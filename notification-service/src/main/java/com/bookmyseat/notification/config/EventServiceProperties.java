package com.bookmyseat.notification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * event-service, the BEST-EFFORT dependency. Supplies the show title and seat labels.
 *
 * <p>These timeouts bound how long a cosmetic lookup may delay a message that is going to be
 * sent either way. Every second spent here is a second the confirmation is late for the sake
 * of a nicer subject line, which is why they are short and why exceeding them is not an error.
 */
@ConfigurationProperties(prefix = "app.event-service")
public record EventServiceProperties(
        String baseUrl,
        Duration connectTimeout,
        Duration readTimeout
) {
}
