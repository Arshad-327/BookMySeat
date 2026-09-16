package com.bookmyseat.notification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.time.ZoneId;

/**
 * How the confirmation is addressed, remembered and rendered.
 *
 * @param fromAddress  the sender. MailHog accepts anything; a real relay would not
 * @param dedupeTtl    how long a handled eventId is remembered in Redis
 * @param displayZone  the zone show times are PRINTED in, applied only at render time
 */
@ConfigurationProperties(prefix = "app.notification")
public record NotificationProperties(
        String from,
        Duration dedupeTtl,
        ZoneId displayZone
) {
}
