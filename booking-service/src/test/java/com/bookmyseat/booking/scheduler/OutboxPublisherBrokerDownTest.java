package com.bookmyseat.booking.scheduler;

import com.bookmyseat.booking.KafkaTemplateSpyConfiguration;
import com.bookmyseat.booking.MySqlContainerTest;
import com.bookmyseat.booking.entity.OutboxEvent;
import com.bookmyseat.booking.repository.OutboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * A publish failure leaves events unpublished for retry - with a genuinely unreachable broker
 * (MySqlContainerTest's dead port), and the real KafkaTemplate spied on rather than mocked.
 */
@Import(KafkaTemplateSpyConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class OutboxPublisherBrokerDownTest extends MySqlContainerTest {

    @Autowired
    private OutboxPublisher publisher;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** The real template, wrapped in a spy by KafkaTemplateSpyConfiguration. */
    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE TABLE outbox");
        Mockito.clearInvocations(kafkaTemplate);
    }

    @Test
    @DisplayName("broker down: nothing is marked published, no exception escapes, and the run stops at the first failure")
    void failedPublishLeavesEventsForRetry() {
        Instant base = Instant.parse("2026-09-14T10:00:00Z");
        for (int i = 1; i <= 3; i++) {
            OutboxEvent row = new OutboxEvent();
            row.setAggregateId(String.valueOf(i));
            row.setEventType("BookingConfirmed");
            row.setPayload("{\"n\":" + i + "}");
            row.setCreatedAt(base.plusMillis(i));
            outboxEventRepository.save(row);
        }

        int published = publisher.publishPending();

        assertThat(published).isZero();
        assertThat(outboxEventRepository.findAll()).hasSize(3).noneMatch(OutboxEvent::isPublished);
        // One attempt, not three: the run stops at the first failure, so a down broker costs one
        // bounded wait per run and nothing after the failed event is sent out of order.
        verify(kafkaTemplate, times(1)).send(anyString(), anyString(), anyString());

        // And the next run tries again, starting from the same oldest event.
        assertThat(publisher.publishPending()).isZero();
        verify(kafkaTemplate, times(2)).send(anyString(), anyString(), anyString());
        assertThat(outboxEventRepository.findAll()).noneMatch(OutboxEvent::isPublished);
    }
}
