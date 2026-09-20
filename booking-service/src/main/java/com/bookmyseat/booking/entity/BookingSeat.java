package com.bookmyseat.booking.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Entity
@Table(name = "booking_seats")
@Getter
@Setter
@NoArgsConstructor
public class BookingSeat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "booking_id", nullable = false)
    private Booking booking;

    /**
     * A show_seats row in event_db. Plain id: no cross-schema association.
     *
     * <p>Deliberately NOT unique. Every hold writes a row here, including holds that
     * later expire or lose, and nothing deletes them - so several rows per seat are
     * normal. What is unique is {@link #soldShowSeatId}.
     */
    @Column(name = "show_seat_id", nullable = false)
    private Long showSeatId;

    /**
     * LAYER 3 of 3 - DATABASE CONSTRAINT. DO NOT REMOVE THIS AS REDUNDANT.
     *
     * <p>Protects against a second booking being confirmed for a seat that is already
     * sold, by any code path at all.
     *
     * <p>It duplicates {@link #showSeatId} on purpose. It expresses a partial unique
     * index - {@code UNIQUE(show_seat_id) WHERE status = 'CONFIRMED'} - which MySQL
     * cannot declare directly because MySQL has no partial indexes. Instead this
     * column holds the seat id only while the booking is CONFIRMED, is NULL otherwise,
     * and carries the unique index uq_booking_seats_sold_show_seat (V2).
     * NULL-distinctness in a unique index is what lets many PENDING rows for the same
     * seat coexist while only one confirmed row per seat is possible.
     *
     * <p>Written only by {@link Booking#confirm()}, in the same transaction as the
     * status flip to CONFIRMED - never in a separate step, or there would be a window
     * in which a confirmed booking is not guarded by the index. No public setter for
     * that reason. A CHECK constraint keeps it NULL or equal to show_seat_id.
     *
     * <p><b>Mirrored by event_db.show_seats.booked_by_booking_id</b> (event-service
     * V2__seat_booking_owner.sql), which records the same sale from the other side: this
     * column says which seat a booking bought, that one says which booking a seat was sold
     * to. The two are DELIBERATELY INDEPENDENT RECORDS, not a duplication to be normalised
     * away. They are written in separate transactions in separate databases either side of
     * an HTTP call, with nothing spanning them, so there is no single record they could be
     * collapsed into. They agree when a confirm completed on both sides; they DISAGREE when
     * one side committed and the other did not, and that disagreement is the signature of
     * review finding #1 - a seat sold to a booking that does not exist, or a booking
     * holding a seat that was never marked. Detecting it requires both records.
     */
    @Setter(AccessLevel.NONE)
    @Column(name = "sold_show_seat_id")
    private Long soldShowSeatId;

    /** BigDecimal, not double: money is exact. Column is DECIMAL(10,2). */
    @Column(name = "price", nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    /** Package-private: only {@link Booking#confirm()} marks a seat sold. */
    void markSold() {
        this.soldShowSeatId = showSeatId;
    }
}
