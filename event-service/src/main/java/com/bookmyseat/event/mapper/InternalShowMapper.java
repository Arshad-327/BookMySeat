package com.bookmyseat.event.mapper;

import com.bookmyseat.event.dto.response.InternalSeatLabelResponse;
import com.bookmyseat.event.dto.response.InternalShowResponse;
import com.bookmyseat.event.entity.Show;
import com.bookmyseat.event.entity.ShowSeat;

import java.util.List;

/**
 * Hand-written mapping, per CLAUDE.md. No mapping framework.
 */
public final class InternalShowMapper {

    private InternalShowMapper() {
    }

    /**
     * Projects a show and the requested seats into the confirmation read model.
     *
     * <p>Both arguments must come from the fetching queries
     * ({@code findWithEventAndVenueById}, {@code findLabelsByShowIdAndIdIn}). This method
     * touches {@code show.event.venue} and {@code showSeat.seat}, which are LAZY: handed
     * entities loaded any other way it would either fire a SELECT per seat or, with
     * open-in-view off and no transaction, fail outright.
     */
    public static InternalShowResponse toInternalShowResponse(Show show, List<ShowSeat> showSeats) {
        return new InternalShowResponse(
                show.getId(),
                show.getEvent().getTitle(),
                show.getEvent().getVenue().getName(),
                show.getStartsAt(),
                showSeats.stream().map(InternalShowMapper::toLabel).toList()
        );
    }

    /**
     * "C" and 2 become "C2".
     *
     * <p>Joined here rather than in the consumer so the two services never have to agree on
     * how a seat is spelled. The label is the contract; its parts are this service's business.
     */
    private static InternalSeatLabelResponse toLabel(ShowSeat showSeat) {
        return new InternalSeatLabelResponse(
                showSeat.getId(),
                showSeat.getSeat().getRowLabel() + showSeat.getSeat().getSeatNumber()
        );
    }
}
