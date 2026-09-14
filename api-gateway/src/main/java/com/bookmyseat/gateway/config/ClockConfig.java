package com.bookmyseat.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class ClockConfig {

    /**
     * CLAUDE.md Timekeeping. The gateway's first time-based decision is access-token
     * expiry, and it reads this Clock rather than the system clock so tests can pin it.
     * Error-response timestamps come from here too.
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
