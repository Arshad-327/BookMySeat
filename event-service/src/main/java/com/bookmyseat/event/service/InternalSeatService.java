package com.bookmyseat.event.service;

import com.bookmyseat.event.dto.request.BookSeatsRequest;
import com.bookmyseat.event.dto.response.SeatsBookedResponse;
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
     * DELIBERATELY UNSAFE. Marks seats BOOKED with no guard whatsoever.
     *
     * <p>Note what is missing and would normally be here: no check that the seats are
     * currently AVAILABLE, no optimistic lock, no rejection when fewer rows are
     * updated than were asked for. A partial update - some ids unknown, or already
     * booked - is reported as a count and nothing else, so the caller cannot tell the
     * difference between "booked 2 seats" and "overwrote someone else's 2 seats".
     *
     * <p>Kept this way on purpose while the race is being measured. Logged at WARN
     * when the counts disagree so the load test leaves evidence in the log even
     * though nothing fails.
     */
    @Transactional
    public SeatsBookedResponse markBooked(Long showId, BookSeatsRequest request) {
        List<Long> ids = request.showSeatIds();
        int updated = showSeatRepository.markBooked(showId, ids);

        if (updated != ids.size()) {
            log.warn("show {}: asked to book {} seats, updated {} rows - ids {} "
                            + "(unknown, wrong show, or already booked; NOT rejected)",
                    showId, ids.size(), updated, ids);
        }
        return new SeatsBookedResponse(showId, ids.size(), updated);
    }
}
