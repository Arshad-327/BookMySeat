package com.bookmyseat.gateway.exception;

import com.bookmyseat.gateway.dto.response.ErrorResponse;
import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.boot.web.reactive.error.DefaultErrorAttributes;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;

import java.net.ConnectException;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;

/**
 * Replaces Spring's default error body for every error the gateway's error handler
 * renders: a path no route matches (404), an unreachable downstream (5xx), anything else.
 *
 * <p>Spring's default is { timestamp, path, status, error, requestId }, with the timestamp
 * a java.util.Date rendered as "+00:00". That breaks CLAUDE.md Timekeeping and matches no
 * other service. This produces the standard {@link ErrorResponse} instead, timestamp an
 * Instant from the injected Clock.
 *
 * <p><b>5xx messages are the reason phrase and nothing more.</b> Spring's message for a dead
 * downstream is the connection error, which names an internal host and port - for
 * example "Connection refused: localhost/127.0.0.1:8083". The public port must not
 * describe the internal network. The full cause is still logged server-side by Spring's
 * error handler.
 *
 * <h2>The status is decided here too, in one case</h2>
 * Everywhere else the status is Spring's own resolution. For a downstream that cannot be
 * CONNECTED to, Spring's resolution is wrong, and this corrects it.
 *
 * <p>Spring Cloud Gateway does not map a failed connection to anything. The Netty client's
 * {@code AnnotatedConnectException} - a {@link ConnectException} - propagates out of the
 * routing filter as an ordinary exception, and Spring Boot resolves any exception that is
 * not a ResponseStatusException to <b>500 Internal Server Error</b>. A 500 says the
 * gateway broke. It did not: it is running, and the service it forwards to is not there.
 * So a connection failure is answered <b>503 Service Unavailable</b>.
 *
 * <p>This is the status of the RESPONSE and not only a field in its body: Boot's
 * DefaultErrorWebExceptionHandler takes the HTTP status from the "status" attribute this
 * method returns. DownstreamUnreachableTest asserts on the response's own status line.
 *
 * <h2>What is deliberately NOT mapped to 503</h2>
 * A downstream that accepts the connection and never answers is already
 * <b>504 Gateway Timeout</b>: Spring Cloud Gateway turns its response timeout into a
 * ResponseStatusException itself, so it arrives here with the right status and is left
 * alone. The two are different facts and stay different statuses. "Nobody is there" means
 * the request was certainly not received; "somebody is there and did not answer" means it
 * may well have been - which matters to a client deciding whether a POST is safe to
 * repeat.
 */
@Component
public class GatewayErrorAttributes extends DefaultErrorAttributes {

    private final Clock clock;

    public GatewayErrorAttributes(Clock clock) {
        this.clock = clock;
    }

    @Override
    public Map<String, Object> getErrorAttributes(ServerRequest request, ErrorAttributeOptions options) {
        // The status is taken from Spring's own resolution, except for a failed connection.
        Map<String, Object> defaults = super.getErrorAttributes(request, ErrorAttributeOptions.defaults());
        HttpStatus status = HttpStatus.resolve((int) defaults.get("status"));
        if (status == null) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        if (isConnectionFailure(getError(request))) {
            status = HttpStatus.SERVICE_UNAVAILABLE;
        }

        String message = status == HttpStatus.NOT_FOUND
                ? "No endpoint is available at this path"
                : status.getReasonPhrase();

        return ErrorResponse.of(Instant.now(clock), status, message, request.path()).toMap();
    }

    /**
     * Whether the error is a connection that could not be made: refused, or timed out
     * before it was established.
     *
     * <p>{@link ConnectException} covers both. Netty's AnnotatedConnectException (connection
     * refused) and ConnectTimeoutException (no answer to the connection attempt within
     * spring.cloud.gateway.httpclient.connect-timeout) each extend it. The cause chain is
     * walked because the exception is not always the outermost one, and the depth is
     * bounded so a cause that points back at itself cannot spin.
     */
    private static boolean isConnectionFailure(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < 10; depth++) {
            if (current instanceof ConnectException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
