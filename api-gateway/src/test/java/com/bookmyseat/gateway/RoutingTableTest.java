package com.bookmyseat.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The routing table, tested by the difference between "no route" and "route to nowhere".
 *
 * <p>Every downstream URL points at a port where nothing is listening, so no request can
 * ever reach a real service, and there is nothing to stub. That leaves two outcomes a
 * request can have:
 *
 * <ul>
 *   <li><b>404</b> - no route matched, and the gateway answered by itself.</li>
 *   <li><b>5xx</b> - a route matched, and the gateway failed to reach the downstream it
 *       points at. That failure is the proof the route exists.</li>
 * </ul>
 *
 * <p>A 404 cannot come from a downstream here, because no downstream is reachable. So a
 * 404 means unrouted, and anything else means routed.
 *
 * <p><b>The unrouted cases are a security property, not a convenience.</b>
 * /api/internal/** lets its caller mark seats BOOKED with no authentication, and must
 * never be reachable through the public port. A catch-all route added later, or a
 * reorganised application.yml, would break that silently. This test is what makes that
 * change fail the build.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RoutingTableTest {

    /** Reserved and released at class load, so nothing is listening on it. */
    private static final int DEAD_PORT = unusedPort();

    @DynamicPropertySource
    static void pointEveryRouteAtNothing(DynamicPropertyRegistry registry) {
        String nowhere = "http://localhost:" + DEAD_PORT;
        registry.add("app.services.auth-service", () -> nowhere);
        registry.add("app.services.event-service", () -> nowhere);
        registry.add("app.services.booking-service", () -> nowhere);
        // A random management port, so a gateway already running on 8090 cannot collide.
        registry.add("management.server.port", () -> "0");
    }

    @Autowired
    private WebTestClient webTestClient;

    @LocalManagementPort
    private int managementPort;

    @ParameterizedTest(name = "{0} {1} is routed (reaches for {2}, which is absent)")
    @CsvSource({
            "POST, /api/auth/login,                auth-service",
            "GET,  /api/events,                    event-service",
            "GET,  /api/shows/1/seats,             event-service",
            "POST, /api/admin/venues,              event-service",
            "GET,  /api/bookings/1,                booking-service",
            "POST, /api/bookings/hold,             booking-service"
    })
    @DisplayName("every public path is routed: not 404, but a failure to reach the absent downstream")
    void publicPathsAreRouted(String method, String path, String downstream) {
        HttpStatus status = statusOf(HttpMethod.valueOf(method), path);

        assertThat(status)
                .as("%s %s should be routed to %s", method, path, downstream)
                .isNotEqualTo(HttpStatus.NOT_FOUND);
        assertThat(status.is5xxServerError())
                .as("%s %s should fail reaching the absent %s, got %s", method, path, downstream, status)
                .isTrue();
    }

    @Test
    @DisplayName("/api/internal/** is never routed: 404 from the gateway itself")
    void internalApiIsNotRouted() {
        assertThat(statusOf(HttpMethod.POST, "/api/internal/shows/1/seats/book")).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("/actuator/** on the public port is never routed: 404")
    void actuatorIsNotServedOnThePublicPort() {
        assertThat(statusOf(HttpMethod.GET, "/actuator/health")).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(statusOf(HttpMethod.GET, "/actuator/env")).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("the gateway's own health is served, on the management port only")
    void gatewayHealthIsOnTheManagementPort() {
        WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + managementPort)
                .build()
                .get().uri("/actuator/health")
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("UP");
    }

    private HttpStatus statusOf(HttpMethod method, String path) {
        return HttpStatus.valueOf(webTestClient
                .mutate().responseTimeout(Duration.ofSeconds(15)).build()
                .method(method).uri(path)
                .header("Content-Type", "application/json")
                .exchange()
                .returnResult(Void.class)
                .getStatus()
                .value());
    }

    private static int unusedPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException ex) {
            throw new UncheckedIOException("could not find a free port", ex);
        }
    }
}
