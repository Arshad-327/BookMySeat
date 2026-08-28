package com.bookmyseat.event.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI eventServiceOpenApi() {
        return new OpenAPI().info(new Info()
                .title("BookMySeat - event-service")
                .version("v1")
                .description("""
                        Read-side catalogue: events, venues, shows and seat maps.

                        Seat availability here has exactly two states, AVAILABLE and
                        BOOKED. A seat being held by another user during checkout still
                        reads AVAILABLE from this service: holds are Redis keys with a
                        10-minute TTL and are never written to the database. Treat this
                        seat map as a display snapshot, not as a reservation."""));
    }
}
