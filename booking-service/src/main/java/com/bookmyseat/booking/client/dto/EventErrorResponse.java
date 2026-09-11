package com.bookmyseat.booking.client.dto;

/**
 * The part of event-service's standard error body this service reads: the message.
 * The other fields (timestamp, status, error, path) are ignored.
 */
public record EventErrorResponse(String message) {
}
