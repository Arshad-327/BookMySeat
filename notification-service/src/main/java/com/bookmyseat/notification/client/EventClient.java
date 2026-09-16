package com.bookmyseat.notification.client;

import com.bookmyseat.notification.client.dto.InternalShowResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Optional;

/**
 * The BEST-EFFORT dependency: the show title, venue and seat labels that make the email
 * readable.
 *
 * <h2>Everything here is cosmetic, and the code says so by returning an Optional</h2>
 * "Seats C2, C3 for Coldplay" is a better message than "Seats #9001, #9002 for show #301".
 * It is not a more correct one. The booking is confirmed either way, and the reference and
 * the total - the parts that make the message a receipt - come from the event itself and
 * need no lookup at all.
 *
 * <p>So a confirmation that never arrives because a cosmetic lookup failed is a worse system
 * than an ugly confirmation that did. This method cannot throw; the signature is the contract.
 *
 * <h2>No classification, unlike {@link AuthClient} - and that asymmetry is the design</h2>
 * The required client examines every failure because the verdict changes what happens next:
 * retry, or give up and log. Here the answer is the same for every failure there is -
 * timeout, connection refused, 5xx, a 404 for a deleted show, a body that will not parse -
 * so there is nothing to decide and nothing to classify. Sorting failures into categories
 * that all lead to the same line would be ceremony, not rigour.
 *
 * <p>Knowing which of the two dependencies is which is the whole point of this pair of
 * classes.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class EventClient {

    private final RestClient eventServiceRestClient;

    /**
     * @return the show's details, or empty if event-service could not supply them for ANY
     *         reason. Never throws.
     */
    public Optional<InternalShowResponse> fetchShow(Long showId, List<Long> showSeatIds) {
        if (showId == null) {
            return Optional.empty();
        }
        try {
            InternalShowResponse show = eventServiceRestClient.get()
                    .uri(builder -> builder
                            .path("/api/internal/shows/{id}")
                            .queryParam("seatIds", showSeatIds == null ? List.of() : showSeatIds)
                            .build(showId))
                    .retrieve()
                    .body(InternalShowResponse.class);

            return Optional.ofNullable(show);
        } catch (RestClientException ex) {
            // WARN, not ERROR: the email is still going out. An ERROR here would page someone
            // about a subject line.
            log.warn("event-service lookup of show {} failed; the confirmation will be sent "
                            + "with ids instead of names ({}: {})",
                    showId, ex.getClass().getSimpleName(), ex.getMessage());
            return Optional.empty();
        } catch (RuntimeException ex) {
            // Deliberately broad, and the only place in this service that is. This lookup is
            // decoration, and NOTHING it can do may stop a confirmation being delivered - not
            // a parsing bug, not a null dereference in a mapping change made years from now.
            // A narrower catch would let some future exception type turn a cosmetic call back
            // into a required one by accident, which is precisely the failure this class
            // exists to rule out.
            log.warn("event-service lookup of show {} failed unexpectedly; the confirmation "
                            + "will be sent with ids instead of names ({}: {})",
                    showId, ex.getClass().getSimpleName(), ex.getMessage());
            return Optional.empty();
        }
    }
}
