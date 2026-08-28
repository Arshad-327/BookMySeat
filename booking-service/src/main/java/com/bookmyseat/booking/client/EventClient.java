package com.bookmyseat.booking.client;

import com.bookmyseat.booking.client.dto.BookSeatsRequest;
import com.bookmyseat.booking.client.dto.SeatMapResponse;
import com.bookmyseat.booking.client.dto.SeatResponse;
import com.bookmyseat.booking.client.dto.SeatsBookedResponse;
import com.bookmyseat.booking.exception.EventServiceUnavailableException;
import com.bookmyseat.booking.exception.ShowNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HTTP access to event-service. The only way this service learns anything about
 * seats - CLAUDE.md forbids reading another service's tables.
 *
 * <p>Base URL comes from app.event-service.base-url, so the same jar works against
 * localhost:8082 on the host and event-service:8082 inside Compose.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class EventClient {

    private final RestClient eventServiceRestClient;

    /**
     * Reads the seat map for a show and indexes it by show_seats id.
     *
     * <p>event-service has no "give me these specific seats" endpoint, so this
     * fetches the whole map and picks out what it needs. Fine for a 60-seat venue;
     * worth revisiting for a large one, since it transfers every seat to check two.
     *
     * <p>Linked, so iteration order matches the seat map rather than hash order -
     * it makes the logs readable when several seats are involved.
     */
    public Map<Long, SeatResponse> fetchSeatsById(Long showId) {
        SeatMapResponse seatMap = getSeatMap(showId);

        Map<Long, SeatResponse> byId = new LinkedHashMap<>();
        seatMap.rows().forEach(row -> row.seats().forEach(seat -> byId.put(seat.id(), seat)));
        return byId;
    }

    private SeatMapResponse getSeatMap(Long showId) {
        try {
            SeatMapResponse response = eventServiceRestClient.get()
                    .uri("/api/shows/{showId}/seats", showId)
                    .retrieve()
                    .onStatus(status -> status.value() == HttpStatus.NOT_FOUND.value(),
                            (request, clientResponse) -> {
                                throw new ShowNotFoundException(showId);
                            })
                    .body(SeatMapResponse.class);

            if (response == null || response.rows() == null) {
                throw new EventServiceUnavailableException(
                        "event-service returned an empty seat map for show " + showId, null);
            }
            return response;
        } catch (ShowNotFoundException | EventServiceUnavailableException ex) {
            throw ex;
        } catch (RestClientException ex) {
            // Timeouts, connection refused, 5xx. Distinguished from a 404 so the
            // caller can answer 503 rather than pretending the show does not exist.
            throw new EventServiceUnavailableException(
                    "event-service call failed for show " + showId, ex);
        }
    }

    /**
     * Marks seats BOOKED in event-service, on the confirm path.
     *
     * <p>The far side now writes through managed entities, so each row's
     * {@code @Version} is checked and incremented and a losing writer gets 409
     * rather than silently overwriting. A 409 arrives here as a RestClientException
     * and is translated to {@link EventServiceUnavailableException} below, which the
     * handler renders as 503 - imprecise, and worth mapping to a 409 of its own once
     * P3.5 defines the retry semantics.
     *
     * <p>This call happens <i>before</i> the local transaction commits, so a failure
     * rolls the confirm back. The remaining gap is the reverse: if event-service
     * marks the seats and this service then fails to commit, the seats are BOOKED
     * with no confirmed booking behind them. Nothing compensates for that yet.
     *
     * <p>{@code updated} is still only logged when it disagrees with the number
     * requested, not acted upon.
     */
    public SeatsBookedResponse markSeatsBooked(Long showId, List<Long> showSeatIds) {
        try {
            SeatsBookedResponse response = eventServiceRestClient.post()
                    .uri("/api/internal/shows/{showId}/seats/book", showId)
                    .body(new BookSeatsRequest(showSeatIds))
                    .retrieve()
                    .body(SeatsBookedResponse.class);

            if (response != null && response.updated() != showSeatIds.size()) {
                log.warn("show {}: asked event-service to book {} seats, it changed {} rows. "
                                + "NOT treated as an error (deliberately unguarded).",
                        showId, showSeatIds.size(), response.updated());
            }
            return response;
        } catch (RestClientException ex) {
            throw new EventServiceUnavailableException(
                    "event-service failed to mark seats booked for show " + showId, ex);
        }
    }
}
