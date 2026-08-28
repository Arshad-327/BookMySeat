package com.bookmyseat.booking.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Binds app.seat-hold.*.
 *
 * <p>ttl is how long a seat stays reserved for a PENDING booking before Redis
 * expires the hold on its own. Ten minutes in normal running, per CLAUDE.md, but
 * a property rather than a constant so it can be dropped to a few seconds while
 * testing expiry behaviour without a rebuild.
 *
 * <p>Duration, not a raw int: the yaml then reads {@code ttl: 600s} and the unit
 * is part of the value rather than a convention someone has to remember. The Lua
 * script needs whole seconds, so {@link com.bookmyseat.booking.service.SeatHoldService}
 * converts once at the call site.
 */
@ConfigurationProperties(prefix = "app.seat-hold")
public record SeatHoldProperties(Duration ttl) {
}
