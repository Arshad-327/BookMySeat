package com.bookmyseat.event.service;

import com.bookmyseat.event.dto.request.CreateEventRequest;
import com.bookmyseat.event.dto.response.EventResponse;
import com.bookmyseat.event.entity.Event;
import com.bookmyseat.event.entity.Venue;
import com.bookmyseat.event.exception.VenueNotFoundException;
import com.bookmyseat.event.mapper.AdminMapper;
import com.bookmyseat.event.repository.EventRepository;
import com.bookmyseat.event.repository.VenueRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AdminEventService {

    private final EventRepository eventRepository;
    private final VenueRepository venueRepository;

    /**
     * The venue is loaded rather than referenced by proxy: the response echoes the
     * venue back, and a bad venueId must be a 404 here rather than a foreign-key
     * violation surfacing as a 409 at flush time.
     */
    @Transactional
    public EventResponse createEvent(CreateEventRequest request) {
        Venue venue = venueRepository.findById(request.venueId())
                .orElseThrow(() -> new VenueNotFoundException(request.venueId()));

        Event saved = eventRepository.save(AdminMapper.toEvent(request, venue));
        return AdminMapper.toEventResponse(saved);
    }
}
