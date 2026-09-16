package com.bookmyseat.notification.client;

import com.bookmyseat.notification.client.dto.InternalUserResponse;
import com.bookmyseat.notification.exception.PermanentLookupException;
import com.bookmyseat.notification.exception.TransientLookupException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * The REQUIRED dependency: resolves a userId to the address the confirmation is sent to.
 *
 * <h2>Required means the message does not go without it</h2>
 * There is no fallback and there must not be one. An email has to be addressed to somebody;
 * an "unknown recipient" confirmation is not a degraded confirmation, it is no confirmation.
 * Contrast {@link EventClient}, whose entire failure handling is a fallback.
 *
 * <h2>Every failure is classified, because the classification changes what happens</h2>
 * That is the difference from the best-effort client, where all failures are equivalent and
 * so none is examined:
 *
 * <ul>
 *   <li><b>Connection refused, timeout, 5xx</b> - {@link TransientLookupException}. The user
 *       exists; auth-service is merely unavailable. The event must come back.</li>
 *   <li><b>404</b> - {@link PermanentLookupException}. The user is deleted. No number of
 *       retries makes a deleted row exist.</li>
 *   <li><b>401/403</b> - {@link PermanentLookupException} too, but this service is
 *       misconfigured rather than the data being odd, and the log must not confuse the two.</li>
 * </ul>
 *
 * <p>auth-service answering 404 rather than 401 for a missing user is what makes that third
 * case meaningful; it is deliberate on that side, and recorded there.
 */
@Component
@RequiredArgsConstructor
public class AuthClient {

    private final RestClient authServiceRestClient;

    /**
     * @throws TransientLookupException auth-service is unreachable or erroring - retry
     * @throws PermanentLookupException the user is gone, or this service may not ask - do not
     */
    public InternalUserResponse fetchUser(Long userId) {
        InternalUserResponse user;
        try {
            user = authServiceRestClient.get()
                    .uri("/api/internal/users/{id}", userId)
                    .retrieve()
                    .body(InternalUserResponse.class);
        } catch (HttpClientErrorException.NotFound ex) {
            throw new PermanentLookupException("user " + userId + " does not exist in auth-service", 404);
        } catch (HttpClientErrorException.Unauthorized | HttpClientErrorException.Forbidden ex) {
            throw new PermanentLookupException(
                    "auth-service refused this service's lookup of user " + userId
                            + " - notification-service is misconfigured",
                    ex.getStatusCode().value());
        } catch (HttpClientErrorException ex) {
            // Any other 4xx: a malformed request from this service. It will be malformed
            // identically next time, so retrying only blocks the partition.
            throw new PermanentLookupException(
                    "auth-service rejected the lookup of user " + userId + ": " + ex.getStatusCode(),
                    ex.getStatusCode().value());
        } catch (RestClientException ex) {
            // Connection refused, timeouts, 5xx, an unparseable body. All of these can differ
            // on the next attempt, so all of them are worth another attempt.
            throw new TransientLookupException("auth-service lookup of user " + userId + " failed", ex);
        }

        // A 200 with no body, or with no address in it, is not a usable answer. Treated as
        // transient: it is far more likely a proxy or a half-deployed instance than a real
        // user with no email, and the column is NOT NULL.
        if (user == null || user.email() == null || user.email().isBlank()) {
            throw new TransientLookupException(
                    "auth-service returned no email for user " + userId, null);
        }
        return user;
    }
}
