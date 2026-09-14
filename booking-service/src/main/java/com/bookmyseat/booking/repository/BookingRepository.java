package com.bookmyseat.booking.repository;

import com.bookmyseat.booking.entity.Booking;
import com.bookmyseat.booking.entity.BookingStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
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

    /**
     * The booking, with its row locked ({@code SELECT ... FOR UPDATE}) until the calling
     * transaction ends. Every status transition out of PENDING - confirm, cancel and the
     * expiry sweep - reads through this, and nothing else may.
     *
     * <h2>Why it exists</h2>
     * Without it, confirm and cancel can both read PENDING and both write. Whichever
     * commits last wins, and one interleaving leaves the booking CANCELLED while
     * event-service has already marked its seats BOOKED - worse than either outcome on
     * its own. With the row locked, the second transition waits for the first to commit
     * and then reads the status that transition actually wrote.
     *
     * <h2>"But this project says never hold a database lock across a slow operation"</h2>
     * It does, and confirm now holds this lock across an HTTP call to event-service. That
     * looks like exactly the thing the seat hold was built to avoid. It is not, and the
     * difference is who contends for the lock and for how long:
     *
     * <ul>
     *   <li><b>The seat hold</b> is contended by MANY users for a HUMAN-scale duration -
     *       up to ten minutes of someone deciding whether to pay. A database lock there
     *       would pin one pooled connection per shopper for the whole of that time and
     *       exhaust the pool. That is why the seat hold is a Redis key, not
     *       {@code SELECT ... FOR UPDATE}.</li>
     *   <li><b>This booking row</b> is contended only by ONE user's own concurrent
     *       requests - a double-clicked confirm, a cancel sent while a confirm is in
     *       flight - for the duration of a single local service call, bounded by
     *       event-service's read timeout. Nobody else can reach it: cancel and confirm are
     *       both authorised against the booking's owner, and the sweeper only touches
     *       bookings whose hold has already lapsed.</li>
     * </ul>
     *
     * <p>A lock is not wrong in itself. A lock held across human think time by many
     * contenders is. This one is held across machine time by one.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM Booking b WHERE b.id = :id")
    Optional<Booking> findByIdForUpdate(@Param("id") Long id);

    /**
     * Ids of PENDING bookings whose expiry is at or before {@code now}.
     *
     * <p>{@code now} is a parameter, supplied from the injected Clock - never SQL
     * {@code NOW()} (CLAUDE.md Timekeeping). At-or-before matches confirm, which treats a
     * booking as expired once {@code expiresAt} is no longer after the current instant.
     *
     * <p>Ids only, unlocked: this is the candidate list. Each candidate is then re-read
     * through {@link #findByIdForUpdate} and re-checked, because it may have been
     * confirmed or cancelled between this query and that lock.
     */
    @Query("SELECT b.id FROM Booking b WHERE b.status = :status AND b.expiresAt <= :now ORDER BY b.id")
    List<Long> findIdsByStatusExpiredAt(@Param("status") BookingStatus status, @Param("now") Instant now);
}
