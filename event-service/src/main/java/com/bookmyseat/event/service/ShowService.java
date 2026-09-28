package com.bookmyseat.event.service;

import com.bookmyseat.event.dto.response.SeatMapResponse;
import com.bookmyseat.event.entity.Show;
import com.bookmyseat.event.entity.ShowSeat;
import com.bookmyseat.event.exception.ShowNotFoundException;
import com.bookmyseat.event.mapper.SeatMapMapper;
import com.bookmyseat.event.repository.ShowRepository;
import com.bookmyseat.event.repository.ShowSeatRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ShowService {

    private final ShowRepository showRepository;
    private final ShowSeatRepository showSeatRepository;

    /**
     * The seat map for one show, with the event title, venue name and start time above it.
     *
     * <h2>Two SQL statements, and it used to be one</h2>
     * This was a single query over show_seats that never loaded the Show at all, and it
     * answered 404 for an empty result rather than checking the show existed - the check
     * would have cost a second SELECT on the hottest endpoint in the system to answer a
     * question the seat map already answered.
     *
     * <p>That is no longer true, because the response now has to say WHICH show it is, and
     * the title lives on the event, the name on the venue. So the show is loaded, and the
     * existence check comes free with it: {@code findWithEventAndVenueById} is REUSED from
     * the internal read model rather than duplicated here, and it already brings event and
     * venue in the same statement.
     *
     * <p>The alternative was to keep one statement by JOIN FETCHing show, event and venue
     * into the seat-map query. Rejected: it would repeat the title and the venue name on
     * every one of the show's seat rows, and it would make the query load Show, which
     * {@code findSeatMapByShowId} documents that it deliberately does not. Two indexed
     * lookups on one connection inside one transaction are not two round trips - the same
     * trade {@link InternalShowService#findForConfirmation} makes and for the same reason.
     *
     * <h2>The 404 has the same two causes as before</h2>
     * An unknown show id is 404 from the lookup below, decided before the seat map is read
     * at all rather than by an empty result. A show that EXISTS with no show_seats rows is still
     * 404, from the emptiness check: a show nobody can book a seat at is not a show to
     * render a grid for, and AdminShowService refuses to create one, so the case should be
     * unreachable. The behaviour visible to a caller is unchanged; only the reason the first
     * of the two fires has moved.
     */
    @Transactional(readOnly = true)
    public SeatMapResponse findSeatMap(Long showId) {
        Show show = showRepository.findWithEventAndVenueById(showId)
                .orElseThrow(() -> new ShowNotFoundException(showId));

        List<ShowSeat> showSeats = showSeatRepository.findSeatMapByShowId(showId);

        if (showSeats.isEmpty()) {
            throw new ShowNotFoundException(showId);
        }
        return SeatMapMapper.toSeatMapResponse(show, showSeats);
    }
}
