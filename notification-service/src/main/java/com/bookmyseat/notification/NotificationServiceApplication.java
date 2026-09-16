package com.bookmyseat.notification;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * notification-service: the consumer end of booking.confirmed.
 *
 * <h2>A consumer, not a service - and that is enforced, not merely intended</h2>
 * This application answers no HTTP requests. Port 8085 serves the actuator health endpoint and
 * nothing else; there are no @RestControllers in this module, and NoHttpSurfaceTest fails the
 * build if one appears. Work arrives from Kafka.
 *
 * <p>It also owns no database. Its only state is one Redis key per handled eventId with a
 * 24-hour TTL, so "database per service" has nothing to hold here - see EventDeduplicator.
 *
 * <p>@EnableKafka is not needed: spring-kafka's auto-configuration registers the listener
 * container factory, and KafkaConsumerConfig replaces only the error handler.
 */
@SpringBootApplication
public class NotificationServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotificationServiceApplication.class, args);
    }
}
