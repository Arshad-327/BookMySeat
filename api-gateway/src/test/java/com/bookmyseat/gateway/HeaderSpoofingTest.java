package com.bookmyseat.gateway;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The spoofing tests. They assert on what the DOWNSTREAM receives, never on the status code.
 *
 * <p>A 200 proves nothing here: a gateway that forwarded the client's X-User-Id: 1
 * untouched would return 200 too, and so would one that forwarded both values. The claim
 * is about the header that arrives. So the assertion has to be made where it arrives.
 *
 * <p>That needs something to arrive at. Every route points at a JDK
 * {@code com.sun.net.httpserver.HttpServer} - part of the JDK, no dependency - that
 * records the headers of the request it received and answers 200. It is a real HTTP hop,
 * so the assertion covers everything the gateway does to a request, its own routing
 * filters included, and nothing test-only is inserted into the filter chain.
 *
 * <p>The recorded {@link Headers} are case-insensitive, and every letter case of a name
 * lands under one key. {@code get("X-User-Id")} therefore returns every value that arrived
 * under any spelling, so a smuggled second value fails the exact-list assertions.
 *
 * <h2>Which test proves the STRIP, and which proves the outcome</h2>
 * Checked by disabling the strip and re-running (P4.2). Two of these tests failed, and the
 * other three did not - which is correct, but easy to misread:
 *
 * <ul>
 *   <li><b>Outcome only:</b> {@link #spoofedUserIdIsReplacedByTheTokens} and
 *       {@link #lowerCaseSpoofedUserIdIsReplacedToo} still pass without the strip. On a
 *       protected path the filter then SETS X-User-Id, and Spring's HttpHeaders.set is
 *       case-insensitive, so it overwrites every client spelling by itself. They prove
 *       the promise to the downstream holds. They do not prove the strip exists.</li>
 *   <li><b>The strip itself:</b> {@link #publicPathStripsAndInjectsNothing} (a public path
 *       sets nothing, so only the strip removes a spoofed header) and
 *       {@link #everySpellingAndTheRoleAreReplaced} (an X-User-* name the filter never
 *       sets, such as x-User-Anything, survives without the strip). These two failed with
 *       the strip disabled. Removing or weakening either loses the proof.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HeaderSpoofingTest {

    private static final AtomicReference<Headers> RECEIVED = new AtomicReference<>();

    private static final HttpServer DOWNSTREAM = startDownstream();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String downstream = "http://localhost:" + DOWNSTREAM.getAddress().getPort();
        registry.add("app.services.auth-service", () -> downstream);
        registry.add("app.services.event-service", () -> downstream);
        registry.add("app.services.booking-service", () -> downstream);
        registry.add("app.jwt.secret", () -> TestTokens.SECRET);
        // Rate limiting fails open against a dead Redis, so it never interferes with these,
        // and the dev Redis on 6379 is never touched.
        registry.add("spring.data.redis.port", HeaderSpoofingTest::deadPort);
        registry.add("management.server.port", () -> "0");
    }

    @Autowired
    private WebTestClient webTestClient;

    @BeforeEach
    void clear() {
        RECEIVED.set(null);
    }

    @AfterAll
    static void stopDownstream() {
        DOWNSTREAM.stop(0);
    }

    @Test
    @DisplayName("X-User-Id: 1 with a valid token for user 2 reaches the downstream as X-User-Id: 2, and only 2")
    void spoofedUserIdIsReplacedByTheTokens() {
        String token = TestTokens.mint(2L, "USER", Clock.systemUTC());

        webTestClient.get().uri("/api/bookings/1")
                .header("Authorization", "Bearer " + token)
                .header("X-User-Id", "1")
                .exchange()
                .expectStatus().isOk();

        Headers received = received();
        assertThat(received.get("X-User-Id")).containsExactly("2");
        assertThat(received.get("X-User-Role")).containsExactly("USER");
    }

    @Test
    @DisplayName("the same with LOWER-CASE x-user-id: still arrives as exactly 2")
    void lowerCaseSpoofedUserIdIsReplacedToo() {
        String token = TestTokens.mint(2L, "USER", Clock.systemUTC());

        webTestClient.get().uri("/api/bookings/1")
                .header("Authorization", "Bearer " + token)
                .header("x-user-id", "1")
                .exchange()
                .expectStatus().isOk();

        assertThat(received().get("X-User-Id")).containsExactly("2");
    }

    @Test
    @DisplayName("several spellings at once, plus a spoofed X-USER-ROLE: ADMIN, arrive as the token's identity only")
    void everySpellingAndTheRoleAreReplaced() {
        String token = TestTokens.mint(2L, "USER", Clock.systemUTC());

        webTestClient.post().uri("/api/admin/venues")
                .header("Authorization", "Bearer " + token)
                .header("X-User-Id", "1")
                .header("x-user-id", "3")
                .header("X-USER-ROLE", "ADMIN")
                .header("x-User-Anything", "smuggled")
                .header("Content-Type", "application/json")
                .bodyValue("{}")
                .exchange()
                .expectStatus().isOk();

        Headers received = received();
        assertThat(received.get("X-User-Id")).containsExactly("2");
        assertThat(received.get("X-User-Role")).containsExactly("USER");
        assertThat(userHeaderNames(received)).containsExactlyInAnyOrder("x-user-id", "x-user-role");
    }

    @Test
    @DisplayName("a PUBLIC path strips a spoofed X-User-Id too, and injects nothing")
    void publicPathStripsAndInjectsNothing() {
        webTestClient.get().uri("/api/events/1")
                .header("X-User-Id", "1")
                .header("x-user-role", "ADMIN")
                .exchange()
                .expectStatus().isOk();

        assertThat(userHeaderNames(received())).isEmpty();
    }

    @Test
    @DisplayName("Authorization is forwarded downstream unchanged - auth-service's /me needs it")
    void authorizationIsForwardedUnchanged() {
        String token = TestTokens.mint(2L, "USER", Clock.systemUTC());

        webTestClient.get().uri("/api/auth/me")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isOk();

        assertThat(received().get("Authorization")).containsExactly("Bearer " + token);
    }

    private static Headers received() {
        Headers headers = RECEIVED.get();
        assertThat(headers).as("the request must actually have reached the downstream").isNotNull();
        return headers;
    }

    private static List<String> userHeaderNames(Headers headers) {
        return headers.keySet().stream()
                .map(name -> name.toLowerCase(Locale.ROOT))
                .filter(name -> name.startsWith("x-user-"))
                .collect(Collectors.toList());
    }

    private static int deadPort() {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException ex) {
            throw new UncheckedIOException("could not find a free port", ex);
        }
    }

    private static HttpServer startDownstream() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/", exchange -> {
                Headers copy = new Headers();
                for (Map.Entry<String, List<String>> header : exchange.getRequestHeaders().entrySet()) {
                    copy.put(header.getKey(), List.copyOf(header.getValue()));
                }
                RECEIVED.set(copy);
                exchange.getRequestBody().readAllBytes();

                byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            server.start();
            return server;
        } catch (IOException ex) {
            throw new UncheckedIOException("could not start the recording downstream", ex);
        }
    }
}
