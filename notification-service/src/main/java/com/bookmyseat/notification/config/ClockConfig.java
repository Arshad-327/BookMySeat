package com.bookmyseat.notification.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class ClockConfig {

    /**
     * CLAUDE.md Timekeeping. Every time-based decision in this service reads through this
     * bean, never a bare Instant.now(), so a test can pin it.
     *
     * <p>UTC, like every other service. Note that the zone the confirmation email is
     * RENDERED in is a separate concern and a separate setting
     * (app.notification.display-zone) - reading the clock and formatting a time for a human
     * are different things, and conflating them is how a host's zone leaks into stored data.
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
