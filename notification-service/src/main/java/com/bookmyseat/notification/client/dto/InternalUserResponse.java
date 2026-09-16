package com.bookmyseat.notification.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * auth-service's GET /api/internal/users/{id} response.
 *
 * @param fullName may be null - the column is nullable, so the greeting must cope
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record InternalUserResponse(Long id, String email, String fullName) {
}
