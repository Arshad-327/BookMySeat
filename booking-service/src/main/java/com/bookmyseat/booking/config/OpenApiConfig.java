package com.bookmyseat.booking.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI bookingServiceOpenApi() {
        return new OpenAPI().info(new Info()
                .title("BookMySeat - booking-service")
                .version("v1")
                .description("""
                        Books seats in two steps, so that no seat can be sold twice.

                        **1. Hold** - `POST /api/bookings/hold` takes an exclusive, \
                        ten-minute hold on every requested seat, all or nothing, and \
                        creates a `PENDING` booking. If any seat is already held, nothing \
                        is taken and the 409 lists every conflicting seat. An \
                        `Idempotency-Key` is required, so a retried request returns the \
                        original booking instead of creating a second one.

                        **2. Confirm** - `POST /api/bookings/{id}/confirm` checks the holds \
                        are still this booking's, marks the seats booked in event-service, \
                        and moves the booking to `CONFIRMED`. A database constraint allows \
                        only one confirmed booking per seat, whatever happens in front of it.

                        **Or cancel** - `DELETE /api/bookings/{id}` releases the seats at \
                        once. A hold that is neither confirmed nor cancelled expires on its \
                        own after ten minutes, and the booking is marked `EXPIRED`.

                        Callers are identified by the `X-User-Id` header, which is not \
                        verified yet: api-gateway will set it from a validated JWT. Until \
                        then, any caller can set it."""));
    }
}
