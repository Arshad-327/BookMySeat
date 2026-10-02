package com.bookmyseat.event;

import com.bookmyseat.event.entity.Event;
import com.bookmyseat.event.entity.Seat;
import com.bookmyseat.event.entity.Show;
import com.bookmyseat.event.entity.ShowSeat;
import com.bookmyseat.event.entity.Venue;
import com.bookmyseat.event.repository.EventRepository;
import com.bookmyseat.event.repository.SeatRepository;
import com.bookmyseat.event.repository.ShowRepository;
import com.bookmyseat.event.repository.ShowSeatRepository;
import com.bookmyseat.event.repository.VenueRepository;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;

/**
 * Test data for the real-MySQL tests: empties event_db and builds a small show
 * through the real repositories, so every row passes the same mappings and
 * constraints as production data.
 */
public final class SeatFixtures {

    public static final BigDecimal PRICE = new BigDecimal("450.00");

    /**
     * Far enough out that no test trips the started-show guard by accident, and a fixed
     * literal rather than {@code now().plus(...)} so the value in a response assertion can be
     * written down.
     */
    public static final Instant DEFAULT_STARTS_AT = Instant.parse("2030-01-01T18:30:00Z");

    private static final List<String> TABLES = List.of("show_seats", "shows", "events", "seats", "venues");

    private final VenueRepository venueRepository;
    private final EventRepository eventRepository;
    private final ShowRepository showRepository;
    private final SeatRepository seatRepository;
    private final ShowSeatRepository showSeatRepository;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    public SeatFixtures(ApplicationContext context) {
        this.venueRepository = context.getBean(VenueRepository.class);
        this.eventRepository = context.getBean(EventRepository.class);
        this.showRepository = context.getBean(ShowRepository.class);
        this.seatRepository = context.getBean(SeatRepository.class);
        this.showSeatRepository = context.getBean(ShowSeatRepository.class);
        this.jdbcTemplate = context.getBean(JdbcTemplate.class);
        this.transactionTemplate = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
    }

    /** Empties every event_db table, leaving flyway_schema_history alone. */
    public void truncateAll() {
        jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
            // FOREIGN_KEY_CHECKS is per session, so every statement runs on this one connection.
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET FOREIGN_KEY_CHECKS = 0");
                for (String table : TABLES) {
                    statement.execute("TRUNCATE TABLE " + table);
                }
                statement.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
            return null;
        });
    }

    /**
     * One venue, one event and one show with {@code seatCount} AVAILABLE seats, starting at
     * {@link #DEFAULT_STARTS_AT}. Returns the show id.
     */
    public Long createShow(String name, int seatCount) {
        return createShow(name, seatCount, DEFAULT_STARTS_AT);
    }

    /**
     * The same, at a start time the caller chooses - including one in the past.
     *
     * <p>Exists for the started-show guard on the seat write, which cannot be tested against
     * {@link #DEFAULT_STARTS_AT}: that instant is years out on purpose, so that every other
     * test's show is comfortably bookable and none of them has to think about the clock. A
     * test of the boundary needs a show sitting on it.
     */
    public Long createShow(String name, int seatCount, Instant startsAt) {
        return transactionTemplate.execute(status -> {
            Venue venue = new Venue();
            venue.setName(name);
            venue.setCity("Test City");
            venueRepository.save(venue);

            Event event = new Event();
            event.setTitle(name + " event");
            event.setCategory("CONCERT");
            event.setVenue(venue);
            eventRepository.save(event);

            Show show = new Show();
            show.setEvent(event);
            show.setStartsAt(startsAt);
            show.setBasePrice(PRICE);
            showRepository.save(show);

            for (int number = 1; number <= seatCount; number++) {
                Seat seat = new Seat();
                seat.setVenue(venue);
                seat.setRowLabel("A");
                seat.setSeatNumber(number);
                seat.setSeatType("STANDARD");
                seatRepository.save(seat);

                ShowSeat showSeat = new ShowSeat();
                showSeat.setShow(show);
                showSeat.setSeat(seat);
                showSeat.setPrice(PRICE);
                showSeatRepository.save(showSeat);
            }
            return show.getId();
        });
    }

    /** The event a show belongs to. {@link #createShow} returns the show id only. */
    public Long eventIdOf(Long showId) {
        return jdbcTemplate.queryForObject("SELECT event_id FROM shows WHERE id = ?", Long.class, showId);
    }

    /**
     * One more show for an event that already exists, with NO seats. Returns the show id.
     *
     * <p>For the event list, which reads {@code shows} and never show_seats: an event with
     * several shows at different times and prices is the only way to tell MIN(starts_at)
     * from "the first row" and a per-event aggregate from a per-show one. A show built here
     * cannot be booked and its seat map is a 404 - it is not for those tests.
     */
    public Long addShow(Long eventId, Instant startsAt, BigDecimal basePrice) {
        return transactionTemplate.execute(status -> {
            Show show = new Show();
            show.setEvent(eventRepository.getReferenceById(eventId));
            show.setStartsAt(startsAt);
            show.setBasePrice(basePrice);
            return showRepository.save(show).getId();
        });
    }

    /** One venue and one event with no show at all. Returns the event id. */
    public Long createEventWithoutShows(String name) {
        return transactionTemplate.execute(status -> {
            Venue venue = new Venue();
            venue.setName(name);
            venue.setCity("Test City");
            venueRepository.save(venue);

            Event event = new Event();
            event.setTitle(name + " event");
            event.setCategory("CONCERT");
            event.setVenue(venue);
            return eventRepository.save(event).getId();
        });
    }

    public List<Long> showSeatIds(Long showId) {
        return jdbcTemplate.queryForList(
                "SELECT id FROM show_seats WHERE show_id = ? ORDER BY id", Long.class, showId);
    }

    /**
     * Marks a seat BOOKED by direct SQL, with the owner given - {@code null} included.
     *
     * <p>Deliberately NOT through the endpoint. A test that needs a seat BOOKED with a
     * NULL owner (a row as written before V2__seat_booking_owner.sql) or BOOKED to some
     * other booking cannot get one from the endpoint, which always stamps the caller's
     * own booking id. Bumps version the way a real write would, so a test can tell an
     * untouched row from a re-written one afterwards.
     */
    public void bookDirectly(Long showSeatId, Long bookingId) {
        jdbcTemplate.update(
                "UPDATE show_seats SET status = 'BOOKED', booked_by_booking_id = ?, "
                        + "version = version + 1 WHERE id = ?",
                bookingId, showSeatId);
    }

    /** The row exactly as MySQL holds it - plain JDBC, so no persistence context can mask it. */
    public SeatRow row(Long showSeatId) {
        return jdbcTemplate.queryForObject(
                "SELECT status, version, price, booked_by_booking_id FROM show_seats WHERE id = ?",
                (rs, rowNum) -> new SeatRow(
                        rs.getString("status"),
                        rs.getLong("version"),
                        rs.getBigDecimal("price"),
                        // getObject, not getLong: getLong reads a SQL NULL as 0, which is
                        // indistinguishable from a booking id of zero and would let an
                        // unwritten owner pass an assertion that it was written.
                        rs.getObject("booked_by_booking_id", Long.class)),
                showSeatId);
    }

    public record SeatRow(String status, long version, BigDecimal price, Long bookedByBookingId) {
    }
}
