package com.bookmyseat.booking.repository;

import com.bookmyseat.booking.entity.BookingSeat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface BookingSeatRepository extends JpaRepository<BookingSeat, Long> {

    /**
     * Every booking_seats row for a given show seat.
     *
     * <p>A List because several rows per seat are normal: every hold writes one,
     * including holds that expired or lost. At most one of them can have
     * sold_show_seat_id set - that is layer 3, a unique index added in V2.
     */
    List<BookingSeat> findByShowSeatId(Long showSeatId);

    /**
     * Every seat of the given bookings, in one statement - the third and last of the
     * bookings list, after the page and its count.
     *
     * <p>Why not let each booking load its own: {@code Booking.seats} is LAZY, so mapping a
     * page of 20 by touching it is 20 more SELECTs, and a collection cannot be JOIN FETCHed
     * into a paged query - Hibernate would page in memory after reading every row.
     *
     * <p>THE ORDER IS PART OF THE CONTRACT. {@code booking_id, id} - id within a booking is
     * venue order, because hold inserts seats in seat-map order, and it is the same order
     * {@code @OrderBy("id")} gives every single-booking endpoint. Without an ORDER BY the
     * seats of a booking could come back in a different order on the next request, which
     * nobody notices until a demo.
     */
    @Query("""
            SELECT s FROM BookingSeat s
            WHERE s.booking.id IN :bookingIds
            ORDER BY s.booking.id, s.id
            """)
    List<BookingSeat> findByBookingIdsInSeatOrder(@Param("bookingIds") Collection<Long> bookingIds);
}
