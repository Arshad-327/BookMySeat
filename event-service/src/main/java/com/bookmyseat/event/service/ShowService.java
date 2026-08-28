package com.bookmyseat.event.service;

import com.bookmyseat.event.dto.response.SeatMapResponse;
import com.bookmyseat.event.entity.ShowSeat;
import com.bookmyseat.event.exception.ShowNotFoundException;
import com.bookmyseat.event.mapper.SeatMapMapper;
import com.bookmyseat.event.repository.ShowSeatRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ShowService {

    private final ShowSeatRepository showSeatRepository;

    /**
     * The seat map for one show. Exactly one SQL statement.
     *
     * <p>Note what is deliberately absent: there is no existence check on the show.
     * Verifying it would cost a second SELECT on the hottest endpoint in the system
     * to answer a question the seat map already answers - a show with no show_seats
     * rows is not a show anyone can book. An empty result is therefore reported as
     * 404 rather than as an empty map.
     */
    @Transactional(readOnly = true)
    public SeatMapResponse findSeatMap(Long showId) {
        List<ShowSeat> showSeats = showSeatRepository.findSeatMapByShowId(showId);

        if (showSeats.isEmpty()) {
            throw new ShowNotFoundException(showId);
        }
        return SeatMapMapper.toSeatMapResponse(showId, showSeats);
    }
}
