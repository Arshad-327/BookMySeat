package com.bookmyseat.booking.scheduler;

import com.bookmyseat.booking.MySqlContainerTest;
import com.bookmyseat.booking.client.EventClient;
import com.bookmyseat.booking.client.dto.SeatsBookedResponse;
import com.bookmyseat.booking.config.KafkaConfig;
import com.bookmyseat.booking.entity.Booking;
import com.bookmyseat.booking.entity.BookingSeat;
import com.bookmyseat.booking.entity.BookingStatus;
import com.bookmyseat.booking.entity.OutboxEvent;
import com.bookmyseat.booking.repository.BookingRepository;
import com.bookmyseat.booking.repository.OutboxEventRepository;
import com.bookmyseat.booking.service.SeatHoldService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * OutboxPublisher against a real Kafka broker and real MySQL.
 *
 * <p>The broker is apache/kafka, the same image as docker-compose.infra.yml, with topic
 * auto-creation OFF as it is there - so booking.confirmed exists only because KafkaConfig
 * declared it, and a stray topic name anywhere would fail rather than appear.
 *
 * <p>The topic has one partition, so ordering is total and "published in creation order" is
 * observable here, at the consumer.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OutboxPublisherKafkaTest extends MySqlContainerTest {

    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.1"))
            .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");

    static {
        KAFKA.start();
    }

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        // Overrides MySqlContainerTest's dead port and disabled topic creation.
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("spring.kafka.admin.auto-create", () -> "true");
    }

    private static final TopicPartition PARTITION = new TopicPartition(KafkaConfig.BOOKING_CONFIRMED_TOPIC, 0);

    @Autowired
    private OutboxPublisher publisher;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private Clock clock;

    @MockBean
    private SeatHoldService seatHoldService;

    @MockBean
    private EventClient eventClient;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET FOREIGN_KEY_CHECKS = 0");
                statement.execute("TRUNCATE TABLE outbox");
                statement.execute("TRUNCATE TABLE booking_seats");
                statement.execute("TRUNCATE TABLE bookings");
                statement.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
            return null;
        });
        when(seatHoldService.seatsNotHeldBy(anyLong(), anyList(), anyLong())).thenReturn(List.of());
        when(eventClient.markSeatsBooked(anyLong(), anyList())).thenAnswer(invocation -> {
            List<?> ids = invocation.getArgument(1);
            return new SeatsBookedResponse(invocation.getArgument(0), ids.size(), ids.size());
        });
    }

    @Test
    @DisplayName("exactly one topic exists on the broker - booking.confirmed, one partition - with auto-create off")
    void exactlyOneTopic() throws Exception {
        try (AdminClient admin = AdminClient.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {

            // Internal topics (consumer offsets) are Kafka's own, not ours, and are excluded.
            Set<String> topics = admin.listTopics().names().get(30, TimeUnit.SECONDS);
            assertThat(topics).containsExactly(KafkaConfig.BOOKING_CONFIRMED_TOPIC);

            TopicDescription description = admin.describeTopics(List.of(KafkaConfig.BOOKING_CONFIRMED_TOPIC))
                    .allTopicNames().get(30, TimeUnit.SECONDS).get(KafkaConfig.BOOKING_CONFIRMED_TOPIC);
            assertThat(description.partitions()).hasSize(1);
        }
    }

    @Test
    @DisplayName("pending events are published in CREATION order, not insertion order, keyed by booking, then marked published")
    void publishesInCreationOrder() throws Exception {
        Instant base = Instant.parse("2026-09-14T10:00:00Z");
        // Inserted newest first, so id order is the REVERSE of creation order. Only an
        // ORDER BY created_at can put them on the topic as 101, 102, 103.
        outboxRow("103", base.plusMillis(3));
        outboxRow("102", base.plusMillis(2));
        outboxRow("101", base.plusMillis(1));

        try (KafkaConsumer<String, String> consumer = consumerAtEnd()) {
            assertThat(publisher.publishPending()).isEqualTo(3);

            List<ConsumerRecord<String, String>> records = poll(consumer, 3);
            assertThat(records).extracting(ConsumerRecord::key).containsExactly("101", "102", "103");
            assertThat(records).extracting(ConsumerRecord::value)
                    .containsExactly("{\"n\":101}", "{\"n\":102}", "{\"n\":103}");
        }

        assertThat(outboxEventRepository.findAll()).allMatch(OutboxEvent::isPublished);
        // A second run finds nothing left to send.
        assertThat(publisher.publishPending()).isZero();
    }

    @Test
    @DisplayName("end to end: a real confirm's event arrives on booking.confirmed, keyed by the booking id")
    void confirmedBookingReachesTheTopic() throws Exception {
        Long booking = pendingBooking(7L, 3L);
        mockMvc.perform(post("/api/bookings/{id}/confirm", booking).header("X-User-Id", 7L))
                .andExpect(status().isOk());

        try (KafkaConsumer<String, String> consumer = consumerAtEnd()) {
            assertThat(publisher.publishPending()).isEqualTo(1);

            ConsumerRecord<String, String> record = poll(consumer, 1).get(0);
            assertThat(record.key()).isEqualTo(String.valueOf(booking));

            JsonNode payload = objectMapper.readTree(record.value());
            assertThat(payload.get("eventType").asText()).isEqualTo("BookingConfirmed");
            assertThat(payload.get("bookingId").asLong()).isEqualTo(booking);
            assertThat(payload.get("userId").asLong()).isEqualTo(7L);
            assertThat(UUID.fromString(payload.get("eventId").asText())).isNotNull();
        }
    }

    private void outboxRow(String aggregateId, Instant createdAt) {
        OutboxEvent row = new OutboxEvent();
        row.setAggregateId(aggregateId);
        row.setEventType("BookingConfirmed");
        row.setPayload("{\"n\":" + aggregateId + "}");
        row.setPublished(false);
        row.setCreatedAt(createdAt);
        outboxEventRepository.save(row);
    }

    private Long pendingBooking(Long userId, Long... showSeatIds) {
        Booking booking = new Booking();
        booking.setUserId(userId);
        booking.setShowId(1L);
        booking.setStatus(BookingStatus.PENDING);
        booking.setTotalAmount(new BigDecimal("450.00").multiply(BigDecimal.valueOf(showSeatIds.length)));
        booking.setExpiresAt(Instant.now(clock).plus(Duration.ofMinutes(10)));
        for (Long showSeatId : showSeatIds) {
            BookingSeat seat = new BookingSeat();
            seat.setShowSeatId(showSeatId);
            seat.setPrice(new BigDecimal("450.00"));
            booking.addSeat(seat);
        }
        return bookingRepository.save(booking).getId();
    }

    /**
     * A consumer positioned at the current end of the partition, so it sees only what the test
     * publishes next - not messages earlier tests left on the shared topic.
     */
    private static KafkaConsumer<String, String> consumerAtEnd() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "outbox-test-" + UUID.randomUUID());
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);

        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props);
        consumer.assign(List.of(PARTITION));
        consumer.seekToEnd(List.of(PARTITION));
        consumer.position(PARTITION); // forces the seek to resolve now, before anything is published
        return consumer;
    }

    private static List<ConsumerRecord<String, String>> poll(KafkaConsumer<String, String> consumer, int expected) {
        List<ConsumerRecord<String, String>> records = new ArrayList<>();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (records.size() < expected && System.nanoTime() < deadline) {
            consumer.poll(Duration.ofMillis(500)).forEach(records::add);
        }
        assertThat(records).as("records received from %s", KafkaConfig.BOOKING_CONFIRMED_TOPIC).hasSize(expected);
        return records;
    }
}
