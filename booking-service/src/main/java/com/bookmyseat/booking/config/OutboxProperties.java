package com.bookmyseat.booking.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * app.outbox.*
 *
 * @param batchSize   the most events one publisher run sends
 * @param sendTimeout the most one publish waits for the broker's acknowledgement. Bounded so a
 *                    slow or absent broker cannot hold the scheduler thread indefinitely.
 */
@ConfigurationProperties("app.outbox")
public record OutboxProperties(int batchSize, Duration sendTimeout) {

    public OutboxProperties {
        if (batchSize < 1) {
            throw new IllegalArgumentException("app.outbox.batch-size must be at least 1");
        }
        if (sendTimeout == null || sendTimeout.isNegative() || sendTimeout.isZero()) {
            throw new IllegalArgumentException("app.outbox.send-timeout must be positive");
        }
    }
}
