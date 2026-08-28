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
     * @throws SeatsAlreadyHeldException  409, with the exact conflicting seat ids
     * @throws com.bookmyseat.booking.exception.HoldUnavailableException 503, Redis down
     */
    @Transactional
    public BookingResponse hold(Long userId, CreateBookingRequest request) {
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
     * @throws BookingNotPendingException 409, already confirmed or cancelled
     * @throws HoldExpiredException       409, the hold lapsed or was taken
     */
    @Transactional
    public BookingResponse confirm(Long userId, Long bookingId) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new BookingNotFoundException(bookingId));

        // Not yours means not found, rather than 403: a 403 would confirm that a
        // booking with this id exists and belongs to somebody, which is more than
        // a caller who does not own it needs to know.
        if (!booking.getUserId().equals(userId)) {
            log.warn("user {} tried to confirm booking {} owned by user {}",
                    userId, bookingId, booking.getUserId());
            throw new BookingNotFoundException(bookingId);
        }

        if (booking.getStatus() != BookingStatus.PENDING) {
            throw new BookingNotPendingException(bookingId, booking.getStatus());
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

        // Marks the seats BOOKED in event-service. That call now writes through
        // managed ShowSeat entities rather than a bulk JPQL update, so each row's
        // @Version is read and incremented and a stale write is rejected instead
        // of silently overwriting. A conflict there surfaces as 409 from
        // event-service and aborts this transaction.
        eventClient.markSeatsBooked(booking.getShowId(), seatIds);

        booking.setStatus(BookingStatus.CONFIRMED);
        // A confirmed booking does not expire. Leaving the old value would leave a
        // timestamp that reads like a deadline the booking no longer has.
        booking.setExpiresAt(null);

        // Released only once the local transaction has actually committed. Doing
        // it inline would drop the holds while this transaction could still roll
        // back, briefly freeing seats that are about to be booked after all. If
        // the release itself fails, the TTL collects the keys.
        registerHoldReleaseAfterCommit(booking.getShowId(), seatIds, bookingId);

        log.info("booking {} CONFIRMED for user {} show {} seats {}",
                bookingId, userId, booking.getShowId(), seatIds);

        return BookingMapper.toResponse(booking);
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
