package com.bookmyseat.event.repository;

import com.bookmyseat.event.entity.Show;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

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
     * The next start time and the lowest base price of each event's upcoming shows, for the
     * events on one page of the list. One row per event that has an upcoming show.
     *
     * <p>ONE statement for the whole page, bounded by the page size (capped at 100 by
     * spring.data.web.pageable.max-page-size) - not one per card. It is the third statement
     * of GET /api/events, after the page and its count, and like them it does not grow with
     * anything but the page.
     *
     * <p>Both aggregates come from {@code shows} alone. The price deliberately does NOT cross
     * into show_seats: that would turn a lookup on the event_id index into an aggregate over
     * every seat of every upcoming show on the page.
     *
     * <p>{@code :now} must be the same instant the list's EXISTS filter used - see
     * {@code EventService.findEvents}, which reads it once and passes it to both.
     */
    @Query("""
            SELECT new com.bookmyseat.event.repository.UpcomingShowSummary(
                       s.event.id, MIN(s.startsAt), MIN(s.basePrice))
            FROM Show s
            WHERE s.event.id IN :eventIds
              AND s.startsAt >= :now
            GROUP BY s.event.id
            """)
    List<UpcomingShowSummary> findUpcomingSummariesByEventIds(
            @Param("eventIds") Collection<Long> eventIds, @Param("now") Instant now);

    /**
     * One show with its event and venue already loaded.
     *
     * <p>TWO CALLERS, and the second is the public seat map. {@link com.bookmyseat.event.service.InternalShowService}
     * projects it for a confirmation message; {@link com.bookmyseat.event.service.ShowService}
     * uses it for the seat map's header, which needs exactly the same three facts. Shared
     * rather than copied: a second query answering "this show, its event and its venue" would
     * be the same statement under another name.
     *
     * <p>{@code findById} would return the show alone: Show.event and Event.venue are both
     * LAZY, so reading the title and the venue name off it fires two more SELECTs - and with
     * open-in-view off, fires them only if a transaction is still open, and a
     * LazyInitializationException otherwise. Fetching all three here makes the read one
     * statement and independent of where the mapping happens.
     *
     * <p>JOIN FETCH and not an EntityGraph: the joins are the query's subject, so they belong
     * where the query is read.
     */
    @Query("""
            SELECT s FROM Show s
            JOIN FETCH s.event e
            JOIN FETCH e.venue
            WHERE s.id = :showId
            """)
    Optional<Show> findWithEventAndVenueById(@Param("showId") Long showId);

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
