package com.bookmyseat.event.repository;

import com.bookmyseat.event.entity.ShowSeat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
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
     * Loads specific seats of a show as MANAGED entities, for the booking write.
     *
     * <h2>Why this replaced a bulk UPDATE</h2>
     * This method used to be {@code @Modifying} JPQL:
     * {@code UPDATE ShowSeat ss SET ss.status = BOOKED WHERE ss.show.id = :showId
     * AND ss.id IN :ids}. That statement went straight to the database and never
     * loaded an entity, which meant Hibernate could not apply the {@code @Version}
     * column on {@link ShowSeat}: it neither read the version, nor added it to the
     * WHERE clause, nor incremented it. The optimistic lock existed in the schema
     * and was simply never consulted.
     *
     * <p>That was not theoretical. In the measured baseline, ten separate bookings
     * claimed the same seat and the row's {@code version} was still 0 afterwards -
     * proof that no version check had ever run. See docs/load-test-results.md.
     *
     * <p>Loading the rows instead lets {@link com.bookmyseat.event.service.InternalSeatService}
     * mutate managed entities, so Hibernate emits one
     * {@code UPDATE ... WHERE id = ? AND version = ?} per row and bumps the version.
     * A second writer holding a stale version now matches zero rows and fails
     * loudly instead of overwriting the first.
     *
     * <p>Scoped to showId as well as the id list so a caller cannot touch seats
     * belonging to a different show by guessing ids.
     *
     * <p>Cost: one SELECT plus one UPDATE per seat, where the bulk statement was a
     * single round trip. That is the price of the lock, and it is worth paying -
     * a booking writes a handful of seats, not thousands, and this is the cold
     * write path, not the seat map.
     */
    List<ShowSeat> findByShow_IdAndIdIn(Long showId, Collection<Long> ids);
}
