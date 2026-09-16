package com.bookmyseat.notification.service;

import com.bookmyseat.notification.client.dto.InternalSeatLabelResponse;
import com.bookmyseat.notification.client.dto.InternalShowResponse;
import com.bookmyseat.notification.client.dto.InternalUserResponse;
import com.bookmyseat.notification.config.NotificationProperties;
import com.bookmyseat.notification.dto.event.BookingConfirmedEvent;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Builds and sends the confirmation.
 *
 * <h2>Two messages, and the difference between them is the design</h2>
 * The <b>enriched</b> message names the event, the venue and the seats. The <b>degraded</b>
 * one prints ids. Which is sent depends solely on whether event-service answered, and the
 * degraded message is a complete, useful receipt rather than an apology:
 *
 * <ul>
 *   <li>It does <b>not</b> say a lookup failed, and it does not apologise. The recipient
 *       cannot act on either fact, and telling them an internal service was unreachable is
 *       strictly worse than saying less. A booking reference and a total are a valid receipt
 *       on their own.</li>
 *   <li>The subject carries the event title when it is known and drops it when it is not, so
 *       an inbox scan shows what was booked without opening anything.</li>
 * </ul>
 *
 * <h2>UTF-8 is explicit, and was verified rather than assumed</h2>
 * The body contains a rupee sign (U+20B9) and an em dash (U+2014); neither is ASCII. A MIME
 * part that defaults to us-ascii turns them into "?" - which would be discovered in a
 * screenshot, not in a test. So the message is built with {@link MimeMessageHelper} on an
 * explicit UTF-8 charset, and the encoding was checked end to end against the real MailHog:
 * a part declaring charset=utf-8 stores U+20B9 intact.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ConfirmationEmailSender {

    /**
     * "14 September 2026, 6:30 PM IST".
     *
     * <h2>Rendered in the venue's zone, never in UTC</h2>
     * {@code startsAt} is an {@link java.time.Instant} everywhere - on the wire, in event_db,
     * in this service (CLAUDE.md Timekeeping). An instant is a point in time and has no zone,
     * so printing one requires choosing a zone, and "18:30 UTC" in a ticket email is wrong
     * for every single recipient of this system.
     *
     * <p><b>This is the display-side consequence of the P2.1 decision to store show times as
     * instants.</b> That decision is right, and it is affordable only because of a fact
     * outside the code: every venue in this project is in one timezone that does not observe
     * daylight saving. So a single configured zone can be applied at render time and be
     * correct for every show.
     *
     * <p>An international venue breaks that immediately - a show in London and a show in
     * Mumbai cannot both render correctly against one setting - and the fix is not a
     * formatter change. The zone would have to be stored WITH the show, because it is a fact
     * about the venue rather than about this service's configuration. Recording that here so
     * the limit is found before a venue is added, rather than after.
     *
     * <p>Locale.ENGLISH is pinned so the month name never follows the host's locale.
     */
    private static final DateTimeFormatter SHOW_TIME =
            DateTimeFormatter.ofPattern("d MMMM yyyy, h:mm a z", Locale.ENGLISH);

    private final JavaMailSender mailSender;
    private final NotificationProperties properties;

    /**
     * Sends one confirmation.
     *
     * <p>Throws if the mail server cannot be reached. That is correct and deliberate: a send
     * failure is transient, the caller has not marked the event handled yet, and letting it
     * escape the listener is what brings the event back. See {@link EventDeduplicator} for
     * why the mark comes after this call and never before.
     */
    public void send(BookingConfirmedEvent event, InternalUserResponse user,
                     Optional<InternalShowResponse> show) {

        String subject = subject(show);
        String body = show.map(details -> enrichedBody(event, user, details))
                .orElseGet(() -> degradedBody(event, user));

        MimeMessage message = mailSender.createMimeMessage();
        try {
            // Explicit UTF-8: see the class javadoc. The rupee sign depends on it.
            MimeMessageHelper helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
            helper.setFrom(properties.from());
            helper.setTo(user.email());
            helper.setSubject(subject);
            helper.setText(body, false);
        } catch (MessagingException ex) {
            // Building the message failed, not sending it. Unchecked so it escapes the
            // listener like any other failure and the event is retried.
            throw new IllegalStateException(
                    "could not build the confirmation for booking " + event.bookingId(), ex);
        }

        mailSender.send(message);

        log.info("SENT confirmation for booking {} (event {}) to {} - {}",
                event.bookingId(), event.eventId(), user.email(),
                show.isPresent() ? "enriched with show details" : "DEGRADED to ids, event-service unavailable");
    }

    /**
     * The event title when known, so an inbox scan shows what was booked.
     *
     * <p>U+2014, an em dash, is written as an escape for the same reason as the rupee
     * sign: the glyph's fate should not depend on this file's own encoding. A subject is also
     * header-encoded rather than sent as raw bytes, so it is checked separately in the demo.
     */
    private String subject(Optional<InternalShowResponse> show) {
        return show.map(InternalShowResponse::eventTitle)
                .filter(title -> !title.isBlank())
                .map(title -> "Your booking is confirmed \u2014 " + title)
                .orElse("Your booking is confirmed");
    }

    private String enrichedBody(BookingConfirmedEvent event, InternalUserResponse user,
                                InternalShowResponse show) {
        return """
                Hi %s,

                Your booking is confirmed.

                  %s
                  %s
                  %s

                  Seats: %s
                  Total: %s

                  Booking reference: %s

                Show this email at the venue.

                \u2014 BookMySeat
                """.formatted(
                greeting(user),
                show.eventTitle(),
                show.venueName(),
                showTime(show),
                seatLabels(show.seats()),
                money(event.totalAmount()),
                event.bookingId());
    }

    /**
     * Terse. No explanation, no apology - see the class javadoc.
     *
     * <p>It also drops "Show this email at the venue": without a venue named, the instruction
     * has nothing to attach to.
     */
    private String degradedBody(BookingConfirmedEvent event, InternalUserResponse user) {
        return """
                Hi %s,

                Your booking is confirmed.

                  Show #%s
                  Seats: %s
                  Total: %s

                  Booking reference: %s

                \u2014 BookMySeat
                """.formatted(
                greeting(user),
                event.showId(),
                seatIds(event.showSeatIds()),
                money(event.totalAmount()),
                event.bookingId());
    }

    /**
     * The name as auth-service holds it, whole.
     *
     * <p>No splitting on whitespace to find a "first name": that is wrong for a large share of
     * the world's names, and being greeted by your full name is a far smaller fault than being
     * greeted by the wrong part of it. full_name is nullable, so "there" is the fallback.
     */
    private String greeting(InternalUserResponse user) {
        return user.fullName() == null || user.fullName().isBlank() ? "there" : user.fullName();
    }

    private String showTime(InternalShowResponse show) {
        if (show.startsAt() == null) {
            return "";
        }
        return SHOW_TIME.format(show.startsAt().atZone(properties.displayZone()));
    }

    /** "C2, C3" - already joined by event-service, so the two never disagree on spelling. */
    private String seatLabels(List<InternalSeatLabelResponse> seats) {
        if (seats == null || seats.isEmpty()) {
            return "-";
        }
        return seats.stream().map(InternalSeatLabelResponse::label).collect(Collectors.joining(", "));
    }

    /** "#9001, #9002" - hashed so a reader can see these are references, not seat numbers. */
    private String seatIds(List<Long> showSeatIds) {
        if (showSeatIds == null || showSeatIds.isEmpty()) {
            return "-";
        }
        return showSeatIds.stream().map(id -> "#" + id).collect(Collectors.joining(", "));
    }

    /**
     * The amount exactly as the event carried it, never recomputed here.
     *
     * <p>U+20B9, the rupee sign, written as an escape rather than as a literal so the source
     * file's own encoding can never be what breaks it. Verified against MailHog rather than
     * assumed - see the class javadoc on encoding.
     */
    private String money(BigDecimal totalAmount) {
        return totalAmount == null ? "-" : "\u20B9" + totalAmount.toPlainString();
    }
}
