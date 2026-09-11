package com.bookmyseat.booking.repository;

import com.bookmyseat.booking.entity.BookingSeat;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
