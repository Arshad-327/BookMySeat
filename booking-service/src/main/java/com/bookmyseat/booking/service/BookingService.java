package com.bookmyseat.booking.service;

import com.bookmyseat.booking.client.EventClient;
import com.bookmyseat.booking.client.dto.SeatResponse;
import com.bookmyseat.booking.config.SeatHoldProperties;
import com.bookmyseat.booking.dto.request.CreateBookingRequest;
import com.bookmyseat.booking.dto.response.BookingResponse;
import com.bookmyseat.booking.entity.Booking;
import com.bookmyseat.booking.entity.BookingSeat;
import com.bookmyseat.booking.entity.BookingStatus;
import com.bookmyseat.booking.exception.BookingNotFoundException;
import com.bookmyseat.booking.exception.BookingNotPendingException;
import com.bookmyseat.booking.exception.HoldExpiredException;
import com.bookmyseat.booking.exception.SeatNotAvailableException;
import com.bookmyseat.booking.exception.SeatsAlreadyHeldException;
import com.bookmyseat.booking.exception.UnknownSeatException;
import com.bookmyseat.booking.mapper.BookingMapper;
import com.bookmyseat.booking.repository.BookingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The two-step booking flow: hold, then confirm.
 *
 * <h2>What changed, and what actually fixes the race</h2>
 * The previous single-call flow read seat availability from event-service and then
 * inserted a booking, with nothing in between. Under load, 50 concurrent requests
 * for one seat all read it as AVAILABLE before any of them wrote, and ten of them
 * booked it - measured, see docs/load-test-results.md.
 *
 * <p>The fix is not a better check. It is mutual exclusion: {@link SeatHoldService}
 * takes a Redis key per seat with SET NX inside an atomic script, so exactly one
 * request can own a seat at a time and the rest are told so immediately. The read
 * from event-service still happens, but it is now only a price lookup and an early
 * filter - it decides nothing.
 *
 * <h2>Ordering, and what a failure leaves behind</h2>
 * hold() writes the PENDING booking first and takes the holds second, so a
 * conflict rolls the transaction back and leaves no orphan row. The reverse
 * ordering would leave holds owned by a booking id that no longer exists.
 *
 * <p>The one gap left is small and bounded: if the transaction fails to commit
 * after the holds are taken, those holds survive until their TTL. Nothing is
 * double-sold - the seats are simply unavailable for up to ten minutes. Closing it
 * properly needs the holds released on rollback, which is a compensating action
 * worth adding when there is a payment step to fail against.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BookingService {

    private final BookingRepository bookingRepository;
    private final EventClient eventClient;
    private final SeatHoldService seatHoldService;
    private final SeatHoldProperties seatHoldProperties;

    /** CLAUDE.md Timekeeping: every instant is read through the injected Clock. */
    private final Clock clock;

    /**
     * Step one. Creates a PENDING booking and takes the seat holds.
     *
     * <p><b>Call this through {@link IdempotentBookingService}, not directly.</b> This
     * method always attempts to create: replay detection and the recovery that turns a
     * duplicate key into the original booking both have to happen outside this
     * transaction, which is what that class is for.
     *
     * @param idempotencyKey stored on the row, where uq_bookings_idempotency_key makes
     *                       a second booking with the same key impossible
     * @throws SeatsAlreadyHeldException  409, with the exact conflicting seat ids
     * @throws org.springframework.dao.DataIntegrityViolationException
     *                                    the key is already used - caught and recovered
     *                                    by {@link IdempotentBookingService#hold}
     * @throws com.bookmyseat.booking.exception.HoldUnavailableException 503, Redis down
     */
    @Transactional
    public BookingResponse hold(Long userId, String idempotencyKey, CreateBookingRequest request) {
        Long showId = request.showId();
        List<Long> seatIds = request.seatIds().stream().distinct().toList();

        // Price lookup and sanity filter. NOT the concurrency control: this is a
        // stale snapshot the moment it arrives, and a seat that reads AVAILABLE
        // here can be held by someone else microseconds later. The hold below is
        // what decides. Kept because it gives a clean 404/400/409 for a bad show,
        // an unknown seat or an already-sold one, without burning a Redis round
        // trip, and because prices have to come from somewhere.
        Map<Long, SeatResponse> seatsById = eventClient.fetchSeatsById(showId);

        List<Long> unknown = seatIds.stream().filter(id -> !seatsById.containsKey(id)).toList();
        if (!unknown.isEmpty()) {
            throw new UnknownSeatException(showId, unknown);
        }

        List<Long> unavailable = seatIds.stream()
                .filter(id -> !seatsById.get(id).isAvailable())
                .toList();
        if (!unavailable.isEmpty()) {
            throw new SeatNotAvailableException(unavailable);
        }

        Instant now = Instant.now(clock);

        Booking booking = new Booking();
        booking.setUserId(userId);
        booking.setShowId(showId);
        booking.setStatus(BookingStatus.PENDING);
        booking.setTotalAmount(totalFor(seatIds, seatsById));

        // ---------------------------------------------------------------------------
        // LAYER 3 of 3 - DATABASE CONSTRAINT (UNIQUE bookings.idempotency_key)
        // Protects against: one request creating two bookings. Set here, on the row
        // itself, so the uniqueness is checked by MySQL as part of the insert below -
        // not by a lookup this code performs and could skip, race or get wrong.
        // The Redis fast path in IdempotencyService sits in front of this and should
        // mean it rarely fires; this is what makes a replay impossible rather than
        // unlikely when Redis has evicted the key.
        // ---------------------------------------------------------------------------
        booking.setIdempotencyKey(idempotencyKey);

        // expires_at mirrors the Redis TTL, from the injected Clock and never from
        // SQL NOW() (CLAUDE.md Timekeeping). The column is TIMESTAMP(6), so the
        // Instant round-trips at microsecond precision rather than being rounded
        // to the nearest second - which on a ten-minute hold would be a half-second
        // of drift between what Redis expires and what this row claims.
        booking.setExpiresAt(now.plus(seatHoldProperties.ttl()));

        for (Long seatId : seatIds) {
            BookingSeat seat = new BookingSeat();
            seat.setShowSeatId(seatId);
            seat.setPrice(seatsById.get(seatId).price());
            booking.addSeat(seat);
        }

        // Saved before the holds are taken because the booking id is the hold's
        // value - it is what proves ownership of a key. IDENTITY ids mean the
        // INSERT happens here, and a throw below rolls it back.
        Booking saved = bookingRepository.save(booking);

        // ---------------------------------------------------------------------------
        // LAYER 1 of 3 - REDIS SEAT HOLD (SET NX EX inside an atomic Lua script)
        // Protects against: a crowd of users contending for the same seat. Exactly one
        // request takes the hold and every other is turned away here, in one Redis
        // round trip, before it reaches confirm, event-service or a database lock.
        // It is a performance optimisation, not the guarantee: a hold can expire
        // mid-checkout or vanish with Redis. Layers 2 and 3, at confirm, are what make
        // a double sale impossible.
        // ---------------------------------------------------------------------------
        SeatHoldService.HoldResult result = seatHoldService.holdSeats(showId, seatIds, saved.getId());
        if (!result.acquired()) {
            // Rolls back the booking just inserted. Nothing is held by it either -
            // the script undid its own partial acquisition before returning.
            throw new SeatsAlreadyHeldException(result.conflictingSeatIds());
        }

        log.info("booking {} PENDING for user {} show {} seats {} - holds expire at {}",
                saved.getId(), userId, showId, seatIds, saved.getExpiresAt());

        return BookingMapper.toResponse(saved);
    }

    /**
     * Step two. Verifies the holds still belong to this booking, marks the seats
     * BOOKED and commits.
     *
     * @throws BookingNotPendingException   409, already confirmed, cancelled or expired
     * @throws HoldExpiredException         409, the hold lapsed or was taken
     * @throws org.springframework.dao.DataIntegrityViolationException
     *                                      409, a seat is already sold to another booking (layer 3)
     * @throws com.bookmyseat.booking.exception.SeatBookingRejectedException
     *                                      409, event-service refused the seat write (layer 2)
     */
    @Transactional
    public BookingResponse confirm(Long userId, Long bookingId) {
        // Locked, so a concurrent cancel waits for this to commit and then sees
        // CONFIRMED - see BookingRepository#findByIdForUpdate.
        Booking booking = lockOwnedBooking(userId, bookingId, "confirm");

        if (booking.getStatus() != BookingStatus.PENDING) {
            throw new BookingNotPendingException(bookingId, booking.getStatus(), "confirmed");
        }

        // Expiry decided in Java against the injected Clock, never by a SQL
        // comparison (CLAUDE.md Timekeeping). Checked before Redis because it is
        // free and gives a more precise error than "hold missing".
        Instant now = Instant.now(clock);
        if (booking.getExpiresAt() == null || !booking.getExpiresAt().isAfter(now)) {
            throw new HoldExpiredException(bookingId);
        }

        List<Long> seatIds = booking.getSeats().stream()
                .map(BookingSeat::getShowSeatId)
                .toList();

        List<Long> lost = seatHoldService.seatsNotHeldBy(booking.getShowId(), seatIds, bookingId);
        if (!lost.isEmpty()) {
            throw new HoldExpiredException(bookingId, lost);
        }

        // ---------------------------------------------------------------------------
        // LAYER 3 of 3 - DATABASE CONSTRAINT (UNIQUE booking_seats.sold_show_seat_id)
        // Protects against: a second booking being confirmed for a seat that is already
        // sold - whatever let it get this far. Booking.confirm() flips the status and
        // fills sold_show_seat_id as one change, so both land in this transaction and in
        // the same flush; there is no moment at which a CONFIRMED booking is unguarded.
        //
        // Flushed BEFORE event-service is called, deliberately. If another booking is
        // already confirmed for one of these seats, the UPDATE fails right here on the
        // unique index - 409 - and event_db is never touched. A concurrent confirm for
        // the same seat waits on that index entry until this transaction ends. In the
        // other order, event-service would flip the seat BOOKED for a booking that is
        // about to roll back.
        // ---------------------------------------------------------------------------
        booking.confirm();
        bookingRepository.flush();

        // Layer 2 runs inside event-service: the seats are written through managed
        // ShowSeat entities with a version check, and refused if any is already BOOKED
        // or not in the show. A refusal arrives as SeatBookingRejectedException (409)
        // and rolls back everything above - status flip and sold_show_seat_id together.
        eventClient.markSeatsBooked(booking.getShowId(), seatIds);

        // Released only once the local transaction has actually committed. Doing
        // it inline would drop the holds while this transaction could still roll
        // back, briefly freeing seats that are about to be booked after all. If
        // the release itself fails, the TTL collects the keys.
        registerHoldReleaseAfterCommit(booking.getShowId(), seatIds, bookingId);

        log.info("booking {} CONFIRMED for user {} show {} seats {}",
                bookingId, userId, booking.getShowId(), seatIds);

        return BookingMapper.toResponse(booking);
    }

    /**
     * The user abandons a PENDING booking. Marks it CANCELLED and frees its seats at
     * once, rather than leaving them locked until the hold's TTL runs out - a cancelled
     * checkout that keeps its seats for ten minutes looks like a bug to anyone watching.
     *
     * @throws BookingNotFoundException   404, no such booking or not the caller's
     * @throws BookingNotPendingException 409, already confirmed, cancelled or expired
     */
    @Transactional
    public BookingResponse cancel(Long userId, Long bookingId) {
        Booking booking = lockOwnedBooking(userId, bookingId, "cancel");

        if (booking.getStatus() != BookingStatus.PENDING) {
            throw new BookingNotPendingException(bookingId, booking.getStatus(), "cancelled");
        }

        // Deliberately NOT checked against expires_at. A PENDING booking whose hold has
        // lapsed, but which the sweeper has not reached yet, is cancelled like any other.
        // Refusing it would make the answer depend on where the 60-second sweep cycle
        // happens to be: the same request from the same user would get 200 one second
        // and 409 the next, decided by a cycle the caller cannot see. One consistent
        // answer beats that, whichever it is. It is also safe: the release below is
        // value-matched, so if the TTL already fired and another booking has since taken
        // the seat, this cancel cannot take it back.
        booking.setStatus(BookingStatus.CANCELLED);

        // The idempotency key stays on the row, and idem:{key} stays in Redis. That is
        // NOT an oversight, and must not be "tidied up". The key names the attempt that
        // created this booking, and that attempt's outcome is now this cancelled booking.
        // A replay of the same key has to return it. Clearing the key would let the
        // replay create a brand new booking, so a client retrying a hold it had already
        // cancelled would find its seats taken again.

        List<Long> seatIds = booking.getSeats().stream()
                .map(BookingSeat::getShowSeatId)
                .toList();

        // After commit, like confirm: freeing the seats inside a transaction that could
        // still roll back would release holds for a booking that stays PENDING. The
        // callback runs as part of the commit, before this returns to the controller, so
        // the seats are free by the time the caller gets its response.
        registerHoldReleaseAfterCommit(booking.getShowId(), seatIds, bookingId);

        log.info("booking {} CANCELLED by user {} - releasing holds on show {} seats {}",
                bookingId, userId, booking.getShowId(), seatIds);

        return BookingMapper.toResponse(booking);
    }

    /**
     * Marks one booking EXPIRED if it is still PENDING and its expiry is at or before
     * {@code now}, and releases whatever holds it still has. For
     * {@link com.bookmyseat.booking.scheduler.ExpiredBookingSweeper}, one transaction per
     * booking.
     *
     * <p>The re-check under the lock is the point. The sweeper chose this id from an
     * unlocked query, and the booking may have been confirmed or cancelled since. In
     * that case this does nothing.
     *
     * @param now from the sweeper's injected Clock, read once per sweep
     * @return true if this call expired the booking
     */
    @Transactional
    public boolean expireIfPending(Long bookingId, Instant now) {
        Optional<Booking> locked = bookingRepository.findByIdForUpdate(bookingId);
        if (locked.isEmpty()) {
            return false;
        }
        Booking booking = locked.get();

        if (booking.getStatus() != BookingStatus.PENDING
                || booking.getExpiresAt() == null
                || booking.getExpiresAt().isAfter(now)) {
            log.debug("booking {} is {} with expiry {} - no longer a sweep candidate",
                    bookingId, booking.getStatus(), booking.getExpiresAt());
            return false;
        }

        // expires_at is left as it is: it records when the hold lapsed.
        booking.setStatus(BookingStatus.EXPIRED);

        List<Long> seatIds = booking.getSeats().stream()
                .map(BookingSeat::getShowSeatId)
                .toList();

        // Almost always a no-op: the TTL on these keys matches expires_at, so Redis has
        // already deleted them. Run anyway to collect any that lingered, and safe
        // because it is value-matched - a seat another booking has held since is not
        // touched.
        registerHoldReleaseAfterCommit(booking.getShowId(), seatIds, bookingId);

        log.info("booking {} EXPIRED (expiry {}, swept at {})", bookingId, booking.getExpiresAt(), now);
        return true;
    }

    /**
     * The booking, row-locked, provided it belongs to this user.
     *
     * <p>Not yours means not found, rather than 403: a 403 would confirm that a booking
     * with this id exists and belongs to somebody, which is more than a caller who does
     * not own it needs to know.
     *
     * @param verb for the log line only
     */
    private Booking lockOwnedBooking(Long userId, Long bookingId, String verb) {
        Booking booking = bookingRepository.findByIdForUpdate(bookingId)
                .orElseThrow(() -> new BookingNotFoundException(bookingId));

        if (!booking.getUserId().equals(userId)) {
            log.warn("user {} tried to {} booking {} owned by user {}",
                    userId, verb, bookingId, booking.getUserId());
            throw new BookingNotFoundException(bookingId);
        }
        return booking;
    }

    private void registerHoldReleaseAfterCommit(Long showId, List<Long> seatIds, Long bookingId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            // No transaction to hang the callback off - release immediately rather
            // than skip it, so holds are never leaked by a configuration change.
            seatHoldService.releaseSeats(showId, seatIds, bookingId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                seatHoldService.releaseSeats(showId, seatIds, bookingId);
            }
        });
    }

    @Transactional(readOnly = true)
    public BookingResponse findById(Long id) {
        Booking booking = bookingRepository.findById(id)
                .orElseThrow(() -> new BookingNotFoundException(id));
        return BookingMapper.toResponse(booking);
    }

    /**
     * Like {@link #findById} but empty rather than throwing when the booking is gone.
     *
     * <p>For the idempotency fast path, where a Redis entry can outlive the row it
     * names - the key is written with a 24-hour TTL and nothing deletes it if the
     * booking is later removed. A missing row there means "the cache is stale", which
     * is a fall-through, not a 404 for the caller.
     */
    @Transactional(readOnly = true)
    public Optional<BookingResponse> findByIdOptional(Long id) {
        return bookingRepository.findById(id).map(BookingMapper::toResponse);
    }

    /**
     * The booking a given Idempotency-Key created, if any.
     *
     * <p>Read in a transaction of its own, which matters on the recovery path: it runs
     * after a failed insert has rolled back, and is what finds the booking that the
     * concurrent request committed. See {@link IdempotentBookingService}.
     */
    @Transactional(readOnly = true)
    public Optional<BookingResponse> findByIdempotencyKey(String idempotencyKey) {
        return bookingRepository.findByIdempotencyKey(idempotencyKey).map(BookingMapper::toResponse);
    }

    @Transactional(readOnly = true)
    public List<BookingResponse> findByUser(Long userId) {
        List<BookingResponse> responses = new ArrayList<>();
        for (Booking booking : bookingRepository.findByUserIdOrderByIdDesc(userId)) {
            responses.add(BookingMapper.toResponse(booking));
        }
        return responses;
    }

    /** Sum of the per-seat prices event-service reported. */
    private static BigDecimal totalFor(List<Long> seatIds, Map<Long, SeatResponse> seatsById) {
        return seatIds.stream()
                .map(id -> seatsById.get(id).price())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
