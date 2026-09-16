package com.bookmyseat.notification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * auth-service, the REQUIRED dependency. No email address, no message.
 *
 * <p>The timeouts are short for the usual reason - a hung dependency must fail rather than
 * hold the single consumer thread - but note what "fail" means here: the event is redelivered
 * and tried again, not dropped. Failing fast costs a retry, not a confirmation.
 */
@ConfigurationProperties(prefix = "app.auth-service")
public record AuthServiceProperties(
        String baseUrl,
        Duration connectTimeout,
        Duration readTimeout
) {
}
