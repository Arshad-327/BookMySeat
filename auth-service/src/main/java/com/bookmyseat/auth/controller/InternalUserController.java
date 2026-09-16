package com.bookmyseat.auth.controller;

import com.bookmyseat.auth.dto.response.ErrorResponse;
import com.bookmyseat.auth.dto.response.InternalUserResponse;
import com.bookmyseat.auth.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Service-to-service endpoints. Called by notification-service, never by a browser.
 *
 * <h2>Why this exists: the cost of a thin event, paid here</h2>
 * booking.confirmed carries ids and nothing else - no email, no name (see
 * BookingConfirmedEvent in booking-service). That was chosen so personal data is never
 * copied into an append-only Kafka log and an outbox table, neither of which can purge one
 * user's record selectively.
 *
 * <p>The cost of that choice is runtime coupling: whoever consumes the event must resolve the
 * ids, and the email lives in auth_db, which no other service may read (CLAUDE.md: no service
 * reads another service's tables). This endpoint is that resolution, and it is the whole
 * reason the thin-event decision is affordable.
 *
 * <h2>MUST NOT BE ROUTED THROUGH THE GATEWAY - it publishes email addresses</h2>
 * Like event-service's InternalSeatController, this endpoint performs <b>no authentication
 * and no authorisation</b>: SecurityConfig permits /api/internal/** precisely so a service
 * can call it without minting a token for itself. Its only protection is being unreachable
 * from outside the Docker network.
 *
 * <p>So a gateway route here would let anyone on the internet enumerate every user's email
 * address by counting upwards from 1. That is not a hypothetical to be remembered: it is
 * asserted. api-gateway's RoutingTableTest has a case pinning GET /api/internal/users/1 to a
 * 404 from the gateway itself, and its javadoc carries the standing rule that every new
 * /api/internal/** endpoint gets one.
 *
 * <p>Worth knowing about the near miss: /api/shows/** <i>is</i> a routed predicate, and
 * /api/internal/shows/... escapes it only because Gateway matches a path from its start. The
 * margin between "unrouted" and "public" is one loosened predicate.
 */
@RestController
@RequestMapping("/api/internal")
@RequiredArgsConstructor
@Tag(name = "Internal", description = "Service-to-service only. Must not be routed through the gateway.")
public class InternalUserController {

    private final AuthService authService;

    @Operation(
            summary = "Resolve a user's email and name (internal)",
            description = """
                    Returns the id, email and full name for one user, and nothing else - no
                    role, no createdAt, no password hash. The narrow projection is the point:
                    this endpoint is unauthenticated, so what it can disclose is bounded by
                    what it returns.

                    Answers **404** when no such user exists, deliberately rather than the
                    401 that `/api/auth/me` gives for the same condition. A caller here
                    presents no credential, so "unauthorised" would describe nothing that
                    happened - and notification-service relies on the difference to tell a
                    deleted user (permanently undeliverable) from its own misconfiguration.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The user's email and name",
                    content = @Content(schema = @Schema(implementation = InternalUserResponse.class),
                            examples = @ExampleObject(value = """
                                    {
                                      "id": 42,
                                      "email": "arshad@example.com",
                                      "fullName": "Arshad"
                                    }"""))),
            @ApiResponse(responseCode = "404", description = "No user with that id",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(value = """
                                    {
                                      "timestamp": "2026-09-16T10:12:04.118427Z",
                                      "status": 404,
                                      "error": "Not Found",
                                      "message": "User 999 not found",
                                      "path": "/api/internal/users/999"
                                    }""")))
    })
    @GetMapping("/users/{id}")
    public ResponseEntity<InternalUserResponse> getUser(
            @Parameter(description = "User id", example = "42") @PathVariable Long id) {

        return ResponseEntity.ok(authService.findInternal(id));
    }
}
