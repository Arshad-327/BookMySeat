package com.bookmyseat.booking.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "bookings")
@Getter
@Setter
@NoArgsConstructor
public class Booking {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** From the X-User-Id header. No association: users live in auth_db. */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** A shows row in event_db. Plain id, no association across schemas. */
    @Column(name = "show_id", nullable = false)
    private Long showId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private BookingStatus status;

    /** BigDecimal, not double: money is exact. Column is DECIMAL(10,2). */
    @Column(name = "total_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal totalAmount;

    /**
     * Not unique in the schema, on purpose - see V1__initial_schema.sql.
     *
     * <p>Nothing reads this column yet either: the create path does not look for an
     * existing booking with the same key before inserting, so a retried request
     * produces a second booking. Left unimplemented while the failure is measured.
     */
    @Column(name = "idempotency_key", length = 64)
    private String idempotencyKey;

    /** Instant, never LocalDateTime (CLAUDE.md Timekeeping). Column is TIMESTAMP(6). */
    @Column(name = "expires_at")
    private Instant expiresAt;

    /**
     * Defaulted by the database (DEFAULT CURRENT_TIMESTAMP(6)), never written by JPA.
     *
     * <p>@Generated(INSERT) makes Hibernate read the value back after the insert.
     * Without it this field stays null on the instance that was just saved, and the
     * create response reports createdAt: null for a row that does in fact have a
     * timestamp - a lie to the client rather than a missing feature.
     */
    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    /**
     * Cascaded so a booking and its seats are written in one save.
     *
     * <p>orphanRemoval is off: nothing removes seats from a booking today, and
     * turning it on would quietly enable a delete path that has not been thought
     * through.
     */
    @OneToMany(mappedBy = "booking", cascade = CascadeType.ALL, orphanRemoval = false)
    private List<BookingSeat> seats = new ArrayList<>();

    /** Keeps both sides of the association consistent when building a booking. */
    public void addSeat(BookingSeat seat) {
        seats.add(seat);
        seat.setBooking(this);
    }
}
