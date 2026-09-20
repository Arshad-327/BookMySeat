package com.bookmyseat.booking.client;

import com.bookmyseat.booking.client.dto.BookSeatsRequest;
import com.bookmyseat.booking.client.dto.EventErrorResponse;
import com.bookmyseat.booking.client.dto.ReleaseSeatsRequest;
import com.bookmyseat.booking.client.dto.SeatsReleasedResponse;
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
     *
     * <p>{@code bookingId} is sent so that event-service can record WHICH booking each
     * seat was sold to, in the same write as the status flip. That is what makes the gap
     * above detectable rather than merely known about: an orphaned seat then names the
     * booking that never committed, instead of being an anonymous BOOKED row. Nothing
     * reads it yet - this is the record, not the repair. It is required: event-service
     * answers 400 for a null, which is a bug in this service surfacing loudly rather than
     * an ownerless seat being written quietly.
     */
    public SeatsBookedResponse markSeatsBooked(Long showId, List<Long> showSeatIds, Long bookingId) {
        try {
            return eventServiceRestClient.post()
                    .uri("/api/internal/shows/{showId}/seats/book", showId)
                    .body(new BookSeatsRequest(showSeatIds, bookingId))
                    .retrieve()
                    .body(SeatsBookedResponse.class);
        } catch (HttpClientErrorException.Conflict | HttpClientErrorException.NotFound ex) {
            throw new SeatBookingRejectedException(showId, showSeatIds, reasonFrom(ex), ex);
        } catch (RestClientException ex) {
            // Confirm-specific wording. The default - "event-service is unavailable, please
            // retry" - is true on the hold path, where nothing was written and a retry is
            // clean. It is NOT true here: this call may well have committed the seats before
            // the timeout fired, so "nothing happened, try again" would be a statement the
            // service cannot make. See EventServiceUnavailableException.
            throw new EventServiceUnavailableException(
                    "event-service failed to mark seats booked for show " + showId, ex,
                    "The booking could not be confirmed, please retry");
        }
    }

    /**
     * Releases seats this booking holds in event-service, on the compensation path.
     *
     * <p>The reverse of {@link #markSeatsBooked}, and deliberately the reverse in its
     * failure behaviour too. event-service frees only the seats whose recorded owner is
     * this booking and skips everything else, so there is no such thing as a refusal here:
     * no 404 for a seat that does not exist, no 409 for a seat somebody else owns.
     * {@code released: 0} is a success and is the normal answer, because most bookings
     * never marked a seat in the first place.
     *
     * <p>That leaves one failure mode, an outage, and it stays a 503 exactly as it does on
     * the booking path. A 4xx would mean this service sent a malformed body - a bug, not a
     * seat verdict - and it is wrapped the same way rather than given a category of its own;
     * there is no caller that could do anything different with it.
     *
     * <p>The caller decides what a failure means. ExpiredBookingSweeper lets it propagate so
     * the booking stays PENDING and the next pass retries.
     */
    public SeatsReleasedResponse releaseSeats(Long showId, List<Long> showSeatIds, Long bookingId) {
        try {
            return eventServiceRestClient.post()
                    .uri("/api/internal/shows/{showId}/seats/release", showId)
                    .body(new ReleaseSeatsRequest(showSeatIds, bookingId))
                    .retrieve()
                    .body(SeatsReleasedResponse.class);
        } catch (RestClientException ex) {
            // Default userMessage. This path is reached from the sweeper, which has no HTTP
            // response to render it into; if a request-scoped caller is added later it will
            // need wording of its own, the way the confirm path has.
            throw new EventServiceUnavailableException(
                    "event-service failed to release seats for show " + showId
                            + " held by booking " + bookingId, ex);
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
