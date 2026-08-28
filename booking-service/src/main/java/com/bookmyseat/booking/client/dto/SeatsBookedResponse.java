package com.bookmyseat.booking.client.dto;

/**
 * Result of the internal booking call.
 *
 * <p>updated may be lower than requested when an id is unknown, belongs to another
 * show, or was already BOOKED. event-service does not reject that case and neither
 * does this service - see BookingService for why that is deliberate for now.
 */
public record SeatsBookedResponse(Long showId, int requested, int updated) {
}
