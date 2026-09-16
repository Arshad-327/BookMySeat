package com.bookmyseat.notification.service;

import com.bookmyseat.notification.client.AuthClient;
import com.bookmyseat.notification.client.EventClient;
import com.bookmyseat.notification.client.dto.InternalShowResponse;
import com.bookmyseat.notification.client.dto.InternalUserResponse;
import com.bookmyseat.notification.dto.event.BookingConfirmedEvent;
import com.bookmyseat.notification.exception.PermanentLookupException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * The whole of this service: one confirmation email per booking.confirmed event.
 *
 * <h2>THE ORDER: check, then SEND, then mark</h2>
 * The dedupe key and the email cannot be written atomically, so one of them is second, and
 * which one decides how this fails:
 *
 * <ul>
 *   <li>Marking first and then sending loses an email if the process dies in between - the
 *       redelivery is skipped as a duplicate and nobody is ever told their booking is
 *       confirmed. <b>That is a support ticket.</b></li>
 *   <li>Sending first and then marking delivers a second copy if the process dies in between.
 *       <b>That is an annoyance.</b></li>
 * </ul>
 *
 * For a booking confirmation the annoyance is plainly the better failure, so the send comes
 * first. The ordering is a decision, not an accident of how the method was typed. Full
 * reasoning, and why no lock is needed around the check, is in {@link EventDeduplicator}.
 *
 * <h2>Two dependencies, different criticality - and the difference is the design</h2>
 * <ul>
 *   <li><b>auth-service is REQUIRED.</b> It supplies the recipient's address. No email
 *       address, no message: the send is not attempted and the event is retried. There is no
 *       fallback and there must not be one, because a confirmation with no recipient is not a
 *       degraded confirmation.</li>
 *   <li><b>event-service is BEST-EFFORT.</b> It supplies the show title, venue and seat
 *       labels. If it cannot be reached the email goes anyway, with ids in place of names.
 *       "Seats C2, C3 for Coldplay" is a better demo; a confirmation that never arrives
 *       because a cosmetic lookup failed is a worse system.</li>
 * </ul>
 *
 * <p>Knowing which dependency is which is the point. It is why {@link AuthClient} classifies
 * every failure it sees and {@link EventClient} classifies none - one client's verdict changes
 * what happens next, the other's cannot.
 *
 * <h2>Transient and permanent failures of the REQUIRED lookup</h2>
 * <ul>
 *   <li><b>Connection refused, timeout, 5xx</b> - transient. The exception escapes this
 *       method, the offset is not committed, the event comes back. Retried forever, because
 *       the user exists and auth-service is expected to return.</li>
 *   <li><b>404</b> - permanent. The user is deleted; no retry makes a deleted row exist.</li>
 *   <li><b>401/403</b> - permanent, but this service is misconfigured rather than the data
 *       being odd. Logged with a distinct message so a log search never confuses the two.</li>
 * </ul>
 *
 * <p><b>Retrying a permanent failure is not caution, it is an outage.</b> booking.confirmed
 * has one partition, and a message that can never succeed and is never committed past blocks
 * the partition head - so a single deleted user would stop every confirmation email in the
 * system, indefinitely. Permanent failures are therefore logged and committed past.
 *
 * <p>The cost is real and is not hidden: <b>that ERROR line is the only record the email ever
 * existed.</b> So it carries the eventId, the bookingId, the userId and the status that
 * caused it - enough for somebody reading it six weeks later to find the booking and contact
 * the customer.
 *
 * <p>The production answer is a dead-letter topic: the message goes somewhere durable instead
 * of only to a log, and can be replayed once the cause is fixed. It is not built here because
 * CLAUDE.md fixes this project at exactly one topic. Log-and-drop is a deliberate trade
 * against a stated constraint, not an oversight.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BookingConfirmedListener {

    private final ObjectMapper objectMapper;
    private final EventDeduplicator deduplicator;
    private final AuthClient authClient;
    private final EventClient eventClient;
    private final ConfirmationEmailSender emailSender;

    /**
     * One event.
     *
     * <p>Returning normally is what commits the offset; throwing is what does not. Every
     * branch below is therefore a choice between "this event is finished with" and "this event
     * must come back", and nothing else.
     *
     * <p>The topic name is a literal rather than a property: booking-service declares the one
     * topic this system has, and a consumer that could be pointed elsewhere by configuration
     * would fail by silently receiving nothing.
     */
    @KafkaListener(topics = BookingConfirmedEvent.TOPIC, groupId = "${spring.kafka.consumer.group-id}")
    public void onBookingConfirmed(ConsumerRecord<String, String> record) {
        BookingConfirmedEvent event = parse(record);
        if (event == null) {
            // Already logged. Unparseable payloads are permanent - the same bytes will not
            // parse next time either - so this returns and commits rather than blocking the
            // partition on a message no retry can fix.
            return;
        }

        log.info("RECEIVED {} for booking {} (event {}, user {}, show {}) from {}-{}@{}",
                event.eventType(), event.bookingId(), event.eventId(), event.userId(),
                event.showId(), record.topic(), record.partition(), record.offset());

        // STEP ONE: check.
        if (deduplicator.seen(event.eventId())) {
            log.info("DUPLICATE event {} for booking {} already handled - no second email sent",
                    event.eventId(), event.bookingId());
            return;
        }

        InternalUserResponse user;
        try {
            // REQUIRED. A transient failure escapes this method on purpose.
            user = authClient.fetchUser(event.userId());
        } catch (PermanentLookupException ex) {
            logPermanentFailure(event, ex);
            // Marked as handled even though nothing was sent. Deliberate: this event can never
            // succeed, and leaving it uncommitted would block every confirmation behind it.
            deduplicator.markSeen(event.eventId());
            return;
        }

        // BEST-EFFORT. Cannot throw - the signature is the contract. An empty result means the
        // email goes out with ids instead of names.
        Optional<InternalShowResponse> show = eventClient.fetchShow(event.showId(), seatIds(event));

        // STEP TWO: send. Before the mark, always. A failure here escapes and is retried.
        emailSender.send(event, user, show);

        // STEP THREE: mark. Only now, once the mail server has actually accepted the message.
        deduplicator.markSeen(event.eventId());
    }

    /**
     * @return the event, or null if this payload can never be handled - already logged
     */
    private BookingConfirmedEvent parse(ConsumerRecord<String, String> record) {
        BookingConfirmedEvent event;
        try {
            event = objectMapper.readValue(record.value(), BookingConfirmedEvent.class);
        } catch (Exception ex) {
            // Same reasoning as a permanent lookup failure: retrying bytes that do not parse
            // blocks the partition forever. The log line carries the offset so the message can
            // be found in the topic, and the raw value so it can be read without Kafka tooling.
            log.error("MALFORMED payload at {}-{}@{} could not be parsed - NO EMAIL WILL BE SENT "
                            + "for it. Raw value: {}",
                    record.topic(), record.partition(), record.offset(), record.value(), ex);
            return null;
        }

        if (!event.isRenderable()) {
            log.error("INCOMPLETE event at {}-{}@{} is missing eventId, bookingId or userId - "
                            + "NO EMAIL WILL BE SENT for it. Raw value: {}",
                    record.topic(), record.partition(), record.offset(), record.value());
            return null;
        }
        return event;
    }

    /**
     * The only record that this confirmation was lost, so it names everything needed to act:
     * eventId, bookingId, userId and the status.
     *
     * <p>The two permanent causes get distinct wording on purpose. A deleted user is odd data
     * and needs a support response; a 401 or 403 is a broken deployment and needs an engineer.
     * A log search that cannot tell them apart sends the wrong person.
     */
    private void logPermanentFailure(BookingConfirmedEvent event, PermanentLookupException ex) {
        if (ex.isMisconfiguration()) {
            log.error("MISCONFIGURED: auth-service refused this service's lookup (status {}). "
                            + "NO EMAIL SENT and none will be retried for event {} / booking {} / user {}. "
                            + "This is a deployment fault in notification-service, not bad data - fix the "
                            + "configuration and republish the event to deliver this confirmation.",
                    ex.getStatus(), event.eventId(), event.bookingId(), event.userId(), ex);
        } else {
            log.error("UNDELIVERABLE: no user {} in auth-service (status {}). NO EMAIL SENT and none "
                            + "will be retried for event {} / booking {}. The booking IS confirmed; only "
                            + "the notification is lost. This line is the only record of it.",
                    event.userId(), ex.getStatus(), event.eventId(), event.bookingId(), ex);
        }
    }

    private List<Long> seatIds(BookingConfirmedEvent event) {
        return event.showSeatIds() == null ? List.of() : event.showSeatIds();
    }
}
