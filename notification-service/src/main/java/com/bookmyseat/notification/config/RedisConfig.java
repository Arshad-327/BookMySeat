package com.bookmyseat.notification.config;

import org.springframework.context.annotation.Configuration;

/**
 * Redis needs no configuration here beyond spring.data.redis in application.yml.
 *
 * <p>This service stores one short string per handled eventId, so Boot's auto-configured
 * {@link org.springframework.data.redis.core.StringRedisTemplate} is exactly right: String
 * keys, String values, no serializer to choose and no JDK-serialized bytes to regret later.
 * booking-service configures a RedisTemplate of its own only because it runs Lua scripts.
 *
 * <p>The class exists to say that, rather than to leave the absence of a RedisConfig looking
 * like an oversight in a package that has one for everything else.
 */
@Configuration
public class RedisConfig {
}
