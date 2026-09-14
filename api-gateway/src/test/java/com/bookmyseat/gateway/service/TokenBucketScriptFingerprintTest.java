package com.bookmyseat.gateway.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the token-bucket Lua script to the one the public rate-limiter-demo repo serves.
 *
 * <p>The expected SHA-1 was computed from github.com/Arshad-327/rate-limiter at commit 6f30c29,
 * independently of this module: the SCRIPT text block was extracted from that commit's
 * TokenBucketRateLimiter.java with git show, compiled with javac (so the same text-block
 * indentation rules apply), and hashed as UTF-8. That gave 79c9edb17b509dfd711ec525f35f122f3376fec3,
 * 867 characters.
 *
 * <p>SHA-1 is not an arbitrary choice: it is how Redis itself identifies a script (EVALSHA). If
 * this test passes, Redis runs exactly the script an interviewer reads on GitHub. If someone
 * "tidies" the text block - re-indents it, trims a blank line, changes a quote - this fails.
 */
class TokenBucketScriptFingerprintTest {

    static final String PUBLIC_SCRIPT_SHA1 = "79c9edb17b509dfd711ec525f35f122f3376fec3";

    @Test
    @DisplayName("the token-bucket script is byte-for-byte the public repo's: same SHA-1 Redis uses to identify it")
    void scriptMatchesThePublicCommit() {
        @SuppressWarnings("rawtypes")
        DefaultRedisScript<List> script = new DefaultRedisScript<>(TokenBucketRateLimiter.SCRIPT, List.class);

        assertThat(script.getSha1()).isEqualTo(PUBLIC_SCRIPT_SHA1);
        assertThat(TokenBucketRateLimiter.SCRIPT).hasSize(867);
    }
}
