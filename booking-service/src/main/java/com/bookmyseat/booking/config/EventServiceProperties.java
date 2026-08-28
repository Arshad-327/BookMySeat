package com.bookmyseat.booking.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Binds app.event-service.*.
 *
 * <p>base-url is a property, not a constant, so the same jar runs on the host
 * (http://localhost:8082) and inside Compose (http://event-service:8082) with only
 * an environment variable changing. CLAUDE.md: services find each other by Docker
 * Compose service name, never by service discovery.
 */
@ConfigurationProperties(prefix = "app.event-service")
public record EventServiceProperties(
        String baseUrl,
        Duration connectTimeout,
        Duration readTimeout
) {
}
