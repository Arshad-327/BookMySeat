package com.bookmyseat.notification.dto.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * The payload of booking.confirmed, as this service reads it.
 *
 * <h2>A COPY of booking-service's record, on purpose</h2>
 * The obvious tidy-up is a shared module holding one definition. It is the wrong trade here,
 * and both halves of the reason matter:
 *
 * <ul>
 *   <li>A shared class makes the producer and the consumer deploy together. The point of
 *       putting Kafka between them is that they do not have to; a compile-time dependency
 *       across the topic quietly takes that back.</li>
 *   <li>A copy makes the wire format explicit. This file states what this service actually
 *       relies on, which is less than booking-service sends - see the ignored fields below.
 *       With a shared class, "what the consumer needs" is invisible.</li>
 * </ul>
 *
 * <p>The cost is honest and small: the two definitions can drift. {@code @JsonIgnoreProperties}
 * bounds the damage to the direction that matters - booking-service adding a field must never
 * stop confirmations going out, and without this annotation it would, instantly and for every
 * event. A field being REMOVED still breaks this consumer, which is correct: that is a real
 * contract change and should not be silent.
 *
 * <h2>eventId is the dedupe key</h2>
 * Delivery is at least once (booking-service's OutboxPublisher explains why it cannot be
 * otherwise), so the same event arrives more than once. eventId is identical on every
 * redelivery, which is what makes deduplication possible at all.
 *
 * @param eventId     unique per event, stable across redeliveries
 * @param eventType   always "BookingConfirmed"
 * @param bookingId   printed in the email as the booking reference
 * @param userId      resolved against auth-service - REQUIRED
 * @param showId      resolved against event-service - BEST-EFFORT
 * @param showSeatIds resolved to labels against event-service - BEST-EFFORT
 * @param totalAmount as charged, exact. Comes from the event, never recomputed here
 * @param confirmedAt an Instant; the wire format is UTC with a trailing Z
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BookingConfirmedEvent(
        String eventId,
        String eventType,
        Long bookingId,
        Long userId,
        Long showId,
        List<Long> showSeatIds,
        BigDecimal totalAmount,
        Instant confirmedAt) {

    public static final String TOPIC = "booking.confirmed";

    /**
     * Whether this event carries the ids a confirmation cannot be built without.
     *
     * <p>A payload missing them is malformed rather than unlucky: no retry can repair it, and
     * it is handled as a permanent failure. Checked explicitly because Jackson will happily
     * leave a record component null, and the alternative is a NullPointerException three
     * frames deeper with nothing useful in the log line.
     */
    public boolean isRenderable() {
        return eventId != null && bookingId != null && userId != null;
    }
}
