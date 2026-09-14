package com.bookmyseat.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JwtAuthenticationFilter's verdicts, with the same no-stub technique as RoutingTableTest:
 * every downstream is a dead port. A 401 is the filter refusing. A 5xx is a request the
 * filter let through, failing to reach a downstream that is not there.
 *
 * <p><b>The gateway's Clock is pinned in 2020.</b> By system time every token here expired
 * years ago. So the "valid token is let through" control can only pass if expiry is judged
 * by the injected Clock, which is the Timekeeping rule applied to the verify path. It is
 * also what makes a "valid token" case trustworthy: a filter that rejected every token
 * would fail it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayAuthenticationTest {

    private static final Instant NOW = Instant.parse("2020-01-01T10:00:00Z");
    private static final Clock GATEWAY_CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final int DEAD_PORT = unusedPort();

    @TestConfiguration
    static class PinnedClock {
        @Bean
        @Primary
        Clock pinnedClock() {
            return GATEWAY_CLOCK;
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String nowhere = "http://localhost:" + DEAD_PORT;
        registry.add("app.services.auth-service", () -> nowhere);
        registry.add("app.services.event-service", () -> nowhere);
        registry.add("app.services.booking-service", () -> nowhere);
        registry.add("app.jwt.secret", () -> TestTokens.SECRET);
        registry.add("management.server.port", () -> "0");
    }

    @Autowired
    private WebTestClient webTestClient;

    private static String validToken() {
        return TestTokens.mint(2L, "USER", GATEWAY_CLOCK);
    }

    // ---------------------------------------------------------------------------------
    // Protected paths
    // ---------------------------------------------------------------------------------

    @ParameterizedTest(name = "{0} {1} with no token -> 401")
    @CsvSource({
            "GET,    /api/bookings/1",
            "POST,   /api/bookings/hold",
            "DELETE, /api/bookings/1",
            "POST,   /api/admin/venues",
            // The catalogue is public for reads only. A write under it is protected.
            "POST,   /api/events/1"
    })
    @DisplayName("a protected path with no token is 401, in the standard error shape")
    void protectedPathWithoutTokenIsRejected(String method, String path) throws Exception {
        EntityExchangeResult<byte[]> result = send(HttpMethod.valueOf(method), path, null);

        assertUnauthorized(result, path);
        assertThat(result.getResponseHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo("Bearer");
    }

    @Test
    @DisplayName("an EXPIRED token is 401: minted by a clock an hour before the gateway's")
    void expiredTokenIsRejected() throws Exception {
        String expired = TestTokens.mint(2L, "USER", Clock.fixed(NOW.minus(Duration.ofHours(1)), ZoneOffset.UTC));

        EntityExchangeResult<byte[]> result = send(HttpMethod.GET, "/api/bookings/1", expired);

        assertUnauthorized(result, "/api/bookings/1");
        assertThat(result.getResponseHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE))
                .isEqualTo("Bearer error=\"invalid_token\"");
    }

    @Test
    @DisplayName("a token with a TAMPERED signature is 401")
    void tamperedSignatureIsRejected() throws Exception {
        assertUnauthorized(
                send(HttpMethod.GET, "/api/bookings/1", TestTokens.withSignatureTampered(validToken())),
                "/api/bookings/1");
    }

    @Test
    @DisplayName("a token whose payload was edited to another user, original signature kept, is 401")
    void forgedPayloadIsRejected() throws Exception {
        assertUnauthorized(
                send(HttpMethod.GET, "/api/bookings/1", TestTokens.withSubjectForged(validToken(), 1L)),
                "/api/bookings/1");
    }

    @Test
    @DisplayName("a token signed with a different secret is 401")
    void wrongKeyIsRejected() throws Exception {
        String foreign = TestTokens.mint(2L, "USER", GATEWAY_CLOCK, "a-different-secret-that-is-also-long-enough-0123456789");

        assertUnauthorized(send(HttpMethod.GET, "/api/bookings/1", foreign), "/api/bookings/1");
    }

    @Test
    @DisplayName("an unsigned alg:none token is 401")
    void unsignedTokenIsRejected() throws Exception {
        assertUnauthorized(send(HttpMethod.GET, "/api/bookings/1", TestTokens.unsigned()), "/api/bookings/1");
    }

    @Test
    @DisplayName("control: a VALID token on a protected path is let through - 5xx from the absent downstream, not 401")
    void validTokenIsLetThrough() {
        EntityExchangeResult<byte[]> result = send(HttpMethod.GET, "/api/bookings/1", validToken());

        assertThat(result.getStatus().value()).isNotEqualTo(401);
        assertThat(result.getStatus().is5xxServerError()).isTrue();
    }

    // ---------------------------------------------------------------------------------
    // Public paths
    // ---------------------------------------------------------------------------------

    @ParameterizedTest(name = "{0} {1} with no token -> not 401")
    @CsvSource({
            "GET,  /api/events",
            "GET,  /api/events/1",
            "GET,  /api/shows/1/seats",
            "POST, /api/auth/login",
            "POST, /api/auth/register"
    })
    @DisplayName("a public path with no token is let through")
    void publicPathWithoutTokenIsLetThrough(String method, String path) {
        EntityExchangeResult<byte[]> result = send(HttpMethod.valueOf(method), path, null);

        assertThat(result.getStatus().value()).isNotEqualTo(401);
        assertThat(result.getStatus().is5xxServerError()).isTrue();
    }

    @Test
    @DisplayName("a public seat-map poll carrying an EXPIRED token is still let through, not 401")
    void publicPathIgnoresAnExpiredToken() {
        String expired = TestTokens.mint(2L, "USER", Clock.fixed(NOW.minus(Duration.ofHours(1)), ZoneOffset.UTC));

        EntityExchangeResult<byte[]> result = send(HttpMethod.GET, "/api/shows/1/seats", expired);

        assertThat(result.getStatus().value()).isNotEqualTo(401);
    }

    @Test
    @DisplayName("a CORS preflight to a protected path is answered, not 401 - it carries no token by design")
    void corsPreflightIsNotAuthenticated() {
        webTestClient.options().uri("/api/bookings/hold")
                .header("Origin", "http://localhost:5173")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "authorization,content-type,idempotency-key")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("Access-Control-Allow-Origin", "http://localhost:5173");
    }

    @Test
    @DisplayName("a CORS preflight asking to send X-User-Id is refused - browsers no longer send it")
    void corsPreflightForUserIdHeaderIsRefused() {
        webTestClient.options().uri("/api/bookings/hold")
                .header("Origin", "http://localhost:5173")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "x-user-id")
                .exchange()
                .expectStatus().isForbidden();
    }

    // ---------------------------------------------------------------------------------

    @Test
    @DisplayName("401 and 404 bodies carry the timestamp from the injected Clock, as an Instant ending in Z")
    void errorTimestampsComeFromTheInjectedClock() throws Exception {
        EntityExchangeResult<byte[]> unauthorized = send(HttpMethod.GET, "/api/bookings/1", null);
        EntityExchangeResult<byte[]> notFound = send(HttpMethod.GET, "/api/internal/anything", null);

        assertThat(TestTokens.assertStandardErrorShape(unauthorized.getResponseBodyContent(), 401, "/api/bookings/1")
                .get("timestamp").asText()).isEqualTo("2020-01-01T10:00:00Z");
        assertThat(TestTokens.assertStandardErrorShape(notFound.getResponseBodyContent(), 404, "/api/internal/anything")
                .get("timestamp").asText()).isEqualTo("2020-01-01T10:00:00Z");
    }

    private EntityExchangeResult<byte[]> send(HttpMethod method, String path, String token) {
        WebTestClient.RequestBodySpec request = webTestClient
                .mutate().responseTimeout(Duration.ofSeconds(15)).build()
                .method(method).uri(path)
                .header("Content-Type", "application/json");
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return request.exchange().expectBody().returnResult();
    }

    private static void assertUnauthorized(EntityExchangeResult<byte[]> result, String path) throws Exception {
        assertThat(result.getStatus().value()).isEqualTo(401);
        TestTokens.assertStandardErrorShape(result.getResponseBodyContent(), 401, path);
    }

    private static int unusedPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException ex) {
            throw new UncheckedIOException("could not find a free port", ex);
        }
    }
}
