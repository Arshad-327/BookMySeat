package com.bookmyseat.event.service;

import com.bookmyseat.event.dto.request.CreateVenueRequest;
import com.bookmyseat.event.dto.request.GenerateSeatsRequest;
import com.bookmyseat.event.dto.response.SeatGenerationResponse;
import com.bookmyseat.event.dto.response.VenueResponse;
import com.bookmyseat.event.entity.Seat;
import com.bookmyseat.event.entity.Venue;
import com.bookmyseat.event.exception.SeatsAlreadyExistException;
import com.bookmyseat.event.exception.VenueNotFoundException;
import com.bookmyseat.event.mapper.AdminMapper;
import com.bookmyseat.event.mapper.EventMapper;
import com.bookmyseat.event.repository.SeatRepository;
import com.bookmyseat.event.repository.VenueRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class AdminVenueService {

    private final VenueRepository venueRepository;
    private final SeatRepository seatRepository;

    @Transactional
    public VenueResponse createVenue(CreateVenueRequest request) {
        Venue saved = venueRepository.save(AdminMapper.toVenue(request));
        return EventMapper.toVenueResponse(saved);
    }

    /**
     * Creates a venue's physical seats, once.
     *
     * <p>Generation is all-or-nothing rather than additive. A venue that already has
     * seats is a 409: re-running with a different row list would interleave two
     * partial maps, and re-running with the same list would fail row by row on
     * uq_seats_venue_row_number anyway.
     *
     * <p>The pre-check is not the real guarantee. Two concurrent calls can both pass
     * it before either commits; the unique constraint is what actually prevents the
     * duplicate, and GlobalExceptionHandler turns that violation into the same 409.
     */
    @Transactional
    public SeatGenerationResponse generateSeats(Long venueId, GenerateSeatsRequest request) {
        Venue venue = venueRepository.findById(venueId)
                .orElseThrow(() -> new VenueNotFoundException(venueId));

        if (seatRepository.existsByVenueId(venueId)) {
            throw new SeatsAlreadyExistException(venueId);
        }

        List<String> rows = normaliseRows(request.rows());
        int seatsPerRow = request.seatsPerRow();
        String seatType = request.seatType().trim().toUpperCase(Locale.ROOT);

        List<Seat> seats = new ArrayList<>(rows.size() * seatsPerRow);
        for (String rowLabel : rows) {
            for (int seatNumber = 1; seatNumber <= seatsPerRow; seatNumber++) {
                seats.add(AdminMapper.toSeat(venue, rowLabel, seatNumber, seatType));
            }
        }
        seatRepository.saveAll(seats);

        return new SeatGenerationResponse(venueId, rows, seatsPerRow, seats.size());
    }

    /**
     * Uppercases labels and rejects duplicates.
     *
     * <p>Case matters here: "a" and "A" are one row to a human and to the unique
     * constraint under a case-insensitive collation, so accepting both would fail at
     * the database rather than at the request. Linked, to preserve request order in
     * the response.
     */
    private static List<String> normaliseRows(List<String> requested) {
        Set<String> unique = new LinkedHashSet<>();
        List<String> duplicates = new ArrayList<>();

        for (String row : requested) {
            String normalised = row.trim().toUpperCase(Locale.ROOT);
            if (!unique.add(normalised)) {
                duplicates.add(normalised);
            }
        }
        if (!duplicates.isEmpty()) {
            throw new IllegalArgumentException("Duplicate row labels in request: " + duplicates);
        }
        return List.copyOf(unique);
    }
}
