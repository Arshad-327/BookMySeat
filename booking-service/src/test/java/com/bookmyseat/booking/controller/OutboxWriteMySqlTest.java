package com.bookmyseat.booking.controller;

import com.bookmyseat.booking.KafkaTemplateSpyConfiguration;
import com.bookmyseat.booking.MySqlContainerTest;
import com.bookmyseat.booking.client.EventClient;
import com.bookmyseat.booking.client.dto.SeatsBookedResponse;
import com.bookmyseat.booking.entity.Booking;
import com.bookmyseat.booking.entity.BookingSeat;
import com.bookmyseat.booking.entity.BookingStatus;
import com.bookmyseat.booking.exception.SeatBookingRejectedException;
import com.bookmyseat.booking.repository.BookingRepository;
import com.bookmyseat.booking.service.OutboxWriter;
import com.bookmyseat.booking.service.SeatHoldService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.IllegalTransactionStateException;

import java.math.BigDecimal;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The outbox row is written in the confirming transaction, and only there - against real MySQL.
 *
 * <p>Kafka is unreachable in this class (MySqlContainerTest points it at a dead port) and the
 * real KafkaTemplate is spied on, not replaced. So "confirm succeeds with Kafka down" and
 * "confirm never touches Kafka" are both asserted on the genuine article.
 */
@Import(KafkaTemplateSpyConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class OutboxWriteMySqlTest extends MySqlContainerTest {

    private static final long SHOW_ID = 1L;
    private static final BigDecimal PRICE = new BigDecimal("450.00");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private OutboxWriter outboxWriter;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private Clock clock;

    @MockBean
    private SeatHoldService seatHoldService;

    @MockBean
    private EventClient eventClient;

    /** The real template, wrapped in a spy by KafkaTemplateSpyConfiguration. */
    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

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
        Mockito.clearInvocations(kafkaTemplate);
        when(seatHoldService.seatsNotHeldBy(anyLong(), anyList(), anyLong())).thenReturn(List.of());
        when(eventClient.markSeatsBooked(anyLong(), anyList(), anyLong())).thenAnswer(invocation -> {
            List<?> ids = invocation.getArgument(1);
            return new SeatsBookedResponse(invocation.getArgument(0), ids.size(), ids.size());
        });
    }

    @Test
    @DisplayName("a successful confirm writes exactly one unpublished booking.confirmed row - with Kafka down, and without touching Kafka")
    void confirmWritesOneEventWithoutKafka() throws Exception {
        Long booking = pendingBooking(7L, 4L, 5L);

        confirm(booking, 7L)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));

        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT * FROM outbox");
        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.get(0);
        assertThat(row.get("aggregate_id")).isEqualTo(String.valueOf(booking));
        assertThat(row.get("event_type")).isEqualTo("BookingConfirmed");
        assertThat(row.get("published")).isEqualTo(false);
        assertThat(row.get("created_at")).isNotNull();

        JsonNode payload = objectMapper.readTree((String) row.get("payload"));
        assertThat(UUID.fromString(payload.get("eventId").asText())).isNotNull();
        assertThat(payload.get("eventType").asText()).isEqualTo("BookingConfirmed");
        assertThat(payload.get("bookingId").asLong()).isEqualTo(booking);
        assertThat(payload.get("userId").asLong()).isEqualTo(7L);
        assertThat(payload.get("showId").asLong()).isEqualTo(SHOW_ID);
        assertThat(payload.get("showSeatIds").toString()).isEqualTo("[4,5]");
        assertThat(payload.get("totalAmount").decimalValue()).isEqualByComparingTo("900.00");
        assertThat(payload.get("confirmedAt").asText()).endsWith("Z");
        // Thin event: nothing personal in the log.
        assertThat(payload.has("email")).isFalse();

        // The confirm path never reached for Kafka - and Kafka was unreachable throughout.
        verifyNoInteractions(kafkaTemplate);
    }

    @Test
    @DisplayName("event-service refusing the seats rolls the event back with the confirmation: no row, booking still PENDING")
    void refusedConfirmLeavesNoEvent() throws Exception {
        Long booking = pendingBooking(8L, 6L);
        when(eventClient.markSeatsBooked(anyLong(), anyList(), anyLong())).thenThrow(new SeatBookingRejectedException(
                SHOW_ID, List.of(6L), "Show 1: seat(s) [6] are already BOOKED", null));

        confirm(booking, 8L).andExpect(status().isConflict());

        assertThat(count("SELECT COUNT(*) FROM outbox")).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM bookings WHERE id = ?", String.class, booking))
                .isEqualTo("PENDING");
    }

    @Test
    @DisplayName("a second confirmation stopped by the layer-3 constraint leaves no event of its own")
    void constraintViolationLeavesNoEvent() throws Exception {
        Long first = pendingBooking(11L, 1L);
        Long second = pendingBooking(12L, 1L);

        confirm(first, 11L).andExpect(status().isOk());
        confirm(second, 12L).andExpect(status().isConflict());

        assertThat(jdbcTemplate.queryForList("SELECT aggregate_id FROM outbox", String.class))
                .containsExactly(String.valueOf(first));
    }

    @Test
    @DisplayName("OutboxWriter refuses to run outside a transaction (Propagation.MANDATORY), and writes nothing")
    void writerRequiresAnExistingTransaction() {
        Booking booking = bookingRepository.findById(pendingBooking(13L, 2L)).orElseThrow();

        assertThatThrownBy(() -> outboxWriter.recordBookingConfirmed(booking))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(count("SELECT COUNT(*) FROM outbox")).isZero();
    }

    @Test
    @DisplayName("V3 created the publisher's index on (published, created_at), in that column order")
    void outboxIndexExists() {
        List<String> columns = jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.statistics "
                        + "WHERE table_schema = DATABASE() AND table_name = 'outbox' "
                        + "AND index_name = 'idx_outbox_published_created' ORDER BY seq_in_index",
                String.class);

        assertThat(columns).containsExactly("published", "created_at");
    }

    private Long pendingBooking(Long userId, Long... showSeatIds) {
        Booking booking = new Booking();
        booking.setUserId(userId);
        booking.setShowId(SHOW_ID);
        booking.setStatus(BookingStatus.PENDING);
        booking.setTotalAmount(PRICE.multiply(BigDecimal.valueOf(showSeatIds.length)));
        booking.setExpiresAt(Instant.now(clock).plus(Duration.ofMinutes(10)));
        for (Long showSeatId : showSeatIds) {
            BookingSeat seat = new BookingSeat();
            seat.setShowSeatId(showSeatId);
            seat.setPrice(PRICE);
            booking.addSeat(seat);
        }
        return bookingRepository.save(booking).getId();
    }

    private ResultActions confirm(Long bookingId, Long userId) throws Exception {
        return mockMvc.perform(post("/api/bookings/{id}/confirm", bookingId).header("X-User-Id", userId));
    }

    private long count(String sql) {
        return jdbcTemplate.queryForObject(sql, Long.class);
    }
}
