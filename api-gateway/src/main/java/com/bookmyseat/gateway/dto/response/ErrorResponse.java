package com.bookmyseat.gateway.dto.response;

import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The single error shape, field for field the same as auth-service, event-service and
 * booking-service: { timestamp, status, error, message, path }.
 *
 * <p>timestamp is an Instant (CLAUDE.md Timekeeping), so it serialises as ISO-8601 with a
 * trailing Z. There is deliberately no requestId: no other service returns one, and a
 * client should not have to special-case errors that happen to come from the gateway.
 */
public record ErrorResponse(Instant timestamp, int status, String error, String message, String path) {

    public static ErrorResponse of(Instant timestamp, HttpStatus status, String message, String path) {
        return new ErrorResponse(timestamp, status.value(), status.getReasonPhrase(), message, path);
    }

    /**
     * The same five fields, in the same order, for Spring's error handler, which renders
     * a Map rather than a record. Kept here so the two renderings cannot drift apart.
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("timestamp", timestamp);
        map.put("status", status);
        map.put("error", error);
        map.put("message", message);
        map.put("path", path);
        return map;
    }
}
