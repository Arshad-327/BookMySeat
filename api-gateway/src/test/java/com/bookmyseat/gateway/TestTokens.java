package com.bookmyseat.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Access tokens for gateway tests, minted the way auth-service's JwtService mints them:
 * subject = user id, a role claim, HS256 over the secret's UTF-8 bytes, 15-minute TTL.
 * Times come from a Clock, so a token can be minted in the past.
 */
final class TestTokens {

    static final String SECRET = "gateway-test-secret-of-sufficient-length-0123456789";

    private static final Duration TTL = Duration.ofMinutes(15);
    private static final ObjectMapper JSON = new ObjectMapper();

    private TestTokens() {
    }

    static String mint(long userId, String role, Clock clock) {
        return mint(userId, role, clock, SECRET);
    }

    static String mint(long userId, String role, Clock clock, String secret) {
        Instant now = clock.instant();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("email", "user" + userId + "@example.com")
                .claim("role", role)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(TTL)))
                .signWith(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8)), Jwts.SIG.HS256)
                .compact();
    }

    /** The same token with its payload's subject rewritten, and its original signature kept. */
    static String withSubjectForged(String token, long forgedUserId) {
        String[] parts = token.split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        String forged = payload.replaceFirst("\"sub\":\"\\d+\"", "\"sub\":\"" + forgedUserId + "\"");
        assertThat(forged).as("the forgery must actually change the payload").isNotEqualTo(payload);
        return parts[0] + "." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(forged.getBytes(StandardCharsets.UTF_8)) + "." + parts[2];
    }

    /**
     * The same token with one character in the MIDDLE of its signature changed. Not the
     * last character: in base64url the final character can carry unused padding bits, so
     * changing it may leave the decoded signature bytes, and so the verdict, untouched.
     */
    static String withSignatureTampered(String token) {
        int dot = token.lastIndexOf('.');
        char[] chars = token.toCharArray();
        int middle = dot + (token.length() - dot) / 2;
        chars[middle] = chars[middle] == 'A' ? 'B' : 'A';
        return new String(chars);
    }

    /** An unsigned token claiming to be user 1: header "alg": "none", empty signature. */
    static String unsigned() {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        String header = encoder.encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
        String body = encoder.encodeToString(
                "{\"sub\":\"1\",\"role\":\"ADMIN\",\"exp\":4102444800}".getBytes(StandardCharsets.UTF_8));
        return header + "." + body + ".";
    }

    /**
     * Asserts the standard error shape: exactly timestamp, status, error, message and path,
     * in that order, with an ISO-8601 timestamp ending in Z - and no requestId.
     */
    static JsonNode assertStandardErrorShape(byte[] body, int expectedStatus, String expectedPath) throws Exception {
        JsonNode json = JSON.readTree(body);

        List<String> fields = new ArrayList<>();
        json.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactly("timestamp", "status", "error", "message", "path");

        String timestamp = json.get("timestamp").asText();
        assertThat(timestamp).endsWith("Z");
        assertThat(Instant.from(DateTimeFormatter.ISO_INSTANT.parse(timestamp))).isNotNull();

        assertThat(json.get("status").asInt()).isEqualTo(expectedStatus);
        assertThat(json.get("path").asText()).isEqualTo(expectedPath);
        assertThat(json.get("message").asText()).isNotBlank();
        return json;
    }
}
