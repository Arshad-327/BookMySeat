package com.bookmyseat.event.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * One seat, for one show. The row that decides whether a seat is sold.
 *
 * <p>{@link #status} is only ever AVAILABLE or BOOKED. A ten-minute hold is a
 * Redis key with a TTL and never touches this table; see {@link SeatStatus}.
 */
@Entity
@Table(
        name = "show_seats",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_show_seats_show_seat",
                columnNames = {"show_id", "seat_id"}
        )
)
@Getter
@Setter
@NoArgsConstructor
public class ShowSeat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "show_id", nullable = false)
    private Show show;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "seat_id", nullable = false)
    private Seat seat;

    /** BigDecimal, not double: money is exact. Column is DECIMAL(10,2). */
    @Column(name = "price", nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    /** Stored as the enum NAME, not its ordinal, so the column stays readable and reorder-proof. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private SeatStatus status = SeatStatus.AVAILABLE;

    /**
     * The booking this seat was marked BOOKED for. NULL while AVAILABLE.
     *
     * <p>WRITTEN, AND NOT YET READ. Nothing decides anything from this value: a BOOKED
     * seat is refused whoever owns it, exactly as before the column existed. It is here
     * so that the record exists before the read that needs it, and so the behaviour
     * change lands in a commit of its own.
     *
     * <p>Plain Long, no association: it identifies a row in booking_db.bookings, which
     * is another service's schema. There is no foreign key and there cannot be one.
     *
     * <p>Mirror of booking_db.booking_seats.sold_show_seat_id, deliberately independent
     * of it - the two are written in separate transactions on either side of an HTTP
     * call, and their DISAGREEMENT is the signature of review finding #1: a seat this
     * service committed as sold after booking-service rolled its confirm back. See
     * V2__seat_booking_owner.sql.
     */
    @Column(name = "booked_by_booking_id")
    private Long bookedByBookingId;

    /**
     * LAYER 2 of 3 - OPTIMISTIC LOCK. Managed by Hibernate - never set this by hand.
     *
     * <p>Protects against two transactions selling the same seat concurrently. Both
     * read version N; each UPDATE carries {@code WHERE version = N}, so the second
     * matches zero rows and Hibernate raises an optimistic-lock failure instead of
     * silently overwriting the first booking. It engages only for writes through
     * managed entities - a bulk JPQL UPDATE bypasses it entirely.
     * ShowSeatOptimisticLockTest proves a stale version is rejected, against real MySQL.
     *
     * <p>Boxed Long rather than long: Hibernate treats null as "never persisted",
     * which a primitive 0 default cannot express.
     */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;
}
