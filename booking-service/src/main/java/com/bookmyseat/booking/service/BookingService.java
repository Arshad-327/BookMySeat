package com.bookmyseat.booking.service;

import com.bookmyseat.booking.client.EventClient;
import com.bookmyseat.booking.client.dto.SeatResponse;
import com.bookmyseat.booking.dto.request.CreateBookingRequest;
import com.bookmyseat.booking.dto.response.BookingResponse;
import com.bookmyseat.booking.entity.Booking;
import com.bookmyseat.booking.entity.BookingSeat;
import com.bookmyseat.booking.entity.BookingStatus;
import com.bookmyseat.booking.exception.BookingNotFoundException;
import com.bookmyseat.booking.exception.SeatNotAvailableException;
import com.bookmyseat.booking.exception.UnknownSeatException;
import com.bookmyseat.booking.mapper.BookingMapper;
import com.bookmyseat.booking.repository.BookingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ============================================================================
 * DELIBERATELY UNSAFE. THIS IMPLEMENTATION HAS A KNOWN RACE CONDITION.
 * ============================================================================
 *
 * <p>Written this way on purpose so the failure can be reproduced under load and
 * measured, before and after it is fixed. Nothing here should be copied into a
 * service that takes real money.
 *
 * <h2>The race, precisely</h2>
 * {@link #createBooking} reads seat availability from event-service, then writes a
 * booking, then tells event-service to mark the seats BOOKED. Nothing holds the
 * seats between the read and the write, and nothing verifies at write time that
 * they are still free. Two requests for the same seat interleave like this:
 *
 * <pre>
 *   T1: GET seat 5 -> AVAILABLE
 *   T2: GET seat 5 -> AVAILABLE        both reads pass
 *   T1: INSERT booking A + seat 5      no constraint stops it
 *   T2: INSERT booking B + seat 5      no constraint stops it either
 *   T1: POST mark 5 BOOKED             blind update, succeeds
 *   T2: POST mark 5 BOOKED             blind update, succeeds again
 * </pre>
 *
 * Both callers receive 201 CONFIRMED. The seat is sold twice. Neither service logs
 * an error, because from each one's point of view nothing went wrong.
 *
 * <h2>Every protection that is missing</h2>
 * <ul>
 *   <li>No Redis hold on the seats between read and write.
 *   <li>No database lock - no SELECT ... FOR UPDATE, no pessimistic read.
 *   <li>No optimistic locking: event-service's blind UPDATE bypasses the @Version
 *       column that exists on ShowSeat.
 *   <li>No unique constraint on booking_seats.show_seat_id, so the database will
 *       not catch what the application misses.
 *   <li>No re-check of availability inside the transaction.
 *   <li>No idempotency: idempotency_key is stored but never read, so a retry
 *       creates a second booking.
 *   <li>No transactional boundary across the two services. The local commit
 *       happens first; if the event-service call then fails, the booking stays
 *       CONFIRMED with seats never marked, and nothing compensates.
 * </ul>
 *
 * <h2>What is NOT the fix</h2>
 * Narrowing the window - reordering the calls, retrying, checking twice - only
 * makes the race rarer and harder to reproduce. The window cannot be closed by
 * being quick; it needs an actual mutual-exclusion mechanism.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BookingService {

    private final BookingRepository bookingRepository;
    private final EventClient eventClient;

    /**
     * The four-step flow, exactly as specified.
     *
     * <p>@Transactional covers the local database work only. It does not and cannot
     * cover the event-service calls: they are HTTP, outside any transaction, and the
     * final one deliberately happens after this method's commit boundary logic has
     * already decided the booking is CONFIRMED.
     */
    @Transactional
    public BookingResponse createBooking(Long userId, CreateBookingRequest request) {
        Long showId = request.showId();
        List<Long> seatIds = request.seatIds().stream().distinct().toList();

        // ---- (a) read the status of each requested seat from event-service ----
        // This is a snapshot over HTTP. It is stale the instant it arrives, and
        // nothing reserves these seats on the strength of it.
        Map<Long, SeatResponse> seatsById = eventClient.fetchSeatsById(showId);

        List<Long> unknown = seatIds.stream().filter(id -> !seatsById.containsKey(id)).toList();
        if (!unknown.isEmpty()) {
            throw new UnknownSeatException(showId, unknown);
        }

        // ---- (b) if any is not AVAILABLE, 409 ----
        List<Long> unavailable = seatIds.stream()
                .filter(id -> !seatsById.get(id).isAvailable())
                .toList();
        if (!unavailable.isEmpty()) {
            throw new SeatNotAvailableException(unavailable);
        }

        // <<< THE RACE WINDOW OPENS HERE >>>
        // Between the check above and the insert below, another request can read the
        // same seats as AVAILABLE and book them. Nothing here prevents that: no lock
        // is held, no hold is written, and the check is never repeated. Under load
        // this window is wide enough to lose reliably.

        // ---- (c) create the booking with status CONFIRMED ----
        // Straight to CONFIRMED, no pending state and no payment step, so there is
        // no point at which the booking could be abandoned and its seats released.
        Booking booking = new Booking();
        booking.setUserId(userId);
        booking.setShowId(showId);
        booking.setStatus(BookingStatus.CONFIRMED);
        booking.setTotalAmount(totalFor(seatIds, seatsById));

        // expiresAt stays null and idempotencyKey stays null: the columns exist, but
        // nothing populates or reads them yet.

        for (Long seatId : seatIds) {
            BookingSeat seat = new BookingSeat();
            seat.setShowSeatId(seatId);
            seat.setPrice(seatsById.get(seatId).price());
            booking.addSeat(seat);
        }

        Booking saved = bookingRepository.save(booking);

        // ---- (d) tell event-service to mark those show_seats BOOKED ----
        // Fire-and-assume-success. The call is a blind UPDATE on the far side, and
        // the response is not checked for a short count. If it throws, the exception
        // propagates and rolls back the local booking - but any seats the remote
        // update already changed stay BOOKED, with no compensation.
        eventClient.markSeatsBooked(showId, seatIds);

        log.info("booking {} CONFIRMED for user {} show {} seats {} (UNSAFE PATH)",
                saved.getId(), userId, showId, seatIds);

        return BookingMapper.toResponse(saved);
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
