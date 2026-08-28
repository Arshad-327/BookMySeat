package com.bookmyseat.event.repository;

import com.bookmyseat.event.entity.Event;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface EventRepository
        extends JpaRepository<Event, Long>, JpaSpecificationExecutor<Event> {

    /**
     * Event plus venue in one statement. Without the JOIN FETCH, venue is a lazy
     * proxy and reading it in the mapper would fire a second SELECT.
     */
    @Query("""
            SELECT e FROM Event e
            JOIN FETCH e.venue
            WHERE e.id = :id
            """)
    Optional<Event> findByIdWithVenue(@Param("id") Long id);

    /** Natural-key lookup used by the demo seeder for idempotency. */
    Optional<Event> findByTitle(String title);
}
