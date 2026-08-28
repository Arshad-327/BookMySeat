package com.bookmyseat.booking.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(EventServiceProperties.class)
public class RestClientConfig {

    /**
     * RestClient pointed at event-service.
     *
     * <p>Timeouts are set explicitly. The default is no timeout at all, which on a
     * synchronous call from a request thread means a hung event-service would hold
     * booking-service threads open until the pool is exhausted - turning one slow
     * dependency into two dead services.
     */
    @Bean
    public RestClient eventServiceRestClient(EventServiceProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.connectTimeout());
        requestFactory.setReadTimeout(properties.readTimeout());

        return RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .build();
    }
}
