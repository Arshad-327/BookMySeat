package com.bookmyseat.event.config;

import com.bookmyseat.event.dto.request.CreateEventRequest;
import com.bookmyseat.event.dto.request.CreateShowRequest;
import com.bookmyseat.event.dto.request.CreateVenueRequest;
import com.bookmyseat.event.dto.request.GenerateSeatsRequest;
import com.bookmyseat.event.entity.Event;
import com.bookmyseat.event.entity.Venue;
import com.bookmyseat.event.repository.EventRepository;
import com.bookmyseat.event.repository.SeatRepository;
import com.bookmyseat.event.repository.ShowRepository;
import com.bookmyseat.event.repository.VenueRepository;
import com.bookmyseat.event.service.AdminEventService;
import com.bookmyseat.event.service.AdminShowService;
import com.bookmyseat.event.service.AdminVenueService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Seeds a usable demo dataset: one venue with rows A-F of 10 seats, two events,
 * three shows, and therefore 3 * 60 = 180 show_seats.
 *
 * <h2>All or nothing, in two separate senses</h2>
 * Both matter, and a seeder with only the first is still able to leave a confusing
 * state behind.
 *
 * <p><b>Atomic writes.</b> {@link #run} is @Transactional, so a failure anywhere
 * rolls the whole seed back and an interrupted run leaves no rows at all. Verified
 * by injecting a throw after the final insert: every table came back empty.
 *
 * <p><b>One skip decision.</b> Whether to seed is decided exactly once, up front,
 * by {@link #seedState()}. An earlier version asked a separate question per step -
 * does this venue exist, does it have seats, does this event exist - which meant
 * pre-existing rows sharing a natural key could be adopted piecemeal: a venue
 * called "Phoenix Arena" left over from something else would be reused, seat
 * generation skipped, and the run would finish "successfully" with 6 seats instead
 * of 60 and no error anywhere. The transaction cannot help with that, because
 * nothing failed. So the decision is taken once and applies to everything.
 *
 * <p>A partial or foreign dataset is therefore reported rather than absorbed: the
 * seeder fails loudly and names the fix, instead of quietly producing a half-seeded
 * database that only surfaces later as a wrong seat count.
 *
 * <h2>Why show times are relative</h2>
 * Seeded shows are dated from the injected Clock so the demo data is always
 * upcoming (CLAUDE.md Timekeeping). That is also why completeness is judged by
 * "does this event have any show", never by matching an exact instant - a second
 * run on a later day would compute different instants and match nothing.
 *
 * <h2>Why it goes through the admin services</h2>
 * It calls AdminVenueService/AdminEventService/AdminShowService rather than the
 * repositories, so seeded data passes the same guards as anything created over
 * HTTP - including the "no show without seats" check. A seeder writing directly to
 * repositories would be a second way to create data, free to produce states the
 * API forbids. It bypasses only AdminRoleInterceptor, which is an HTTP concern.
 */
@Component
@Profile("demo")
@RequiredArgsConstructor
@Slf4j
public class DemoDataSeeder implements ApplicationRunner {

    private static final String VENUE_NAME = "Phoenix Arena";
    private static final String VENUE_CITY = "Bengaluru";
    private static final String VENUE_ADDRESS = "42 MG Road, Bengaluru 560001";

    private static final List<String> ROWS = List.of("A", "B", "C", "D", "E", "F");
    private static final int SEATS_PER_ROW = 10;
    private static final long EXPECTED_SEATS = (long) ROWS.size() * SEATS_PER_ROW;

    private static final String EVENT_ONE = "Coldplay - Music of the Spheres";
    private static final String EVENT_TWO = "Indie Night Vol. 7";

    /** Shows land at 18:30 UTC, this many days out. First two for event one, last for event two. */
    private static final long[] SHOW_DAYS_OUT = {7, 14, 21};
    private static final long SHOW_HOUR_UTC = 18;
    private static final long SHOW_MINUTE_UTC = 30;

    private static final BigDecimal PRICE_ONE = new BigDecimal("450.00");
    private static final BigDecimal PRICE_TWO = new BigDecimal("300.00");

    private final VenueRepository venueRepository;
    private final EventRepository eventRepository;
    private final ShowRepository showRepository;
    private final SeatRepository seatRepository;

    private final AdminVenueService adminVenueService;
    private final AdminEventService adminEventService;
    private final AdminShowService adminShowService;

    /** CLAUDE.md Timekeeping: seeded instants come from the injected Clock. */
    private final Clock clock;

    /** What the database looks like before this run writes anything. */
    private enum SeedState {
        /** No demo data present. Seed everything. */
        ABSENT,
        /** The complete expected dataset is already there. Do nothing. */
        COMPLETE,
        /** Some of it is there, or something else owns the same natural keys. Refuse. */
        PARTIAL
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        SeedState state = seedState();

        if (state == SeedState.COMPLETE) {
            log.info("demo data already present and complete - nothing to do");
            return;
        }
        if (state == SeedState.PARTIAL) {
            // Deliberately fatal. Seeding on top would interleave two datasets, and
            // skipping would hide the problem until someone wondered why a venue has
            // the wrong number of seats.
            throw new IllegalStateException(
                    "event_db holds a partial or unrecognised demo dataset: some of the expected "
                            + "venue, seats, events or shows are present but not all of them. "
                            + "Refusing to seed on top of it - see the ERROR line above for exactly "
                            + "what was found. Clear the data and start the demo profile again:\n"
                            + "  docker exec bookmyseat-mysql mysql -uroot -proot event_db -e "
                            + "\"SET FOREIGN_KEY_CHECKS=0; TRUNCATE show_seats; TRUNCATE seats; "
                            + "TRUNCATE shows; TRUNCATE events; TRUNCATE venues; "
                            + "SET FOREIGN_KEY_CHECKS=1;\"");
        }

        log.info("demo profile active - seeding event_db");

        // Past the gate, every step is unconditional. Nothing below re-checks whether
        // its own rows exist; that question was answered once, above.
        Venue venue = createVenue();
        int seats = createSeats(venue);

        Event eventOne = createEvent(EVENT_ONE,
                "The Music of the Spheres world tour, live in India.", "CONCERT",
                "https://cdn.bookmyseat.local/posters/coldplay.jpg", venue);
        Event eventTwo = createEvent(EVENT_TWO,
                "Six bands, one stage.", "CONCERT", null, venue);

        int showSeats = createShows(eventOne, PRICE_ONE, 0, 2)
                + createShows(eventTwo, PRICE_TWO, 2, 3);

        log.info("demo seed complete: 1 venue, {} seats, 2 events, {} shows, {} show_seats",
                seats, SHOW_DAYS_OUT.length, showSeats);
    }

    /**
     * Classifies the database in one pass, before anything is written.
     *
     * <p>Every element of the expected dataset is checked, and the answer is only
     * ABSENT or COMPLETE when they all agree. Any disagreement is PARTIAL - which
     * covers a half-finished seed, a hand-edited database, and unrelated rows that
     * happen to share a name with the demo data.
     */
    private SeedState seedState() {
        List<String> present = new ArrayList<>();
        List<String> missing = new ArrayList<>();

        Optional<Venue> venue = venueRepository.findByName(VENUE_NAME);
        record(venue.isPresent(), "venue '" + VENUE_NAME + "'", present, missing);

        // Seat count, not mere existence: a venue carrying the wrong number of seats
        // is exactly the silent half-seeded state this method exists to catch.
        if (venue.isPresent()) {
            long seatCount = seatRepository.countByVenueId(venue.get().getId());
            record(seatCount == EXPECTED_SEATS,
                    EXPECTED_SEATS + " seats (found " + seatCount + ")", present, missing);
        } else {
            missing.add(EXPECTED_SEATS + " seats");
        }

        for (String title : List.of(EVENT_ONE, EVENT_TWO)) {
            Optional<Event> event = eventRepository.findByTitle(title);
            record(event.isPresent(), "event '" + title + "'", present, missing);
            if (event.isPresent()) {
                record(showRepository.existsByEventId(event.get().getId()),
                        "shows for '" + title + "'", present, missing);
            } else {
                missing.add("shows for '" + title + "'");
            }
        }

        if (missing.isEmpty()) {
            return SeedState.COMPLETE;
        }
        if (present.isEmpty()) {
            return SeedState.ABSENT;
        }
        log.error("partial demo dataset. present: {} | missing: {}", present, missing);
        return SeedState.PARTIAL;
    }

    private static void record(boolean found, String what, List<String> present, List<String> missing) {
        (found ? present : missing).add(what);
    }

    private Venue createVenue() {
        Long id = adminVenueService.createVenue(
                new CreateVenueRequest(VENUE_NAME, VENUE_CITY, VENUE_ADDRESS)).id();
        log.info("created venue '{}' (id={})", VENUE_NAME, id);
        // Re-read so later steps hold a managed entity, not just the response id.
        return venueRepository.findById(id).orElseThrow();
    }

    private int createSeats(Venue venue) {
        int created = adminVenueService.generateSeats(venue.getId(),
                new GenerateSeatsRequest(ROWS, SEATS_PER_ROW, "REGULAR")).seatsCreated();
        log.info("generated {} seats for venue {}", created, venue.getId());
        return created;
    }

    private Event createEvent(String title, String description, String category,
                              String posterUrl, Venue venue) {
        Long id = adminEventService.createEvent(new CreateEventRequest(
                title, description, category, posterUrl, venue.getId())).id();
        log.info("created event '{}' (id={})", title, id);
        return eventRepository.findById(id).orElseThrow();
    }

    /** Creates shows at SHOW_DAYS_OUT[fromIndex..toIndex). Returns show_seats rows written. */
    private int createShows(Event event, BigDecimal basePrice, int fromIndex, int toIndex) {
        int showSeats = 0;
        for (int i = fromIndex; i < toIndex; i++) {
            Instant startsAt = showInstant(SHOW_DAYS_OUT[i]);
            int seats = adminShowService.createShow(
                    new CreateShowRequest(event.getId(), startsAt, basePrice)).seatsCreated();
            log.info("created show for event {} at {} with {} seats", event.getId(), startsAt, seats);
            showSeats += seats;
        }
        return showSeats;
    }

    /**
     * Midnight UTC of the current day, plus the offset, plus 18:30.
     *
     * <p>Truncating to days first is what makes two runs on the same day produce
     * byte-identical instants instead of drifting by however long apart they were.
     */
    private Instant showInstant(long daysOut) {
        return Instant.now(clock)
                .truncatedTo(ChronoUnit.DAYS)
                .plus(daysOut, ChronoUnit.DAYS)
                .plus(SHOW_HOUR_UTC, ChronoUnit.HOURS)
                .plus(SHOW_MINUTE_UTC, ChronoUnit.MINUTES);
    }
}
