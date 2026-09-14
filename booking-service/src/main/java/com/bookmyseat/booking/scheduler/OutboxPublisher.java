package com.bookmyseat.booking.scheduler;

import com.bookmyseat.booking.config.KafkaConfig;
import com.bookmyseat.booking.config.OutboxProperties;
import com.bookmyseat.booking.entity.OutboxEvent;
import com.bookmyseat.booking.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Limit;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Publishes outbox rows to Kafka topic booking.confirmed, every 2 seconds.
 *
 * <h2>The transactional outbox, and why it exists</h2>
 * A booking's confirmation and its booking.confirmed event must agree: never an event for a
 * confirm that rolled back, never a confirm whose event is lost. The obvious code - commit the
 * booking, then publish to Kafka, or publish inside the transaction - cannot guarantee that.
 * <b>A database row and a Kafka message cannot be committed atomically.</b> They are two systems
 * with no shared transaction: publish first and the commit can still fail, leaving an event for
 * a booking that does not exist; commit first and the process can die before the publish,
 * losing the event for a booking that does.
 *
 * <p>So only the database is committed atomically. The confirming transaction writes the event
 * into the outbox table alongside the booking change (OutboxWriter, Propagation.MANDATORY), and
 * this job publishes it afterwards, outside any transaction the booking cares about. If the
 * transaction rolls back, the row never existed. If it commits, the row survives crashes and
 * outages until it is sent.
 *
 * <h2>The price: at-least-once delivery, so the consumer must be idempotent</h2>
 * The publish and the "mark published" update are, again, two systems. If the process dies after
 * Kafka acknowledged a message but before the row is marked, the next run sends it again. That
 * cannot be closed from this side - it is the same impossibility one step later - so delivery is
 * <b>at least once</b>, never exactly once. Every consumer of booking.confirmed must therefore be
 * idempotent, deduplicating on the event's eventId, which is identical on every redelivery.
 * (The producer's enable.idempotence prevents duplicates from Kafka's own network retries of one
 * send; it cannot recognise a second send of the same outbox row.)
 *
 * <h2>Order, and failure</h2>
 * Rows are sent oldest first, one at a time, each waiting for the broker's acknowledgement
 * before its row is marked published in a short transaction of its own. No database transaction
 * is held open while waiting on Kafka. On the first failure the run stops: the failed row stays
 * unpublished for the next run, and nothing after it is sent, so events never leave out of
 * order. A broker that is down costs one bounded wait per run, not one per row.
 *
 * <h2>Single instance only</h2>
 * Like ExpiredBookingSweeper, this assumes one booking-service instance, and it is switched by
 * the same app.scheduling.enabled. Two instances would both read the same unpublished rows and
 * both send them: still correct under at-least-once, but every event duplicated. Before scaling
 * out, claim rows (SELECT ... FOR UPDATE SKIP LOCKED) or run the publisher on one instance only.
 *
 * <p>Published rows are never deleted, so the table only grows. A cleanup job is deferred.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxPublisher {

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final OutboxProperties properties;

    /**
     * One run. fixedDelay, so the next run starts 2 seconds after this one finishes and runs can
     * never overlap. spring.task.scheduling.pool.size is 2 so a slow run here cannot delay
     * ExpiredBookingSweeper, which shares the scheduler.
     *
     * @return how many events this run published
     */
    @Scheduled(fixedDelayString = "PT2S", initialDelayString = "PT2S")
    public int publishPending() {
        List<OutboxEvent> pending = outboxEventRepository.findUnpublished(Limit.of(properties.batchSize()));
        if (pending.isEmpty()) {
            return 0;
        }

        int published = 0;
        for (OutboxEvent event : pending) {
            if (!send(event)) {
                break;
            }
            outboxEventRepository.markPublished(event.getId());
            published++;
        }

        log.info("outbox run: {} of {} pending event(s) published to {}",
                published, pending.size(), KafkaConfig.BOOKING_CONFIRMED_TOPIC);
        return published;
    }

    /** @return true once the broker has acknowledged this event; false if it did not, for any reason */
    private boolean send(OutboxEvent event) {
        try {
            kafkaTemplate.send(KafkaConfig.BOOKING_CONFIRMED_TOPIC, event.getAggregateId(), event.getPayload())
                    .get(properties.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
            return true;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("outbox publish of event {} interrupted; it stays unpublished", event.getId());
            return false;
        } catch (ExecutionException | TimeoutException | RuntimeException ex) {
            // RuntimeException covers KafkaTemplate failing synchronously, e.g. no broker metadata
            // within max.block.ms. Whatever the cause, the row stays unpublished for the next run.
            log.warn("outbox publish of event {} (booking {}) failed; it stays unpublished and will be retried ({}: {})",
                    event.getId(), event.getAggregateId(), ex.getClass().getSimpleName(), ex.getMessage());
            return false;
        }
    }
}
