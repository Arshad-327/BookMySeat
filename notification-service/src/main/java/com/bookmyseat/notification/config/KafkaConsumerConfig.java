package com.bookmyseat.notification.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * How this consumer behaves when the listener throws.
 *
 * <h2>The stock error handler would silently drop confirmation emails</h2>
 * Spring Kafka installs a {@link DefaultErrorHandler} with {@code FixedBackOff(0, 9)} when
 * you configure nothing: ten immediate attempts, and then it logs the failure, <b>seeks past
 * the record</b> and carries on. Applied here that means a ten-second auth-service blip
 * consumes all ten attempts in a few milliseconds and the confirmation is gone - no email, no
 * error anyone would connect to a missing booking confirmation days later.
 *
 * <p>That default is reasonable for a consumer whose messages are cheap to lose. This one's
 * are not, so it is replaced. The replacement is written out here rather than left implicit
 * precisely because the default is dangerous in a way that reads as fine.
 *
 * <h2>Unlimited ATTEMPTS, bounded INTERVAL</h2>
 * A transient failure means the event must come back however long the outage lasts, so there
 * is no attempt limit - {@code maxElapsedTime} stays at its default of effectively forever.
 *
 * <p>But the interval is capped at 30 seconds, and the cap is the point. An unbounded
 * exponential backoff keeps doubling: after an hour-long outage the next attempt could be
 * scheduled hours out, so auth-service comes back and the consumer sits idle while mail
 * queues behind it. Recovery time would be governed by how long the outage lasted rather than
 * by when it ended. Capped, the consumer notices within half a minute of the dependency
 * returning, no matter how long it was gone.
 *
 * <h2>The cost, stated plainly: head-of-line blocking</h2>
 * booking.confirmed has one partition, so retrying one event forever means nothing behind it
 * is delivered either. That is acceptable ONLY because the thing being retried is genuinely
 * transient - a dependency that is expected back. It is exactly why permanent failures must
 * never reach this handler: a 404 for a deleted user retried forever would stop every
 * confirmation in the system indefinitely. BookingConfirmedListener catches those and commits
 * past them, and that division of labour is what makes an unlimited retry safe here.
 *
 * <p>The production answer to both halves is a dead-letter topic: a message that cannot be
 * handled goes somewhere durable instead of being retried forever or logged and dropped.
 * There is no DLT here because CLAUDE.md fixes this project at exactly one topic. That is a
 * deliberate trade against a stated constraint, not an oversight.
 */
@Configuration
@Slf4j
public class KafkaConsumerConfig {

    /** One second, doubling, never longer than this between attempts. */
    private static final long INITIAL_INTERVAL_MS = 1_000L;
    private static final double MULTIPLIER = 2.0;
    private static final long MAX_INTERVAL_MS = 30_000L;

    /**
     * Replaces Boot's default error handler for every listener in this service.
     *
     * <p>Every value is set explicitly, including ones that match the framework default. What
     * this backoff does is the service's retry policy, and a policy that has to be looked up
     * in another project's source to be known is not written down.
     */
    @Bean
    public DefaultErrorHandler kafkaErrorHandler() {
        ExponentialBackOff backOff = new ExponentialBackOff();
        backOff.setInitialInterval(INITIAL_INTERVAL_MS);
        backOff.setMultiplier(MULTIPLIER);
        // The cap. Without it the interval grows without bound and a recovered dependency
        // waits out a delay sized by the outage it already ended.
        backOff.setMaxInterval(MAX_INTERVAL_MS);
        // Unlimited attempts: a transient failure is retried until it stops being one.
        backOff.setMaxElapsedTime(Long.MAX_VALUE);

        DefaultErrorHandler handler = new DefaultErrorHandler(
                (record, exception) -> {
                    // Unreachable while the backoff never exhausts. Present so that shortening
                    // it later cannot make records vanish with no trace of the decision.
                    log.error("giving up on offset {} of {}-{} after exhausting retries - "
                                    + "THIS EVENT'S EMAIL IS LOST",
                            record.offset(), record.topic(), record.partition(), exception);
                },
                backOff);

        // Log every failed attempt at WARN, not just the last. During an outage the retry
        // cadence is the only visible sign the consumer is alive and still trying.
        handler.setLogLevel(org.springframework.kafka.KafkaException.Level.WARN);
        return handler;
    }
}
