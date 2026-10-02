package com.bookmyseat.booking.service;

import com.bookmyseat.booking.dto.event.BookingConfirmedEvent;
import com.bookmyseat.booking.entity.Booking;
import com.bookmyseat.booking.entity.OutboxEvent;
import com.bookmyseat.booking.mapper.BookingEventMapper;
import com.bookmyseat.booking.repository.OutboxEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Records an event in the outbox, as part of the transaction that caused it.
 *
 * <h2>Propagation.MANDATORY: "same transaction" enforced, not requested</h2>
 * The outbox pattern is only correct if the event row commits exactly when the booking change
 * commits: never an event for a confirm that rolled back, never a confirm with no event.
 * MANDATORY makes Spring throw IllegalTransactionStateException if this is called with no
 * transaction already active. So the rule cannot be broken by accident - a caller that forgets
 * its transaction fails loudly on the first call, rather than silently writing an event in a
 * transaction of its own that commits even if the booking later rolls back. It also never starts
 * a new transaction, which REQUIRES_NEW would, with exactly that bug.
 *
 * <p>Nothing here talks to Kafka. The row is published later by OutboxPublisher.
 */
@Service
@RequiredArgsConstructor
public class OutboxWriter {

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    /** CLAUDE.md Timekeeping: created_at and confirmedAt both come from the injected Clock. */
    private final Clock clock;

    /**
     * Writes the booking.confirmed event for a booking that this transaction is confirming.
     *
     * @throws org.springframework.transaction.IllegalTransactionStateException if no transaction
     *         is active
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public OutboxEvent recordBookingConfirmed(Booking booking) {
        // Truncated to microseconds at assignment (CLAUDE.md Timekeeping): created_at is
        // TIMESTAMP(6) and MySQL rounds a nanosecond Instant into it. This one instant is
        // written twice - into the column, and into the JSON payload as confirmedAt - and
        // untruncated the two disagreed below a microsecond within a single row. Truncated
        // once, here, they are the same value. The payload loses only digits no consumer was
        // ever promised.
        //
        // The rule is uniform - every Instant written from Java to a TIMESTAMP(6) column -
        // and it is proven once, by HoldExpiryRoundTripMySqlTest, on bookings.expires_at.
        // This write site has no round-trip test of its own, and that is not an oversight:
        // it is the same one-line change guarded by the same mechanism.
        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
        BookingConfirmedEvent event = BookingEventMapper.toConfirmedEvent(booking, UUID.randomUUID(), now);

        OutboxEvent row = new OutboxEvent();
        row.setAggregateId(String.valueOf(booking.getId()));
        row.setEventType(BookingConfirmedEvent.TYPE);
        row.setPayload(serialise(event));
        row.setPublished(false);
        row.setCreatedAt(now);
        return outboxEventRepository.save(row);
    }

    private String serialise(BookingConfirmedEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException ex) {
            // Unchecked, so it rolls back the confirming transaction with it: a confirm whose
            // event cannot be recorded must not commit.
            throw new IllegalStateException("could not serialise " + event.eventType() + " for booking "
                    + event.bookingId(), ex);
        }
    }
}
