package com.bookmyseat.notification.service;

import com.bookmyseat.notification.config.NotificationProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;

/**
 * Remembers which eventIds have already produced an email.
 *
 * <h2>Why this is needed at all</h2>
 * booking-service publishes from a transactional outbox, and a database row and a Kafka
 * message cannot be committed atomically. If the publisher dies after the broker acknowledged
 * a message but before the row is marked published, the next run sends it again. That gap
 * cannot be closed from the producer side, so delivery is <b>at least once</b> and every
 * consumer must be idempotent. eventId is stable across redeliveries, which is what makes it
 * the key.
 *
 * <h2>One key per eventId, NOT one Redis Set</h2>
 * A Set would be the more literal reading of "dedupe with a Redis set", and it is wrong for
 * the requirement: a Set has a single TTL for the whole key, so every remembered event would
 * expire at the same moment rather than 24 hours after its own arrival. Members would also
 * accumulate with nothing to evict them between expiries. One key each is what a
 * per-event TTL actually is.
 *
 * <h2>THE ORDER: check, then SEND, then mark. Chosen, not stumbled into.</h2>
 * The email and the Redis key are - again - two systems that cannot be written atomically.
 * Something has to be second, and which one decides the failure mode:
 *
 * <ul>
 *   <li><b>Mark first, then send.</b> A crash in between loses the email permanently: the
 *       event is remembered as handled and the redelivery is skipped. The recipient's booking
 *       is confirmed and they were never told. That is a support ticket.</li>
 *   <li><b>Send first, then mark.</b> A crash in between sends a second copy on redelivery.
 *       That is an annoyance in an inbox.</li>
 * </ul>
 *
 * A duplicate confirmation is strictly less bad than a missing one, so sending comes first.
 * The window is genuinely narrow - one Redis write - but the ordering is what decides which
 * way the system fails when it does, and it is not an accident.
 *
 * <h2>Why check-then-act needs no lock HERE</h2>
 * Between {@link #seen} and {@link #markSeen} another consumer could, in general, handle the
 * same event and both would send. It cannot happen in this service: booking.confirmed has one
 * partition and spring.kafka.listener.concurrency is 1, so exactly one thread in one instance
 * ever runs this sequence, and there is nothing to interleave with.
 *
 * <p><b>That is a property of the configuration, not of this code.</b> Raising concurrency, or
 * running a second instance in the same consumer group with more partitions, makes it a real
 * race. The fix then is SET NX as an atomic claim - which trades this ordering for the other
 * one, and so must be a deliberate decision about which failure is preferred, not a quiet
 * addition of a flag.
 */
@Service
@RequiredArgsConstructor
public class EventDeduplicator {

    private static final String KEY_PREFIX = "notif:dedupe:";

    private final StringRedisTemplate redisTemplate;
    private final NotificationProperties properties;

    /** CLAUDE.md Timekeeping: the stored instant comes from the injected Clock. */
    private final Clock clock;

    /** Whether this event has already been handled. Step ONE of check-send-mark. */
    public boolean seen(String eventId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(KEY_PREFIX + eventId));
    }

    /**
     * Records this event as handled. Step THREE - called only after the email has actually
     * been handed to the mail server, never before.
     *
     * <p>The value is the instant it was handled. Nothing reads it; it is there so that a
     * human looking at a key in redis-cli during a demo can see when, not just whether.
     */
    public void markSeen(String eventId) {
        redisTemplate.opsForValue().set(
                KEY_PREFIX + eventId,
                Instant.now(clock).toString(),
                properties.dedupeTtl());
    }
}
