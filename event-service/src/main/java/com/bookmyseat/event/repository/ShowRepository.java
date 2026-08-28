package com.bookmyseat.event.repository;

import com.bookmyseat.event.entity.Show;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface ShowRepository extends JpaRepository<Show, Long> {

    /**
     * Upcoming shows for one event, earliest first.
     *
     * <p>:now is supplied by the caller as Instant.now(clock). CLAUDE.md forbids
     * SQL NOW()/CURRENT_TIMESTAMP in comparison logic precisely so this boundary
     * can be pinned in a test.
     *
     * <p>Uses show.event.id, which reads the event_id FK column directly - no join
     * to events is emitted.
     */
    @Query("""
            SELECT s FROM Show s
            WHERE s.event.id = :eventId
              AND s.startsAt >= :now
            ORDER BY s.startsAt ASC
            """)
    List<Show> findUpcomingByEventId(@Param("eventId") Long eventId, @Param("now") Instant now);

    /**
     * Whether an event already has any show at all.
     *
     * <p>The demo seeder's idempotency key. Deliberately coarse: matching on exact
     * (event, startsAt) would let a second run on a later day create a fresh set,
     * because the seeded instants are relative to the clock. "Does this event have
     * shows yet" is stable regardless of when the seeder runs again.
     */
    boolean existsByEventId(Long eventId);
}
