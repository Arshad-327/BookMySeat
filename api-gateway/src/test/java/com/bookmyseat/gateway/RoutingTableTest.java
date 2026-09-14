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
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.time.Clock;
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
 * <p>Protected paths carry a valid access token. Without one they would stop at the
 * gateway's 401, which proves authentication, not routing - that is
 * GatewayAuthenticationTest's job.
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
        registry.add("app.jwt.secret", () -> TestTokens.SECRET);
        // Redis at a dead port too: rate limiting fails open, so routing is all this measures,
        // and the dev Redis on 6379 is never touched.
        registry.add("spring.data.redis.port", () -> DEAD_PORT);
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
    @DisplayName("every routed path is routed: not 404, but a failure to reach the absent downstream")
    void routedPathsReachForTheirDownstream(String method, String path, String downstream) {
        HttpStatus status = HttpStatus.valueOf(exchange(HttpMethod.valueOf(method), path).getStatus().value());

        assertThat(status)
                .as("%s %s should be routed to %s", method, path, downstream)
                .isNotEqualTo(HttpStatus.NOT_FOUND);
        assertThat(status.is5xxServerError())
                .as("%s %s should fail reaching the absent %s, got %s", method, path, downstream, status)
                .isTrue();
    }

    @Test
    @DisplayName("GET /api/demo/spam is routed - to no://op, so it answers 200 with every downstream absent")
    void demoSpamIsRoutedToNoOp() {
        // 200 while every service URL is a dead port proves it forwards nowhere; not 404
        // proves it is a gateway route, and so inside the global filter chain.
        assertThat(exchange(HttpMethod.GET, "/api/demo/spam").getStatus().value()).isEqualTo(200);
    }

    @Test
    @DisplayName("POST /api/demo/spam is not public: GET-only is enforced by the JWT filter, not a route predicate")
    void demoSpamIsPublicForGetOnly() {
        EntityExchangeResult<byte[]> result = webTestClient.post().uri("/api/demo/spam")
                .exchange().expectBody().returnResult();

        assertThat(result.getStatus().value()).isEqualTo(401);
    }

    @Test
    @DisplayName("/api/internal/** is never routed: 404 from the gateway itself, in the standard error shape")
    void internalApiIsNotRouted() throws Exception {
        String path = "/api/internal/shows/1/seats/book";
        EntityExchangeResult<byte[]> result = exchange(HttpMethod.POST, path);

        assertThat(result.getStatus().value()).isEqualTo(404);
        TestTokens.assertStandardErrorShape(result.getResponseBodyContent(), 404, path);
    }

    @Test
    @DisplayName("/actuator/** on the public port is never routed: 404, in the standard error shape")
    void actuatorIsNotServedOnThePublicPort() throws Exception {
        for (String path : new String[] {"/actuator/health", "/actuator/env"}) {
            EntityExchangeResult<byte[]> result = exchange(HttpMethod.GET, path);

            assertThat(result.getStatus().value()).as(path).isEqualTo(404);
            TestTokens.assertStandardErrorShape(result.getResponseBodyContent(), 404, path);
        }
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

    /** Every request carries a valid token, so protected routes get past authentication. */
    private EntityExchangeResult<byte[]> exchange(HttpMethod method, String path) {
        return webTestClient
                .mutate().responseTimeout(Duration.ofSeconds(15)).build()
                .method(method).uri(path)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + TestTokens.mint(2L, "ADMIN", Clock.systemUTC()))
                .exchange()
                .expectBody()
                .returnResult();
    }

    private static int unusedPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException ex) {
            throw new UncheckedIOException("could not find a free port", ex);
        }
    }
}
