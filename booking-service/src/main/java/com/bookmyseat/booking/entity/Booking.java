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
     * LAYER 3 of 3 - DATABASE CONSTRAINT: UNIQUE uq_bookings_idempotency_key (V2).
     *
     * <p>Protects against one request creating two bookings: a booking carrying a key
     * that is already used is refused by the database and rendered as 409. NULLs are
     * distinct in a unique index, so bookings without a key never collide.
     *
     * <p>Nothing sets this yet - no endpoint accepts an idempotency key - so today it
     * guards the schema rather than a live code path. When one does, a replayed
     * request should get the original booking back rather than the 409.
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

    /**
     * Moves the booking to CONFIRMED and marks every one of its seats sold - as one
     * change.
     *
     * <p>The status flip and {@link BookingSeat#getSoldShowSeatId()} are set together
     * here so they can only ever be written in the same transaction, and the same
     * flush. That is the point of layer 3: the unique index on sold_show_seat_id can
     * only reject a second confirmation for a seat if every confirmed booking already
     * has the column filled in. Writing the two separately would open a window in
     * which a CONFIRMED booking is guarded by nothing.
     */
    public void confirm() {
        this.status = BookingStatus.CONFIRMED;
        // A confirmed booking does not expire. Leaving the old value would leave a
        // timestamp that reads like a deadline the booking no longer has.
        this.expiresAt = null;
        seats.forEach(BookingSeat::markSold);
    }
}
