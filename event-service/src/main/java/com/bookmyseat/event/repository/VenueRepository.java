package com.bookmyseat.event.repository;

import com.bookmyseat.event.entity.Venue;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface VenueRepository extends JpaRepository<Venue, Long> {

    /** Natural-key lookup used by the demo seeder for idempotency. */
    Optional<Venue> findByName(String name);
}
