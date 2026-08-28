package com.bookmyseat.booking.repository;

import com.bookmyseat.booking.entity.BookingSeat;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BookingSeatRepository extends JpaRepository<BookingSeat, Long> {

    /**
     * Every booking_seats row for a given show seat.
     *
     * <p>Should only ever return one. Returns a List rather than an Optional because
     * with no unique constraint it can and will return several under load - which is
     * precisely the evidence the load test is looking for.
     */
    List<BookingSeat> findByShowSeatId(Long showSeatId);
}
