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
     * A show_seats row in event_db. Plain id: no cross-schema association, and
     * DELIBERATELY not unique - two bookings may hold the same value, which is the
     * double-sale this build exists to demonstrate.
     */
    @Column(name = "show_seat_id", nullable = false)
    private Long showSeatId;

    /** BigDecimal, not double: money is exact. Column is DECIMAL(10,2). */
    @Column(name = "price", nullable = false, precision = 10, scale = 2)
    private BigDecimal price;
}
