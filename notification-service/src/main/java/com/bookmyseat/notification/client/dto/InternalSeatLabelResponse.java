package com.bookmyseat.notification.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** One seat's printable label, e.g. {@code {"id": 9001, "label": "C2"}}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record InternalSeatLabelResponse(Long id, String label) {
}
