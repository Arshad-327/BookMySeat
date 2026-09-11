package com.bookmyseat.event.service;

import com.bookmyseat.event.dto.request.BookSeatsRequest;
import com.bookmyseat.event.dto.response.SeatsBookedResponse;
import com.bookmyseat.event.entity.SeatStatus;
import com.bookmyseat.event.entity.ShowSeat;
import com.bookmyseat.event.exception.SeatsAlreadyBookedException;
import com.bookmyseat.event.exception.ShowSeatsNotFoundException;
import com.bookmyseat.event.repository.ShowSeatRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class InternalSeatService {

    private final ShowSeatRepository showSeatRepository;

    /**
     * Marks seats BOOKED - every requested seat, or none of them.
     *
     * <h2>Strict: rejected, never skipped</h2>
     * The call fails, and nothing is written, unless every requested seat exists in
     * this show and is AVAILABLE:
     * <ul>
     *   <li>an id that is unknown or belongs to another show - 404
     *       ({@link ShowSeatsNotFoundException})
     *   <li>a seat that is already BOOKED - 409 ({@link SeatsAlreadyBookedException})
     *   <li>a row that changed after it was read - 409, from the optimistic lock
     * </ul>
     * All three used to be tolerated: a BOOKED seat was skipped, and a short count was
     * logged and handed back as a number. Asking for three seats and changing two then
     * looked like success, and a booking could be confirmed for a seat it never got.
     *
     * <h2>Why managed entities, not a bulk UPDATE</h2>
     * A bulk JPQL UPDATE bypasses {@code @Version} entirely: Hibernate neither reads,
     * checks nor increments it. The baseline run proved the lock was inert that way -
     * ten bookings for one seat and {@code version} still 0. Loading the rows costs a
     * SELECT and one UPDATE per seat, and buys the version check on every one.
     */
    @Transactional
    public SeatsBookedResponse markBooked(Long showId, BookSeatsRequest request) {
        // Distinct: an id repeated in the request is one seat, not a count that could
        // never be met.
        List<Long> ids = request.showSeatIds().stream().distinct().toList();
        List<ShowSeat> seats = showSeatRepository.findByShow_IdAndIdIn(showId, ids);

        // Short count. The lookup is scoped to showId, so an id it did not return is
        // unknown or belongs to another show. Rejected before anything is mutated.
        if (seats.size() != ids.size()) {
            Set<Long> found = seats.stream().map(ShowSeat::getId).collect(Collectors.toSet());
            List<Long> missing = ids.stream().filter(id -> !found.contains(id)).toList();
            throw new ShowSeatsNotFoundException(showId, missing);
        }

        // status = AVAILABLE guard. Checked in Java against the rows as loaded, and still
        // race-free: layer 2 below writes WHERE version = <the version read here>, so a row
        // that became BOOKED after this read carries a new version and its UPDATE fails.
        // The guard gives the precise 409 for the common case; the version closes the race.
        List<Long> alreadyBooked = seats.stream()
                .filter(seat -> seat.getStatus() != SeatStatus.AVAILABLE)
                .map(ShowSeat::getId)
                .toList();
        if (!alreadyBooked.isEmpty()) {
            throw new SeatsAlreadyBookedException(showId, alreadyBooked);
        }

        // ---------------------------------------------------------------------------
        // LAYER 2 of 3 - OPTIMISTIC LOCK (@Version on show_seats)
        // Protects against: two writers selling the same seat concurrently - e.g. two
        // confirms racing after a Redis hold (layer 1) was lost - where both read the
        // seat as AVAILABLE before either wrote. Each UPDATE Hibernate emits is
        //     UPDATE show_seats SET status = 'BOOKED', version = N + 1
        //      WHERE id = ? AND version = N
        // so only the first writer matches a row. Hibernate checks the row count itself:
        // zero rows is an optimistic-lock failure, rendered as 409, and the whole call
        // rolls back. That is the SQL half of the short-count rule. Proven against real
        // MySQL by ShowSeatOptimisticLockTest.
        // ---------------------------------------------------------------------------
        for (ShowSeat seat : seats) {
            seat.setStatus(SeatStatus.BOOKED);
        }
        // Flushed here rather than at commit, so a stale version throws inside this
        // method and is attributable to this call in the log.
        showSeatRepository.flush();

        return new SeatsBookedResponse(showId, ids.size(), seats.size());
    }
}
