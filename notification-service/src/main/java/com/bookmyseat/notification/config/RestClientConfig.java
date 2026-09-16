package com.bookmyseat.notification.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * The two outbound clients. Two beans rather than one configurable client, because they are
 * not the same kind of thing: one is required and one is decoration, and keeping them
 * separate keeps that visible at the point of injection.
 */
@Configuration
@EnableConfigurationProperties({
        AuthServiceProperties.class,
        EventServiceProperties.class,
        NotificationProperties.class
})
public class RestClientConfig {

    /** auth-service. REQUIRED - see AuthClient. */
    @Bean
    public RestClient authServiceRestClient(AuthServiceProperties properties) {
        return build(properties.baseUrl(), properties.connectTimeout(), properties.readTimeout());
    }

    /** event-service. BEST-EFFORT - see EventClient. */
    @Bean
    public RestClient eventServiceRestClient(EventServiceProperties properties) {
        return build(properties.baseUrl(), properties.connectTimeout(), properties.readTimeout());
    }

    /**
     * Timeouts are always set explicitly. The default is none at all, which on the single
     * consumer thread means one hung dependency stops every confirmation in the system -
     * including the ones that do not need that dependency.
     */
    private static RestClient build(String baseUrl, Duration connectTimeout, Duration readTimeout) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeout);
        requestFactory.setReadTimeout(readTimeout);

        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
    }
}
