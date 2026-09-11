package com.bookmyseat.booking.client.dto;

/**
 * Result of the internal booking call.
 *
 * <p>On success updated always equals requested: event-service rejects a request it
 * cannot apply in full (404 or 409) rather than returning a smaller number.
 */
public record SeatsBookedResponse(Long showId, int requested, int updated) {
}
