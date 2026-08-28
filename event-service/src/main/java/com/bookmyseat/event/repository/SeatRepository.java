package com.bookmyseat.event.repository;

import com.bookmyseat.event.entity.Seat;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SeatRepository extends JpaRepository<Seat, Long> {

    /**
     * Guard for seat generation. Cheaper than counting: MySQL stops at the first
     * matching row rather than scanning the whole venue.
     */
    boolean existsByVenueId(Long venueId);

    /** Used by the demo seeder to tell a complete seed from a partial one. */
    long countByVenueId(Long venueId);

    /**
     * Every seat in a venue, used to fan a new show out into show_seats.
     *
     * <p>Reads seat.venue.id, which uses the venue_id FK column directly and emits
     * no join to venues.
     */
    List<Seat> findByVenueId(Long venueId);
}
