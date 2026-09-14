package com.bookmyseat.booking.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on {@code @Scheduled} jobs: ExpiredBookingSweeper and OutboxPublisher.
 *
 * <p>Behind {@code app.scheduling.enabled} (default true) for two reasons. Tests switch
 * it off so a background job cannot change rows under an assertion; they run the jobs
 * directly instead. And until both jobs have a distributed lock or row claiming, this is
 * the knob that keeps a second instance from sweeping and publishing alongside the first.
 *
 * <p>The two jobs share one scheduler, sized to two threads by spring.task.scheduling.pool.size,
 * so a slow Kafka send in the publisher cannot hold up the sweep.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
