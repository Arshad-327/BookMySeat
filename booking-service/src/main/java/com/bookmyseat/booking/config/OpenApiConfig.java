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
                        Creates bookings against event-service.

                        WARNING: the current booking implementation is DELIBERATELY \
                        UNSAFE and contains a known race condition. It holds no lock \
                        between reading seat availability and writing the booking, and \
                        there is no unique constraint behind it, so two concurrent \
                        requests can both book the same seat and both receive 201. \
                        This is intentional and temporary - the failure is being \
                        measured under load before it is fixed. See BookingService."""));
    }
}
