package com.bookmyseat.event.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link SeatBookingFaultProperties}, and nothing else.
 *
 * <p>A file of its own so the fault-injection knob is one grep and one deletion, rather
 * than an annotation hidden on a class that exists for another reason. It contributes no
 * behaviour: with {@code app.fault.book-seats-delay} unset - which is every run except a
 * deliberate reproduction - the bound record holds {@code PT0S} and
 * {@link com.bookmyseat.event.service.InternalSeatService} never sleeps.
 */
@Configuration
@EnableConfigurationProperties(SeatBookingFaultProperties.class)
public class FaultInjectionConfig {
}
