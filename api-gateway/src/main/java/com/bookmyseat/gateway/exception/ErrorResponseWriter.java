package com.bookmyseat.gateway.exception;

import com.bookmyseat.gateway.dto.response.ErrorResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;

/**
 * Writes the standard error body straight onto the response, for errors a filter raises
 * itself and answers without going further down the chain - the 401s and the 429s.
 *
 * <p>Errors from further in (no route, an unreachable downstream) go through
 * {@link GatewayErrorAttributes} instead. Both build the same {@link ErrorResponse}.
 */
@Component
public class ErrorResponseWriter {

    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ErrorResponseWriter(ObjectMapper objectMapper, Clock clock) {
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * @param headers extra response headers - WWW-Authenticate on a 401, Retry-After and
     *                X-RateLimit-Remaining on a 429. Empty for none.
     */
    public Mono<Void> write(ServerWebExchange exchange, HttpStatus status, String message, Map<String, String> headers) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        headers.forEach(response.getHeaders()::set);

        ErrorResponse body = ErrorResponse.of(
                Instant.now(clock), status, message, exchange.getRequest().getPath().value());

        // Serialised lazily inside the Mono, so nothing is done until the response is
        // actually subscribed to and written.
        return response.writeWith(Mono.fromCallable(() -> response.bufferFactory().wrap(serialise(body))));
    }

    private byte[] serialise(ErrorResponse body) throws JsonProcessingException {
        return objectMapper.writeValueAsBytes(body);
    }
}
