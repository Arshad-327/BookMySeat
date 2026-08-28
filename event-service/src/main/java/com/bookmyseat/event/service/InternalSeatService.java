package com.bookmyseat.event.service;

import com.bookmyseat.event.dto.request.BookSeatsRequest;
import com.bookmyseat.event.dto.response.SeatsBookedResponse;
import com.bookmyseat.event.entity.SeatStatus;
import com.bookmyseat.event.entity.ShowSeat;
import com.bookmyseat.event.repository.ShowSeatRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class InternalSeatService {

    private final ShowSeatRepository showSeatRepository;

    /**
     * Marks seats BOOKED through managed entities, so the optimistic lock engages.
     *
     * <h2>The important part is what this is NOT</h2>
     * It is not a bulk JPQL UPDATE any more. A bulk update bypasses {@code @Version}
     * entirely - Hibernate does not read it, does not add it to the WHERE clause and
     * does not increment it - so the optimistic lock on {@link ShowSeat} was inert.
     * The baseline run proved it: ten bookings for one seat, and {@code version}
     * still 0 afterwards.
     *
     * <p>Loading the rows first costs a SELECT and turns one statement into one per
     * seat. In exchange, each UPDATE Hibernate emits carries
     * {@code WHERE id = ? AND version = ?} and bumps the version. Two transactions
     * that read the same version can no longer both succeed: the second matches zero
     * rows, Hibernate raises an optimistic-lock failure, and the transaction rolls
     * back rather than silently overwriting the first booking.
     *
     * <h2>What is still deliberately missing - P3.5 owes this</h2>
     * There is no {@code status = AVAILABLE} guard here, and a short count is still
     * only logged rather than rejected. So a seat that is already BOOKED is skipped
     * quietly, and asking for three seats and changing two is reported as a count,
     * not an error. Shaping the write path and proving the lock actually engages are
     * different jobs; this change is the first, and P3.5 still owes a test in which
     * a stale version causes a rejection.
     */
    @Transactional
    public SeatsBookedResponse markBooked(Long showId, BookSeatsRequest request) {
        List<Long> ids = request.showSeatIds();
        List<ShowSeat> seats = showSeatRepository.findByShow_IdAndIdIn(showId, ids);

        int updated = 0;
        for (ShowSeat seat : seats) {
            // Already BOOKED means nothing to change - Hibernate's dirty check would
            // skip it anyway, and counting it would overstate what this call did.
            // Note this is NOT a rejection: see the class note, that is P3.5's job.
            if (seat.getStatus() == SeatStatus.BOOKED) {
                continue;
            }
            // Mutating a managed entity, not issuing a statement. The UPDATE is
            // emitted at flush, with the version predicate attached.
            seat.setStatus(SeatStatus.BOOKED);
            updated++;
        }

        // Flush inside the method rather than leaving it to commit, so an optimistic
        // lock failure is thrown here and mapped to 409 by GlobalExceptionHandler.
        // Left to commit time it would surface from the transaction proxy, which is
        // harder to attribute to this call in a log.
        showSeatRepository.flush();

        if (updated != ids.size()) {
            log.warn("show {}: asked to book {} seats, changed {} rows - ids {} "
                            + "(unknown, wrong show, or already booked; NOT rejected)",
                    showId, ids.size(), updated, ids);
        }
        return new SeatsBookedResponse(showId, ids.size(), updated);
    }
}
