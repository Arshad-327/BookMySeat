package com.bookmyseat.booking.client;

import com.bookmyseat.booking.client.dto.BookSeatsRequest;
import com.bookmyseat.booking.client.dto.EventErrorResponse;
import com.bookmyseat.booking.client.dto.SeatMapResponse;
import com.bookmyseat.booking.client.dto.SeatResponse;
import com.bookmyseat.booking.client.dto.SeatsBookedResponse;
import com.bookmyseat.booking.exception.EventServiceUnavailableException;
import com.bookmyseat.booking.exception.SeatBookingRejectedException;
import com.bookmyseat.booking.exception.ShowNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
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
     * Marks seats BOOKED in event-service, on the confirm path. Layer 2 runs there.
     *
     * <p>event-service is strict: every seat is booked or the call fails. Its refusals
     * - 409 for a seat already BOOKED or a lost optimistic lock, 404 for an id not in
     * the show - are verdicts about the seats, so they become
     * {@link SeatBookingRejectedException} and a 409 here. Anything else (timeouts,
     * connection refused, 5xx) is an outage and stays a 503.
     *
     * <p>This call happens <i>before</i> the local transaction commits, so a refusal
     * rolls the confirm back. The remaining gap is the reverse: if event-service marks
     * the seats and this service then fails to commit, the seats are BOOKED with no
     * confirmed booking behind them. Nothing compensates for that yet.
     */
    public SeatsBookedResponse markSeatsBooked(Long showId, List<Long> showSeatIds) {
        try {
            return eventServiceRestClient.post()
                    .uri("/api/internal/shows/{showId}/seats/book", showId)
                    .body(new BookSeatsRequest(showSeatIds))
                    .retrieve()
                    .body(SeatsBookedResponse.class);
        } catch (HttpClientErrorException.Conflict | HttpClientErrorException.NotFound ex) {
            throw new SeatBookingRejectedException(showId, showSeatIds, reasonFrom(ex), ex);
        } catch (RestClientException ex) {
            throw new EventServiceUnavailableException(
                    "event-service failed to mark seats booked for show " + showId, ex);
        }
    }

    /** event-service's own error message, or the status when the body is not its error shape. */
    private static String reasonFrom(HttpClientErrorException ex) {
        try {
            EventErrorResponse body = ex.getResponseBodyAs(EventErrorResponse.class);
            if (body != null && body.message() != null) {
                return body.message();
            }
        } catch (RuntimeException ignored) {
            // No body, or not JSON in the standard error shape. The status still says enough.
        }
        return ex.getStatusCode().toString();
    }
}
