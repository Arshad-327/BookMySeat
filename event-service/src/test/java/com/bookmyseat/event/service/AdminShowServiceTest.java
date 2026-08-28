package com.bookmyseat.event.service;

import com.bookmyseat.event.dto.request.CreateShowRequest;
import com.bookmyseat.event.dto.response.ShowCreatedResponse;
import com.bookmyseat.event.entity.Event;
import com.bookmyseat.event.entity.Seat;
import com.bookmyseat.event.entity.SeatStatus;
import com.bookmyseat.event.entity.Show;
import com.bookmyseat.event.entity.ShowSeat;
import com.bookmyseat.event.entity.Venue;
import com.bookmyseat.event.exception.EventNotFoundException;
import com.bookmyseat.event.exception.VenueHasNoSeatsException;
import com.bookmyseat.event.repository.EventRepository;
import com.bookmyseat.event.repository.SeatRepository;
import com.bookmyseat.event.repository.ShowRepository;
import com.bookmyseat.event.repository.ShowSeatRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminShowServiceTest {

    private static final Long EVENT_ID = 42L;
    private static final Long VENUE_ID = 12L;
    private static final Instant STARTS_AT = Instant.parse("2026-09-14T18:30:00Z");
    private static final BigDecimal BASE_PRICE = new BigDecimal("450.00");

    @Mock
    private ShowRepository showRepository;
    @Mock
    private ShowSeatRepository showSeatRepository;
    @Mock
    private EventRepository eventRepository;
    @Mock
    private SeatRepository seatRepository;

    @InjectMocks
    private AdminShowService adminShowService;

    private Event event;
    private Venue venue;

    @BeforeEach
    void setUp() {
        venue = new Venue();
        venue.setId(VENUE_ID);
        venue.setName("Phoenix Arena");
        venue.setCity("Bengaluru");

        event = new Event();
        event.setId(EVENT_ID);
        event.setTitle("Coldplay - Music of the Spheres");
        event.setCategory("CONCERT");
        event.setVenue(venue);
    }

    /**
     * The guard that lets GET /api/shows/{id}/seats keep its single-query 404.
     *
     * <p>If this test ever fails, that endpoint's 404 has silently become a lie:
     * a show would exist with no show_seats rows, and the seat map would report it
     * as missing.
     */
    @Test
    @DisplayName("rejects a show at a venue with zero seats, and writes nothing")
    void rejectsShowWhenVenueHasNoSeats() {
        when(eventRepository.findByIdWithVenue(EVENT_ID)).thenReturn(Optional.of(event));
        when(seatRepository.findByVenueId(VENUE_ID)).thenReturn(List.of());

        CreateShowRequest request = new CreateShowRequest(EVENT_ID, STARTS_AT, BASE_PRICE);

        assertThatThrownBy(() -> adminShowService.createShow(request))
                .isInstanceOf(VenueHasNoSeatsException.class)
                .hasMessageContaining("Venue " + VENUE_ID + " has no seats");

        // The show must not be written either: a persisted show with no seats is
        // exactly the state the seat-map 404 assumes cannot happen.
        verify(showRepository, never()).save(any());
        verify(showSeatRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("creates one show_seats row per venue seat, all AVAILABLE at base price")
    void createsOneShowSeatPerVenueSeat() {
        List<Seat> seats = seats(60);
        when(eventRepository.findByIdWithVenue(EVENT_ID)).thenReturn(Optional.of(event));
        when(seatRepository.findByVenueId(VENUE_ID)).thenReturn(seats);
        when(showRepository.save(any(Show.class))).thenAnswer(invocation -> {
            Show show = invocation.getArgument(0);
            show.setId(301L);
            return show;
        });

        ShowCreatedResponse response =
                adminShowService.createShow(new CreateShowRequest(EVENT_ID, STARTS_AT, BASE_PRICE));

        assertThat(response.id()).isEqualTo(301L);
        assertThat(response.eventId()).isEqualTo(EVENT_ID);
        assertThat(response.startsAt()).isEqualTo(STARTS_AT);
        assertThat(response.seatsCreated()).isEqualTo(60);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ShowSeat>> captor = ArgumentCaptor.forClass(List.class);
        verify(showSeatRepository).saveAll(captor.capture());

        List<ShowSeat> written = captor.getValue();
        assertThat(written).hasSize(60);
        assertThat(written).allSatisfy(showSeat -> {
            assertThat(showSeat.getStatus()).isEqualTo(SeatStatus.AVAILABLE);
            assertThat(showSeat.getPrice()).isEqualByComparingTo(BASE_PRICE);
            assertThat(showSeat.getShow().getId()).isEqualTo(301L);
        });
        // Every venue seat is represented exactly once.
        assertThat(written).map(showSeat -> showSeat.getSeat().getId())
                .containsExactlyInAnyOrderElementsOf(seats.stream().map(Seat::getId).toList());
    }

    @Test
    @DisplayName("unknown event is a 404, checked before any seat lookup")
    void rejectsUnknownEvent() {
        when(eventRepository.findByIdWithVenue(EVENT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> adminShowService.createShow(
                new CreateShowRequest(EVENT_ID, STARTS_AT, BASE_PRICE)))
                .isInstanceOf(EventNotFoundException.class);

        verify(seatRepository, never()).findByVenueId(any());
        verify(showRepository, never()).save(any());
    }

    private List<Seat> seats(int count) {
        List<Seat> seats = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            Seat seat = new Seat();
            seat.setId((long) i);
            seat.setVenue(venue);
            seat.setRowLabel(String.valueOf((char) ('A' + (i - 1) / 10)));
            seat.setSeatNumber(((i - 1) % 10) + 1);
            seat.setSeatType("REGULAR");
            seats.add(seat);
        }
        return seats;
    }
}
