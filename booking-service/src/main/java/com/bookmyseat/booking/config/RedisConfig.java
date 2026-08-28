package com.bookmyseat.booking.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scripting.support.ResourceScriptSource;

import java.util.List;

/**
 * The two Lua scripts, loaded once at startup.
 *
 * <p><b>Once, not per call, and that is a correctness matter as well as a
 * performance one.</b> DefaultRedisScript computes the script's SHA1 on first use
 * and caches it, so subsequent executions go out as EVALSHA - the digest only,
 * not the script body. Building a new DefaultRedisScript per request would
 * re-read the file from the classpath and re-hash it on every booking, and would
 * push the whole script text over the wire each time.
 *
 * <p>Spring Data Redis handles the one failure mode this creates on its own: if
 * Redis has been restarted or its script cache flushed, EVALSHA comes back
 * NOSCRIPT and the template retries with a full EVAL, which re-registers the
 * script. Nothing here needs to detect that.
 *
 * <p>StringRedisTemplate is not declared here - Spring Boot's RedisAutoConfiguration
 * already provides one, and it is the right template for this job: both scripts
 * deal purely in String keys and String values, so the plain String serializer
 * keeps what is stored in Redis identical to what the Lua sees. A JDK-serialized
 * template would write binary payloads that {@code owner == booking_id} in
 * release_seats.lua could never match.
 */
@Configuration
@EnableConfigurationProperties(SeatHoldProperties.class)
public class RedisConfig {

    /**
     * hold_seats.lua - returns the conflicting keys, so the result type is a List.
     *
     * <p>Raw List rather than {@code List<String>}: the result type tells Spring
     * which Redis reply converter to use, and generics are erased by the time that
     * decision is made. Declaring List.class is the documented way to read a
     * multi-bulk reply.
     */
    @Bean
    @SuppressWarnings("rawtypes")
    public RedisScript<List> holdSeatsScript() {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource("scripts/hold_seats.lua")));
        script.setResultType(List.class);
        return script;
    }

    /** release_seats.lua - returns how many keys it deleted, so an integer reply. */
    @Bean
    public RedisScript<Long> releaseSeatsScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource("scripts/release_seats.lua")));
        script.setResultType(Long.class);
        return script;
    }
}
