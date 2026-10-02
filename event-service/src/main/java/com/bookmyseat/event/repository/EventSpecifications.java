package com.bookmyseat.event.repository;

import com.bookmyseat.event.entity.Event;
import com.bookmyseat.event.entity.Show;
import com.bookmyseat.event.entity.Venue;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils;

import java.time.Instant;
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
 *
 * <h2>One predicate is NOT optional: the event must have an upcoming show</h2>
 * Every other filter here is a parameter the caller may leave out. This one is always
 * applied. The list used to take no position on shows at all - it never looked at the
 * table - while GET /api/events/{id} deliberately returns only shows starting at or after
 * the current instant. So the browse page offered cards whose detail page had nothing to
 * book. The list now agrees with the detail page: same comparison, same boundary
 * ({@code startsAt >= :now}, inclusive), same Clock.
 *
 * <p>The detail endpoint is deliberately NOT filtered the same way. A deep link to an
 * event whose shows have all happened still resolves, with an empty upcomingShows.
 *
 * <h2>THE INTERLOCK - read this before removing or loosening the filter</h2>
 * {@code EventSummaryResponse.nextShowStartsAt} and {@code fromPrice} are documented as
 * NEVER NULL, and clients render them unconditionally. That is true only because of the
 * EXISTS predicate below: an event is listed if and only if it has an upcoming show, so
 * the summary statement in {@code EventService.findEvents} always has a row for it.
 * <b>Remove this filter, or make it optional, and you have made two non-null fields
 * nullable</b> - and {@code EventService} will say so by throwing, because it treats a
 * listed event with no summary as a broken invariant rather than as a blank date.
 */
public final class EventSpecifications {

    /** The LIKE escape character, declared once so the predicate and the escaper agree. */
    private static final char LIKE_ESCAPE = '\\';

    private EventSpecifications() {
    }

    /**
     * @param now from the injected Clock, read ONCE by the caller and shared with the summary
     *            statement that follows. Never SQL NOW() (CLAUDE.md Timekeeping): this is
     *            exactly the kind of comparison that rule exists for, and a parameter is
     *            what lets a test put a show on either side of it
     */
    public static Specification<Event> filter(String city, String category, String q, Instant now) {
        return (root, query, cb) -> {
            Join<Event, Venue> venue = venueJoin(root, query);

            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.exists(upcomingShow(root, query, cb, now)));
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
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    /**
     * {@code EXISTS (SELECT 1 FROM shows s WHERE s.event_id = e.id AND s.starts_at >= :now)}.
     *
     * <p>A subquery in the WHERE clause, not a join to shows: a join would return one row per
     * upcoming show, so an event with three shows would appear three times and the page
     * would need DISTINCT - which changes the count as well as the content.
     *
     * <p>Spring Data builds the COUNT query from this same Specification, so the predicate
     * lands in both statements without being written twice. That is what keeps
     * totalElements and totalPages honest: a filter applied to the content alone would
     * produce a pagination control promising pages that come back empty.
     */
    private static Subquery<Integer> upcomingShow(
            Root<Event> root, CriteriaQuery<?> query, CriteriaBuilder cb, Instant now) {
        Subquery<Integer> subquery = query.subquery(Integer.class);
        Root<Show> show = subquery.from(Show.class);
        return subquery.select(cb.literal(1)).where(
                cb.equal(show.get("event"), root),
                cb.greaterThanOrEqualTo(show.get("startsAt"), now));
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
