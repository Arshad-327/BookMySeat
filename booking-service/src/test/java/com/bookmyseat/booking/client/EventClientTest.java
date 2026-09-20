package com.bookmyseat.booking.client;

import com.bookmyseat.booking.client.dto.SeatsBookedResponse;
import com.bookmyseat.booking.exception.EventServiceUnavailableException;
import com.bookmyseat.booking.exception.SeatBookingRejectedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

/**
 * How confirm reports event-service's answer to the seat write. A refusal about the
 * seats is a 409 here; only a genuine failure to reach or run event-service is a 503.
 */
class EventClientTest {

    private static final String BOOK_URL = "http://event-service/api/internal/shows/1/seats/book";

    private MockRestServiceServer server;
    private EventClient eventClient;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://event-service");
        server = MockRestServiceServer.bindTo(builder).build();
        eventClient = new EventClient(builder.build());
    }

    @Test
    @DisplayName("event-service 409 (seat already BOOKED) becomes a seat rejection carrying its message, not a 503")
    void conflictBecomesRejection() {
        server.expect(requestTo(BOOK_URL)).andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.CONFLICT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"status\":409,\"error\":\"Conflict\","
                                + "\"message\":\"Show 1: seat(s) [7] are already BOOKED\"}"));

        assertThatThrownBy(() -> eventClient.markSeatsBooked(1L, List.of(7L), 4471L))
                .isInstanceOf(SeatBookingRejectedException.class)
                .hasMessageContaining("Show 1: seat(s) [7] are already BOOKED");
    }

    @Test
    @DisplayName("event-service 404 (seat not in the show) becomes a seat rejection, not a 503")
    void notFoundBecomesRejection() {
        server.expect(requestTo(BOOK_URL)).andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.NOT_FOUND)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"status\":404,\"message\":\"Show 1 has no seat(s) [99]\"}"));

        assertThatThrownBy(() -> eventClient.markSeatsBooked(1L, List.of(99L), 4471L))
                .isInstanceOf(SeatBookingRejectedException.class)
                .hasMessageContaining("Show 1 has no seat(s) [99]");
    }

    @Test
    @DisplayName("event-service 500 is still an outage: 503")
    void serverErrorStaysUnavailable() {
        server.expect(requestTo(BOOK_URL)).andExpect(method(HttpMethod.POST))
                .andRespond(withServerError());

        assertThatThrownBy(() -> eventClient.markSeatsBooked(1L, List.of(7L), 4471L))
                .isInstanceOf(EventServiceUnavailableException.class);
    }

    @Test
    @DisplayName("the seat-booking request carries the booking id, so event-service can record who bought the seats")
    void requestCarriesTheBookingId() {
        server.expect(requestTo(BOOK_URL)).andExpect(method(HttpMethod.POST))
                // Asserted on the wire, not on the record: the record could hold the id and
                // still not serialise it, and event-service rejects a body without it.
                .andExpect(jsonPath("$.bookingId").value(4471))
                .andExpect(jsonPath("$.showSeatIds[0]").value(7))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"showId\":1,\"requested\":1,\"updated\":1}"));

        SeatsBookedResponse response = eventClient.markSeatsBooked(1L, List.of(7L), 4471L);

        assertThat(response.updated()).isEqualTo(1);
        server.verify();
    }
}
