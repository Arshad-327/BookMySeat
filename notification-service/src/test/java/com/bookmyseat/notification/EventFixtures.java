package com.bookmyseat.notification;

import com.bookmyseat.notification.client.dto.InternalSeatLabelResponse;
import com.bookmyseat.notification.client.dto.InternalShowResponse;
import com.bookmyseat.notification.client.dto.InternalUserResponse;
import com.bookmyseat.notification.dto.event.BookingConfirmedEvent;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The event and the lookup responses the tests work from, matching the shapes booking-service,
 * auth-service and event-service actually produce.
 */
public final class EventFixtures {

    public static final Long BOOKING_ID = 6L;
    public static final Long USER_ID = 42L;
    public static final Long SHOW_ID = 301L;
    public static final List<Long> SEAT_IDS = List.of(9001L, 9002L);
    public static final BigDecimal TOTAL = new BigDecimal("900.00");

    private EventFixtures() {
    }

    /** A fresh event, with a new eventId each time so tests never collide in Redis. */
    public static BookingConfirmedEvent event() {
        return event(UUID.randomUUID().toString());
    }

    public static BookingConfirmedEvent event(String eventId) {
        return new BookingConfirmedEvent(
                eventId,
                "BookingConfirmed",
                BOOKING_ID,
                USER_ID,
                SHOW_ID,
                SEAT_IDS,
                TOTAL,
                Instant.parse("2026-09-16T06:30:00Z"));
    }

    public static InternalUserResponse user() {
        return new InternalUserResponse(USER_ID, "arshad@example.com", "Arshad");
    }

    /** The show as event-service returns it: 2026-09-14T13:00:00Z is 6:30 PM in Asia/Kolkata. */
    public static InternalShowResponse show() {
        return new InternalShowResponse(
                SHOW_ID,
                "Coldplay - Music of the Spheres",
                "DY Patil Stadium",
                Instant.parse("2026-09-14T13:00:00Z"),
                List.of(new InternalSeatLabelResponse(9001L, "C2"),
                        new InternalSeatLabelResponse(9002L, "C3")));
    }
}
