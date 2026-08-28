package com.bookmyseat.auth.dto.response;

import java.time.Instant;

/**
 * The single error shape for this service. Produced by GlobalExceptionHandler
 * and, for failures inside the security filter chain, by SecurityConfig's
 * entry point and access-denied handler.
 *
 * timestamp is an Instant (CLAUDE.md Timekeeping), so it serialises with a
 * trailing Z and is unambiguous to any client.
 */
public record ErrorResponse(
        Instant timestamp,
        int status,
        String error,
        String message,
        String path
) {
}
