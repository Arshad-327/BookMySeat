package com.bookmyseat.booking.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * The one Kafka topic this system has (CLAUDE.md: exactly one, booking.confirmed).
 *
 * <p>Declared here and created at startup by Spring's KafkaAdmin. The broker has
 * auto-create switched off (docker-compose.infra.yml), so this declaration is the only way the
 * topic comes to exist, and a misspelled name anywhere else fails loudly instead of quietly
 * becoming a second topic. The name lives in {@link #BOOKING_CONFIRMED_TOPIC} and nowhere else.
 *
 * <p>If Kafka is down when booking-service starts, KafkaAdmin logs the failure and startup
 * continues (spring.kafka.admin.fail-fast is false). Bookings do not depend on Kafka - the
 * outbox holds events until it is back.
 */
@Configuration
@EnableConfigurationProperties(OutboxProperties.class)
public class KafkaConfig {

    public static final String BOOKING_CONFIRMED_TOPIC = "booking.confirmed";

    /**
     * <h2>One partition - a decision, not a default</h2>
     * There is one consumer (notification-service), one broker and replication 1. A single
     * partition makes ordering TOTAL: events leave in exactly the order OutboxPublisher sends
     * them, and that order is observable end to end and asserted by a test. Three partitions
     * would copy the shape of a production topic without the substance, and would weaken
     * ordering to per-key only.
     *
     * <p>When that changes: messages are keyed by booking id. If a second consumer instance were
     * ever needed for throughput, partitions can be added and every event for one booking still
     * lands on one partition, so per-booking ordering survives. Knowing when that would be
     * needed is the point; building it now is not.
     */
    @Bean
    public NewTopic bookingConfirmedTopic() {
        return TopicBuilder.name(BOOKING_CONFIRMED_TOPIC)
                .partitions(1)
                .replicas(1)
                .build();
    }
}
