package com.bookmyseat.gateway;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the gateway answers when the service behind a route cannot be reached.
 *
 * <h2>The defect this pins</h2>
 * With event-service stopped, the gateway answered <b>500 Internal Server Error</b>. A 500
 * says the gateway itself broke. It had not: it was working, and the thing it forwards to
 * was not there. Found by stopping event-service under a browser and reading the status of
 * the seat-map poll.
 *
 * <p>Spring Cloud Gateway does not map a failed connection to anything. The Netty client's
 * {@code ConnectException} reaches Spring Boot's error handler as an ordinary exception,
 * and an ordinary exception is a 500. The mapping is done in
 * {@code GatewayErrorAttributes}; see there.
 *
 * <h2>Two different failures, and they already differed</h2>
 * <ul>
 *   <li><b>Refused</b> - nothing is listening. Was 500, is now 503.
 *   <li><b>Hung</b> - something accepts the connection and never answers. This was ALREADY
 *       right and is pinned here so it stays so: Spring Cloud Gateway itself turns its
 *       response timeout into 504 Gateway Timeout. It is deliberately not folded into 503 -
 *       "nobody is there" and "somebody is there and is not answering" are different facts,
 *       and a 504 after a POST means the request may well have been received.
 * </ul>
 *
 * <p>{@code RoutingTableTest} asserts only that a routed path to a dead port is "some 5xx",
 * because all it is measuring is that the route exists. This class is where the status
 * itself is the subject.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DownstreamUnreachableTest {

    /** Reserved and released at class load, so nothing is listening on it: connection refused. */
    private static final int DEAD_PORT = unusedPort();

    /** Accepts every connection and never writes a byte: the hung service. */
    private static final ServerSocket SILENT = silentServer();
    private static final List<Socket> ACCEPTED = new CopyOnWriteArrayList<>();

    @DynamicPropertySource
    static void routes(DynamicPropertyRegistry registry) {
        // event-service is gone; auth-service is there and says nothing.
        registry.add("app.services.event-service", () -> "http://localhost:" + DEAD_PORT);
        registry.add("app.services.auth-service", () -> "http://localhost:" + SILENT.getLocalPort());
        registry.add("app.services.booking-service", () -> "http://localhost:" + DEAD_PORT);
        // One second instead of the ten in application.yml, so the hung case costs the suite
        // a second. The property is the same one; only its value is shortened.
        registry.add("spring.cloud.gateway.httpclient.response-timeout", () -> "1s");
        registry.add("app.jwt.secret", () -> TestTokens.SECRET);
        registry.add("spring.data.redis.port", () -> DEAD_PORT);
        registry.add("management.server.port", () -> "0");
    }

    @AfterAll
    static void stopSilentServer() throws IOException {
        for (Socket socket : ACCEPTED) {
            socket.close();
        }
        SILENT.close();
    }

    @Autowired
    private WebTestClient webTestClient;

    @Test
    @DisplayName("a downstream that refuses the connection is 503 in the standard shape - not 500, which would say the gateway broke")
    void refusedConnectionIsServiceUnavailable() throws Exception {
        EntityExchangeResult<byte[]> result = exchange(HttpMethod.GET, "/api/shows/1/seats");

        assertThat(result.getStatus().value()).isEqualTo(503);
        var body = TestTokens.assertStandardErrorShape(result.getResponseBodyContent(), 503, "/api/shows/1/seats");
        assertThat(body.get("error").asText()).isEqualTo("Service Unavailable");
        // The message must not describe the internal network. Spring's own message for this
        // failure is "Connection refused: localhost/127.0.0.1:<port>".
        assertThat(body.get("message").asText())
                .doesNotContain("localhost")
                .doesNotContain("127.0.0.1")
                .doesNotContain(String.valueOf(DEAD_PORT));
    }

    @Test
    @DisplayName("the same for a protected route: an authenticated request to a dead booking-service is 503")
    void refusedConnectionBehindTheJwtFilterIsServiceUnavailable() throws Exception {
        EntityExchangeResult<byte[]> result = exchange(HttpMethod.POST, "/api/bookings/1/confirm");

        assertThat(result.getStatus().value()).isEqualTo(503);
        TestTokens.assertStandardErrorShape(result.getResponseBodyContent(), 503, "/api/bookings/1/confirm");
    }

    @Test
    @DisplayName("a downstream that accepts and never answers is 504 in the standard shape - already so, and kept apart from 503")
    void hungDownstreamIsGatewayTimeout() throws Exception {
        EntityExchangeResult<byte[]> result = exchange(HttpMethod.POST, "/api/auth/login");

        assertThat(result.getStatus().value()).isEqualTo(504);
        var body = TestTokens.assertStandardErrorShape(result.getResponseBodyContent(), 504, "/api/auth/login");
        assertThat(body.get("error").asText()).isEqualTo("Gateway Timeout");
    }

    private EntityExchangeResult<byte[]> exchange(HttpMethod method, String path) {
        return webTestClient
                .mutate().responseTimeout(Duration.ofSeconds(15)).build()
                .method(method).uri(path)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + TestTokens.mint(2L, "USER", Clock.systemUTC()))
                .exchange()
                .expectBody()
                .returnResult();
    }

    private static int unusedPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static ServerSocket silentServer() {
        try {
            ServerSocket server = new ServerSocket(0);
            Thread acceptor = new Thread(() -> {
                while (!server.isClosed()) {
                    try {
                        // Held open and never read from or written to.
                        ACCEPTED.add(server.accept());
                    } catch (IOException closed) {
                        return;
                    }
                }
            }, "silent-downstream");
            acceptor.setDaemon(true);
            acceptor.start();
            return server;
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
