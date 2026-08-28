package com.bookmyseat.event.repository;

import com.bookmyseat.event.entity.Event;
import com.bookmyseat.event.entity.Venue;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Filters for GET /api/events, composed so that an absent parameter contributes
 * no predicate at all.
 *
 * <p>That is the point of using Specifications here rather than one JPQL string
 * with (:city IS NULL OR v.city = :city) guards: those guards sit in the WHERE
 * clause even when unused, and MySQL will not use idx_events_category or
 * idx_venues_city through them.
 */
public final class EventSpecifications {

    /** The LIKE escape character, declared once so the predicate and the escaper agree. */
    private static final char LIKE_ESCAPE = '\\';

    private EventSpecifications() {
    }

    public static Specification<Event> filter(String city, String category, String q) {
        return (root, query, cb) -> {
            Join<Event, Venue> venue = venueJoin(root, query);

            List<Predicate> predicates = new ArrayList<>();
            if (StringUtils.hasText(city)) {
                predicates.add(cb.equal(venue.get("city"), city.trim()));
            }
            if (StringUtils.hasText(category)) {
                predicates.add(cb.equal(root.get("category"), category.trim()));
            }
            if (StringUtils.hasText(q)) {
                // No lower(): the schema is utf8mb4_unicode_ci, so LIKE is already
                // case-insensitive. Wrapping the column in lower() would only make
                // the predicate non-sargable for no gain.
                predicates.add(cb.like(
                        root.get("title"),
                        "%" + escapeLike(q.trim()) + "%",
                        LIKE_ESCAPE));
            }
            return predicates.isEmpty() ? cb.conjunction() : cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    /**
     * Joins venue once and, on the content query only, fetches it.
     *
     * <p>Two things are going on. The fetch is what stops the list endpoint firing
     * one SELECT per row to resolve each event's venue. And it must be a plain join
     * on the count query: Hibernate rejects a fetch there, because a count has no
     * result entity to attach the fetched association to.
     *
     * <p>Casting the Fetch to a Join is safe on Hibernate - the returned object
     * implements both - and lets the city predicate reuse the same join rather
     * than emitting a second one.
     */
    @SuppressWarnings("unchecked")
    private static Join<Event, Venue> venueJoin(Root<Event> root, CriteriaQuery<?> query) {
        Class<?> resultType = query == null ? null : query.getResultType();
        boolean isCountQuery = Long.class.equals(resultType) || long.class.equals(resultType);

        // Via a wildcard: the String-named join/fetch overloads are typed
        // Join<Object,Object>, which does not cast to Join<Event,Venue> directly.
        // javac allows the shortcut; ecj does not, so go through Join<?, ?>.
        if (isCountQuery) {
            return (Join<Event, Venue>) (Join<?, ?>) root.join("venue", JoinType.INNER);
        }
        return (Join<Event, Venue>) (Join<?, ?>) root.fetch("venue", JoinType.INNER);
    }

    /**
     * Neutralises LIKE wildcards in user input.
     *
     * <p>Without this a caller passing q=%%% matches every row, turning a filtered
     * lookup into a full scan on demand. The escape character is doubled first, or
     * it would double-escape the escapes added on the two lines after it.
     */
    private static String escapeLike(String input) {
        String escape = String.valueOf(LIKE_ESCAPE);
        return input.replace(escape, escape + escape)
                .replace("%", escape + "%")
                .replace("_", escape + "_");
    }
}
