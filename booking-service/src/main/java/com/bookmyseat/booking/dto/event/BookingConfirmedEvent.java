package com.bookmyseat.booking.dto.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * The payload of the one Kafka topic, booking.confirmed. Written to the outbox as JSON in the
 * confirming transaction, published afterwards.
 *
 * <h2>A THIN event: ids, not state - chosen deliberately</h2>
 * It carries userId, showId and seat ids, and nothing those ids resolve to: no email, no name,
 * no show title or start time.
 *
 * <p>This is one side of a known pair of designs:
 * <ul>
 *   <li>A <b>thin</b> event carrying only IDs keeps the log clean and avoids stale copies, at the
 *       cost of runtime coupling to the services that resolve them - notification-service must
 *       ask auth-service for the email and event-service for the show.</li>
 *   <li>A <b>fat</b> event carrying full state removes that coupling, at the cost of duplicating
 *       data that can go stale and cannot be purged.</li>
 * </ul>
 * Thin was chosen for the retention reason. A Kafka topic is an append-only log: one user's
 * record cannot be selectively deleted from it, and the outbox table keeps its rows too. Putting
 * an email address in this payload would copy personal data into two stores with no selective
 * purge. booking-service also does not know the email - it lives in auth_db - and fetching it at
 * confirm time would make an auth-service outage a booking outage for the sake of a notification.
 *
 * <h2>Consumers must be idempotent</h2>
 * Delivery is at least once (see OutboxPublisher), so the same event can arrive twice.
 * {@code eventId} is the key to deduplicate on: the same event always carries the same eventId,
 * however many times it is delivered.
 *
 * @param eventId      unique per event, stable across redeliveries - the consumer's dedupe key
 * @param eventType    always {@link #TYPE}
 * @param bookingId    the booking; also the Kafka message key
 * @param userId       auth_db user id, resolved by the consumer
 * @param showId       event_db show id, resolved by the consumer
 * @param showSeatIds  event_db show_seats ids that were sold
 * @param totalAmount  as charged, exact
 * @param confirmedAt  from the injected Clock, an Instant serialising with a trailing Z
 */
public record BookingConfirmedEvent(
        String eventId,
        String eventType,
        Long bookingId,
        Long userId,
        Long showId,
        List<Long> showSeatIds,
        BigDecimal totalAmount,
        Instant confirmedAt) {

    public static final String TYPE = "BookingConfirmed";
}
