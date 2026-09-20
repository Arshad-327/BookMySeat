package com.bookmyseat.event.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Fault injection for the internal seat-booking write. Binds {@code app.fault.*}.
 *
 * <h2>What this is for, and why it is not a test double</h2>
 * Review finding #1 (docs/review-2026-09-18.md) predicts that a slow - not crashed -
 * event-service orphans a seat: it commits {@code show_seats.status = 'BOOKED'} while
 * booking-service's 5s read timeout has already fired and rolled the confirm back.
 * Proving that needs the REAL commit to happen in the REAL database after the caller has
 * given up, which no mock can demonstrate - a mock proves only what the test stubbed.
 * So the delay is injected into the real write path and the real transaction.
 *
 * <h2>Defaults to zero, and never sleeps unless asked</h2>
 * {@link #bookSeatsDelay()} is {@code PT0S} unless something sets it. At zero,
 * {@link com.bookmyseat.event.service.InternalSeatService} performs no sleep at all - not
 * a zero-length one - so the production path is a single {@code isZero()} branch and
 * nothing more. It is set only from the command line of a deliberate reproduction run,
 * never from application.yml, and a non-zero value logs a warning at every call so an
 * instance running with it cannot be mistaken for a healthy one.
 */
@ConfigurationProperties(prefix = "app.fault")
public record SeatBookingFaultProperties(Duration bookSeatsDelay) {

    public SeatBookingFaultProperties {
        if (bookSeatsDelay == null) {
            bookSeatsDelay = Duration.ZERO;
        }
    }

    /** True when a reproduction run has armed the delay. False in every normal run. */
    public boolean isArmed() {
        return !bookSeatsDelay.isZero() && !bookSeatsDelay.isNegative();
    }
}
