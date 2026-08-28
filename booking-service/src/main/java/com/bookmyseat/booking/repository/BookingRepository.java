package com.bookmyseat.booking.repository;

import com.bookmyseat.booking.entity.Booking;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BookingRepository extends JpaRepository<Booking, Long> {

    List<Booking> findByUserIdOrderByIdDesc(Long userId);

    List<Booking> findByShowId(Long showId);
}
