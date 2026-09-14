package com.bookmyseat.booking.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * One event waiting to be published, or already published. Written by OutboxWriter inside
 * the transaction that produced the event; read and marked by OutboxPublisher. Table from
 * V3__outbox.sql.
 */
@Entity
@Table(name = "outbox")
@Getter
@Setter
@NoArgsConstructor
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The booking id. Also the Kafka message key. */
    @Column(name = "aggregate_id", nullable = false, length = 64)
    private String aggregateId;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    /** JSON. TEXT in MySQL. */
    @Column(name = "payload", nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Column(name = "published", nullable = false)
    private boolean published;

    /**
     * From the injected Clock, never a database default: it orders publication, so it is
     * logic, not bookkeeping (CLAUDE.md Timekeeping). Instant; the column is TIMESTAMP(6).
     */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
