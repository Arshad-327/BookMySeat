package com.bookmyseat.booking.repository;

import com.bookmyseat.booking.entity.Booking;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BookingRepository extends JpaRepository<Booking, Long> {

    List<Booking> findByUserIdOrderByIdDesc(Long userId);

    List<Booking> findByShowId(Long showId);

    /**
     * The booking a given Idempotency-Key created, if any.
     *
     * <p>Returns at most one row, and that is enforced by the database rather than
     * assumed here: uq_bookings_idempotency_key (V2) makes a second booking with the
     * same key impossible to insert. The column is nullable and NULLs are distinct in a
     * unique index, so the many bookings created without a key never collide.
     */
    Optional<Booking> findByIdempotencyKey(String idempotencyKey);
}
