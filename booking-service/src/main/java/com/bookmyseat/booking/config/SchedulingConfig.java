package com.bookmyseat.booking.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on {@code @Scheduled} jobs - today, only ExpiredBookingSweeper.
 *
 * <p>Behind {@code app.scheduling.enabled} (default true) for two reasons. Tests switch
 * it off so a background sweep cannot change rows under an assertion; they call the
 * sweep directly instead. And until the sweeper has a distributed lock, this is the
 * knob that keeps a second instance from sweeping alongside the first.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
