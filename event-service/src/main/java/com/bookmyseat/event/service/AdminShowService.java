package com.bookmyseat.event.service;

import com.bookmyseat.event.dto.request.CreateShowRequest;
import com.bookmyseat.event.dto.response.ShowCreatedResponse;
import com.bookmyseat.event.entity.Event;
import com.bookmyseat.event.entity.Seat;
import com.bookmyseat.event.entity.Show;
import com.bookmyseat.event.entity.ShowSeat;
import com.bookmyseat.event.exception.EventNotFoundException;
import com.bookmyseat.event.exception.VenueHasNoSeatsException;
import com.bookmyseat.event.mapper.AdminMapper;
import com.bookmyseat.event.repository.EventRepository;
import com.bookmyseat.event.repository.SeatRepository;
import com.bookmyseat.event.repository.ShowRepository;
import com.bookmyseat.event.repository.ShowSeatRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AdminShowService {

    private final ShowRepository showRepository;
    private final ShowSeatRepository showSeatRepository;
    private final EventRepository eventRepository;
    private final SeatRepository seatRepository;

    /**
     * Creates a show and its full seat map in one transaction.
     *
     * <h2>Why the empty-venue check exists</h2>
     * A venue with no seats is rejected with 409 rather than producing a show with
     * zero show_seats rows. This is not defensive tidiness - it is what makes
     * GET /api/shows/{id}/seats correct. That endpoint is the highest-traffic query
     * in the system, runs exactly one SQL statement, and deliberately performs no
     * existence check on the show: it reports an empty result as 404. That is only
     * truthful while a seatless show cannot exist. Delete this guard and the seat
     * map starts returning 404 for shows that are really there, and the fix would be
     * a second query on the hottest path in the system.
     *
     * <p>So: a show cannot exist without seats. Enforced here, once, on the cold
     * write path, so the hot read path never has to ask.
     *
     * <h2>On batching</h2>
     * show_seats rows are inserted with saveAll under hibernate.jdbc.batch_size=50.
     * Note that every entity in this service uses GenerationType.IDENTITY, and
     * Hibernate must read each generated key back, which limits how much real
     * batching the driver can do. The statement count is measured rather than
     * assumed - see the verification notes for this change.
     */
    @Transactional
    public ShowCreatedResponse createShow(CreateShowRequest request) {
        Event event = eventRepository.findByIdWithVenue(request.eventId())
                .orElseThrow(() -> new EventNotFoundException(request.eventId()));

        Long venueId = event.getVenue().getId();
        List<Seat> seats = seatRepository.findByVenueId(venueId);
        if (seats.isEmpty()) {
            throw new VenueHasNoSeatsException(venueId);
        }

        Show show = showRepository.save(AdminMapper.toShow(request, event));

        List<ShowSeat> showSeats = new ArrayList<>(seats.size());
        for (Seat seat : seats) {
            showSeats.add(AdminMapper.toShowSeat(show, seat, request.basePrice()));
        }
        showSeatRepository.saveAll(showSeats);

        return AdminMapper.toShowCreatedResponse(show, showSeats.size());
    }
}
