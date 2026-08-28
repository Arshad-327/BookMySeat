package com.bookmyseat.event.repository;

import com.bookmyseat.event.entity.ShowSeat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ShowSeatRepository extends JpaRepository<ShowSeat, Long> {

    /**
     * The seat map for one show, in exactly one SQL statement.
     *
     * <p>This is the highest-traffic query in the system, so the shape matters:
     *
     * <ul>
     *   <li>JOIN FETCH ss.seat pulls Seat into the same SELECT. A plain join would
     *       still leave seat as a lazy proxy and the mapper would then fire one
     *       SELECT per seat - the N+1 this method exists to prevent.
     *   <li>ss.show.id reads the show_id FK column directly, so no join to shows
     *       is emitted and Show is never loaded at all.
     *   <li>ss.seat.venue is left lazy and never touched by the mapper. Touching it
     *       would reintroduce N+1.
     *   <li>Ordering happens in SQL, so the mapper can group by row in one pass
     *       over an already-sorted list.
     * </ul>
     *
     * <p>The WHERE is served by uq_show_seats_show_seat, whose leftmost column is
     * show_id.
     */
    @Query("""
            SELECT ss FROM ShowSeat ss
            JOIN FETCH ss.seat s
            WHERE ss.show.id = :showId
            ORDER BY s.rowLabel ASC, s.seatNumber ASC
            """)
    List<ShowSeat> findSeatMapByShowId(@Param("showId") Long showId);

    /**
     * Blind bulk UPDATE of seat status to BOOKED. DELIBERATELY UNSAFE - see below.
     *
     * <p><b>This method has no concurrency protection of any kind, on purpose.</b>
     * It is the write half of a race that is about to be measured under load, and it
     * is written this way so the failure is real rather than simulated:
     *
     * <ul>
     *   <li><b>No version check.</b> A JPQL bulk update bypasses the @Version column
     *       on ShowSeat entirely - Hibernate neither reads nor increments it, and no
     *       OptimisticLockException can be raised. The optimistic lock that exists in
     *       the schema is simply not consulted.
     *   <li><b>No availability re-check.</b> The WHERE clause does not test
     *       {@code status = 'AVAILABLE'}. A seat already BOOKED by someone else is
     *       overwritten silently, so two callers can both "succeed" on the same seat.
     *   <li><b>Stale entities.</b> A bulk update does not touch the persistence
     *       context, so any ShowSeat already loaded in this transaction keeps its old
     *       status.
     * </ul>
     *
     * <p>Adding {@code AND ss.status = 'AVAILABLE'} here, plus a version check, is
     * roughly what closes the race. That is a later step; leaving it out now is what
     * makes the before/after measurement meaningful.
     *
     * <p>Scoped to showId as well as the id list so a caller cannot book seats
     * belonging to a different show by guessing ids.
     *
     * @return the number of rows actually changed
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE ShowSeat ss
            SET ss.status = com.bookmyseat.event.entity.SeatStatus.BOOKED
            WHERE ss.show.id = :showId
              AND ss.id IN :showSeatIds
            """)
    int markBooked(@Param("showId") Long showId, @Param("showSeatIds") List<Long> showSeatIds);
}
