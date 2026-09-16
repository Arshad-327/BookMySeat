package com.bookmyseat.auth.dto.response;

/**
 * The user projection served to other services by GET /api/internal/users/{id}.
 *
 * <h2>Why this is not {@link UserResponse}</h2>
 * It would have been one line to reuse the existing record, and that is exactly the change
 * this type exists to prevent. {@code UserResponse} also carries the role and createdAt, and
 * this endpoint is <b>unauthenticated</b> - it trusts that only the Docker network can reach
 * it. Reuse would mean that every field ever added to the user-facing response is
 * automatically also published to an endpoint with no caller check, by nobody's decision.
 *
 * <p>A separate record makes the exposure a deliberate list rather than an inherited one:
 * adding a field here is a visible edit to a file whose whole purpose is minimal exposure,
 * and InternalUserEndpointTest asserts the serialised body has exactly these three fields.
 *
 * <p>Three fields, and why each is needed: the email is the recipient, the name is the
 * greeting, and the id is echoed so a caller can match a response to the request it made.
 * Nothing here is chosen for convenience.
 *
 * @param id       the user id, as requested
 * @param email    the recipient address
 * @param fullName may be null - the column is nullable, and callers must render a greeting
 *                 without it rather than assuming it is present
 */
public record InternalUserResponse(
        Long id,
        String email,
        String fullName
) {
}
