package com.bookmyseat.gateway.exception;

import com.bookmyseat.gateway.dto.response.ErrorResponse;
import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.boot.web.reactive.error.DefaultErrorAttributes;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;

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
 */
@Component
public class GatewayErrorAttributes extends DefaultErrorAttributes {

    private final Clock clock;

    public GatewayErrorAttributes(Clock clock) {
        this.clock = clock;
    }

    @Override
    public Map<String, Object> getErrorAttributes(ServerRequest request, ErrorAttributeOptions options) {
        // Only the status is taken from Spring's own resolution; everything else is ours.
        Map<String, Object> defaults = super.getErrorAttributes(request, ErrorAttributeOptions.defaults());
        HttpStatus status = HttpStatus.resolve((int) defaults.get("status"));
        if (status == null) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }

        String message = status == HttpStatus.NOT_FOUND
                ? "No endpoint is available at this path"
                : status.getReasonPhrase();

        return ErrorResponse.of(Instant.now(clock), status, message, request.path()).toMap();
    }
}
