package com.bookmyseat.booking.client.dto;

/**
 * Result of the internal release call.
 *
 * <p>{@code released} is routinely less than {@code requested}, and is zero most of the
 * time. event-service skips any seat this booking does not own instead of refusing the
 * call, so a small number here is the normal outcome and never an error - see the release
 * endpoint's contract. Nothing should branch on it; it is for the log.
 */
public record SeatsReleasedResponse(Long showId, int requested, int released) {
}
