package com.bookmyseat.booking.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class ClockConfig {

    /**
     * CLAUDE.md Timekeeping. Every instant in this service is an Instant, and
     * Clock.instant() is zone-independent, so this zone is never consulted for
     * any stored value. It is pinned to UTC anyway so that nothing can later
     * reintroduce a host-dependent reading of the clock by accident.
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
