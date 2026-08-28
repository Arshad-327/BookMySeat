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
     * Optimistic lock counter, managed by Hibernate - never set this by hand.
     *
     * <p>This is what stops a double sale. Two transactions confirming the same
     * seat both read version N; each UPDATE carries {@code WHERE version = N},
     * so the second matches zero rows and Hibernate raises
     * OptimisticLockException instead of silently overwriting the first booking.
     *
     * <p>Boxed Long rather than long: Hibernate treats null as "never persisted",
     * which a primitive 0 default cannot express.
     */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;
}
