package com.bookmyseat.event.service;

import com.bookmyseat.event.dto.response.InternalShowResponse;
import com.bookmyseat.event.entity.Show;
import com.bookmyseat.event.entity.ShowSeat;
import com.bookmyseat.event.exception.ShowNotFoundException;
import com.bookmyseat.event.mapper.InternalShowMapper;
import com.bookmyseat.event.repository.ShowRepository;
import com.bookmyseat.event.repository.ShowSeatRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * The read model behind GET /api/internal/shows/{id}. Read-only; writes nothing, ever.
 */
@Service
@RequiredArgsConstructor
public class InternalShowService {

    private final ShowRepository showRepository;
    private final ShowSeatRepository showSeatRepository;

    /**
     * One show plus labels for the given seat ids.
     *
     * <h2>Two queries, one round trip - which is the whole point</h2>
     * The consumer would otherwise make two HTTP calls across the network to assemble one
     * email. Here the second query is a local index lookup inside the same transaction. Two
     * SELECTs on one connection and two HTTP requests between two services are not the same
     * cost, and conflating them is how a service ends up chatty.
     *
     * <h2>Unknown seat ids are dropped, an unknown SHOW is 404</h2>
     * Deliberately asymmetric. Unlike {@link ShowService#findSeatMap}, this does check the
     * show exists - it must, because the show's own details are most of the answer and an
     * empty seat list is a legitimate result here rather than evidence of a missing show.
     *
     * <p>But an id that is not in this show is simply absent from the result, not an error.
     * This read is best-effort by contract: its caller degrades to printing raw ids when it
     * cannot reach this service at all, so refusing the whole response over one stale id
     * would cost the caller every label it could have had, to no one's benefit. The show
     * scoping in the query is what stops such an id reading another show's seat.
     *
     * @param seatIds may be empty - the show's details alone are still a usable answer
     * @throws ShowNotFoundException if no show has that id
     */
    @Transactional(readOnly = true)
    public InternalShowResponse findForConfirmation(Long showId, List<Long> seatIds) {
        Show show = showRepository.findWithEventAndVenueById(showId)
                .orElseThrow(() -> new ShowNotFoundException(showId));

        // Guarded because JPQL "IN :ids" with an empty collection is not portable SQL, and
        // an empty request has no row to find anyway.
        List<ShowSeat> showSeats = seatIds.isEmpty()
                ? List.of()
                : showSeatRepository.findLabelsByShowIdAndIdIn(showId, seatIds);

        return InternalShowMapper.toInternalShowResponse(show, showSeats);
    }
}
