package com.bookmyseat.notification.service;

import com.bookmyseat.notification.EventFixtures;
import com.bookmyseat.notification.KafkaRedisContainerTest;
import com.bookmyseat.notification.client.AuthClient;
import com.bookmyseat.notification.client.EventClient;
import com.bookmyseat.notification.dto.event.BookingConfirmedEvent;
import com.bookmyseat.notification.exception.PermanentLookupException;
import com.bookmyseat.notification.exception.TransientLookupException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.mail.internet.MimeMessage;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The consumer, against a real Kafka broker and a real Redis.
 *
 * <p>JavaMailSender is a mock. MailHog is the right place to see an email and the wrong place
 * to assert on one - these tests care about <i>whether and how many times</i> a message was
 * handed to the mail layer, and about what it said, both of which a captured MimeMessage
 * answers precisely. The end-to-end proof that a message reaches MailHog is the live
 * demonstration, not a unit of this suite.
 *
 * <p>The two lookup clients are mocks too, because the failure modes under test are their
 * failures: "auth-service is down" is not reproducible by pointing at a real auth-service that
 * is up.
 */
@SpringBootTest
class BookingConfirmedListenerKafkaTest extends KafkaRedisContainerTest {

    /** Generous: a redelivery waits out the error handler's 1-second initial backoff. */
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private EventDeduplicator deduplicator;

    @Autowired
    private BookingConfirmedListener listener;

    @MockBean
    private JavaMailSender mailSender;

    @MockBean
    private AuthClient authClient;

    @MockBean
    private EventClient eventClient;

    @BeforeEach
    void setUp() {
        reset(mailSender, authClient, eventClient);
        // A real MimeMessage: the production code builds one through MimeMessageHelper, so a
        // null from the mock would fail for a reason unrelated to anything under test.
        when(mailSender.createMimeMessage())
                .thenAnswer(invocation -> new JavaMailSenderImpl().createMimeMessage());
        when(authClient.fetchUser(anyLong())).thenReturn(EventFixtures.user());
        when(eventClient.fetchShow(anyLong(), anyList())).thenReturn(Optional.of(EventFixtures.show()));
    }

    // ------------------------------------------------------------------------------------
    // The happy path
    // ------------------------------------------------------------------------------------

    @Test
    @DisplayName("an event on the real topic produces one enriched email, and the event is marked seen")
    void sendsOneEnrichedEmail() {
        BookingConfirmedEvent event = EventFixtures.event();

        publish(event);

        MimeMessage sent = awaitOneSend();
        String body = bodyOf(sent);

        assertThat(subjectOf(sent)).isEqualTo("Your booking is confirmed — Coldplay - Music of the Spheres");
        assertThat(body).contains("Hi Arshad,");
        assertThat(body).contains("Coldplay - Music of the Spheres");
        assertThat(body).contains("DY Patil Stadium");
        assertThat(body).contains("Seats: C2, C3");
        assertThat(body).contains("Booking reference: 6");
        assertThat(body).contains("Show this email at the venue.");

        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(deduplicator.seen(event.eventId())).isTrue());
    }

    @Test
    @DisplayName("the show time is printed in Asia/Kolkata, not UTC - 13:00Z is 6:30 PM IST")
    void rendersTheShowTimeInTheVenuesZone() {
        // The instant on the wire is 2026-09-14T13:00:00Z. Printing that as "1:00 PM UTC"
        // would be wrong for every recipient of this system; see ConfirmationEmailSender.
        publish(EventFixtures.event());

        assertThat(bodyOf(awaitOneSend())).contains("14 September 2026, 6:30 PM IST");
    }

    @Test
    @DisplayName("the rupee sign survives into the sent message - it is not mangled to '?'")
    void rendersTheRupeeSign() {
        publish(EventFixtures.event());

        String body = bodyOf(awaitOneSend());
        assertThat(body).contains("Total: ₹900.00");
        assertThat(body).doesNotContain("?900.00");
    }

    // ------------------------------------------------------------------------------------
    // Idempotency
    // ------------------------------------------------------------------------------------

    @Test
    @DisplayName("the SAME eventId delivered twice produces exactly ONE email")
    void deduplicatesARedeliveredEvent() {
        // The outbox is at-least-once by construction, so this is not a hypothetical: it is
        // what happens whenever the publisher dies between Kafka's ack and its own row update.
        BookingConfirmedEvent event = EventFixtures.event();

        publish(event);
        awaitOneSend();
        // Only publish the duplicate once the first is fully handled, so this tests the dedupe
        // check rather than a race the single consumer thread cannot actually have.
        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(deduplicator.seen(event.eventId())).isTrue());

        publish(event);

        // A second event with a DIFFERENT id, published after the duplicate, is the fence: once
        // it has been handled, the duplicate ahead of it in the single partition is certainly
        // past. Without it this would assert on the absence of something that had not happened
        // YET, which any sleep long enough to fix is also long enough to be flaky.
        BookingConfirmedEvent later = EventFixtures.event();
        publish(later);
        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(deduplicator.seen(later.eventId())).isTrue());

        // Two sends total: the original and `later`. The duplicate contributed none.
        verify(mailSender, times(2)).send(any(MimeMessage.class));
    }

    @Test
    @DisplayName("THE ORDERING: when the send fails, the event is NOT marked seen")
    void doesNotMarkSeenWhenTheSendFails() {
        // This is the check-send-mark ordering stated as an assertion. If the mark ran first,
        // this key would exist and the redelivery would be skipped - the email would be lost
        // silently, which is the failure the ordering exists to avoid.
        //
        // Driven through the listener directly rather than through Kafka: a send that always
        // fails would otherwise be retried forever by design, and the test would never end.
        // What is under test here is the order of two statements, and this exercises it
        // against the real Redis without involving the retry loop.
        BookingConfirmedEvent event = EventFixtures.event();
        doThrow(new MailSendException("mail server is down")).when(mailSender).send(any(MimeMessage.class));

        assertThatThrownBy(() -> listener.onBookingConfirmed(recordOf(event)))
                .isInstanceOf(MailSendException.class);

        assertThat(deduplicator.seen(event.eventId()))
                .as("a failed send must leave the event unmarked, so the redelivery sends it")
                .isFalse();
    }

    // ------------------------------------------------------------------------------------
    // The REQUIRED dependency: auth-service
    // ------------------------------------------------------------------------------------

    @Test
    @DisplayName("auth-service DOWN: no email, nothing marked, and the event is retried until it recovers")
    void retriesWhileAuthServiceIsUnavailable() {
        BookingConfirmedEvent event = EventFixtures.event();
        // Down for the first two attempts, then back. The email must survive the outage.
        when(authClient.fetchUser(anyLong()))
                .thenThrow(new TransientLookupException("auth-service unreachable", null))
                .thenThrow(new TransientLookupException("auth-service unreachable", null))
                .thenReturn(EventFixtures.user());

        publish(event);

        // The proof is that the email arrives at all: it can only be the redelivery, because
        // the first two attempts never reached the mail layer.
        MimeMessage sent = awaitOneSend();
        assertThat(bodyOf(sent)).contains("Booking reference: 6");
        verify(authClient, times(3)).fetchUser(EventFixtures.USER_ID);
        assertThat(deduplicator.seen(event.eventId())).isTrue();
    }

    @Test
    @DisplayName("auth-service 404: no email, but marked and committed - a deleted user must not block the partition")
    void treatsAMissingUserAsPermanent() {
        BookingConfirmedEvent deletedUserEvent = EventFixtures.event();
        when(authClient.fetchUser(anyLong()))
                .thenThrow(new PermanentLookupException("user 42 does not exist in auth-service", 404));

        publish(deletedUserEvent);

        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(deduplicator.seen(deletedUserEvent.eventId()))
                        .as("a permanently undeliverable event must be marked, or it is retried forever")
                        .isTrue());
        verify(mailSender, never()).send(any(MimeMessage.class));

        // The point of committing past it: the NEXT event still gets its email. Retrying the
        // 404 forever would block the single partition and stop every confirmation behind it.
        reset(authClient);
        when(authClient.fetchUser(anyLong())).thenReturn(EventFixtures.user());
        BookingConfirmedEvent nextEvent = EventFixtures.event();

        publish(nextEvent);

        MimeMessage sent = awaitOneSend();
        assertThat(bodyOf(sent)).contains("Hi Arshad,");
    }

    // ------------------------------------------------------------------------------------
    // The BEST-EFFORT dependency: event-service
    // ------------------------------------------------------------------------------------

    @Test
    @DisplayName("event-service DOWN: the email is STILL SENT, with ids instead of names")
    void sendsADegradedEmailWhenEventServiceIsUnavailable() {
        // The distinction this whole service is built around: a cosmetic lookup failing must
        // never cost the confirmation. EventClient cannot throw, so "down" reaches the listener
        // as an empty Optional.
        when(eventClient.fetchShow(anyLong(), anyList())).thenReturn(Optional.empty());
        BookingConfirmedEvent event = EventFixtures.event();

        publish(event);

        MimeMessage sent = awaitOneSend();
        String body = bodyOf(sent);

        assertThat(subjectOf(sent))
                .as("the subject drops the title it does not know, rather than inventing one")
                .isEqualTo("Your booking is confirmed");
        assertThat(body).contains("Show #301");
        assertThat(body).contains("Seats: #9001, #9002");
        // The parts that make it a valid receipt survive degradation: they come from the event.
        assertThat(body).contains("Total: ₹900.00");
        assertThat(body).contains("Booking reference: 6");
        assertThat(deduplicator.seen(event.eventId())).isTrue();
    }

    @Test
    @DisplayName("the degraded email does not apologise or mention an internal failure")
    void theDegradedEmailExplainsNothing() {
        // Telling a recipient that an internal service was unreachable is worse than saying
        // less: they cannot act on it, and it turns a valid receipt into an incident report.
        when(eventClient.fetchShow(anyLong(), anyList())).thenReturn(Optional.empty());

        publish(EventFixtures.event());

        assertThat(bodyOf(awaitOneSend()).toLowerCase())
                .doesNotContain("sorry")
                .doesNotContain("apolog")
                .doesNotContain("unavailable")
                .doesNotContain("unable")
                .doesNotContain("error")
                .doesNotContain("failed");
    }

    // ------------------------------------------------------------------------------------
    // Malformed input
    // ------------------------------------------------------------------------------------

    @Test
    @DisplayName("an unparseable payload is committed past, not retried forever")
    void skipsAMalformedPayload() {
        publishRaw("not-json-at-all");

        // The fence again: a later good event proves the partition moved on rather than
        // wedging on bytes that will never parse.
        BookingConfirmedEvent good = EventFixtures.event();
        publish(good);

        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(deduplicator.seen(good.eventId())).isTrue());
        verify(mailSender, times(1)).send(any(MimeMessage.class));
    }

    // ------------------------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------------------------

    private void publish(BookingConfirmedEvent event) {
        try {
            publishRaw(objectMapper.writeValueAsString(event));
        } catch (Exception ex) {
            throw new IllegalStateException("could not serialise the test event", ex);
        }
    }

    private void publishRaw(String payload) {
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(producerConfig())) {
            producer.send(new ProducerRecord<>(BookingConfirmedEvent.TOPIC, "test-key", payload)).get();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted publishing the test event", ex);
        } catch (Exception ex) {
            throw new IllegalStateException("could not publish the test event", ex);
        }
    }

    /** Waits for exactly one send and returns the message that was sent. */
    private MimeMessage awaitOneSend() {
        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        await().atMost(TIMEOUT).untilAsserted(() ->
                verify(mailSender, times(1)).send(captor.capture()));
        return captor.getValue();
    }

    private org.apache.kafka.clients.consumer.ConsumerRecord<String, String> recordOf(
            BookingConfirmedEvent event) {
        try {
            return new org.apache.kafka.clients.consumer.ConsumerRecord<>(
                    BookingConfirmedEvent.TOPIC, 0, 0L, "test-key", objectMapper.writeValueAsString(event));
        } catch (Exception ex) {
            throw new IllegalStateException("could not serialise the test event", ex);
        }
    }

    private static String subjectOf(MimeMessage message) {
        try {
            return message.getSubject();
        } catch (Exception ex) {
            throw new IllegalStateException("could not read the subject", ex);
        }
    }

    /** The body as the recipient would see it, decoded from the MIME part. */
    private static String bodyOf(MimeMessage message) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            message.getDataHandler().writeTo(out);
            return out.toString(java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new IllegalStateException("could not read the message body", ex);
        }
    }
}
