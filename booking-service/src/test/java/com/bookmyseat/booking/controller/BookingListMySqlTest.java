package com.bookmyseat.booking.controller;

import com.bookmyseat.booking.MySqlContainerTest;
import com.bookmyseat.booking.client.EventClient;
import com.bookmyseat.booking.entity.Booking;
import com.bookmyseat.booking.entity.BookingSeat;
import com.bookmyseat.booking.entity.BookingStatus;
import com.bookmyseat.booking.repository.BookingRepository;
import com.bookmyseat.booking.service.SeatHoldService;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.sql.Statement;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /api/bookings - the caller's bookings, paged, against real MySQL.
 *
 * <p>Before this class no Java test called the list at all. Its array shape was pinned by
 * one line of scripts/e2e-smoke.sh, and that the list returns only the caller's own
 * bookings was pinned by nothing.
 *
 * <h2>What is pinned here</h2>
 * <ul>
 *   <li><b>Ownership</b>, in a test of its own: a user never sees another user's bookings,
 *       with or without a status filter. The two cases run through two different repository
 *       methods, and each carries the user predicate separately.
 *   <li>The page envelope, newest-first order, the default size and the 100 cap.
 *   <li>{@code status}: absent means everything, it repeats, and it is strict - an unknown
 *       or lower-case value is a 400 naming the valid ones.
 *   <li>A client cannot choose the order.
 *   <li>Seats arrive with their labels in a stable order, and the whole page costs three
 *       statements however many bookings and seats are on it.
 * </ul>
 *
 * <p>Hibernate statistics are switched on for this class only, to count statements.
 */
@SpringBootTest(properties = {
        "spring.jpa.properties.hibernate.generate_statistics=true",
        // Statistics also log a metrics block per session at INFO. Not wanted, only the counter.
        "logging.level.org.hibernate.engine.internal.StatisticalLoggingSessionEventListener=WARN"
})
@AutoConfigureMockMvc
class BookingListMySqlTest extends MySqlContainerTest {

    private static final long SHOW_ID = 301L;
    private static final BigDecimal PRICE = new BigDecimal("450.00");

    private static final long ALICE = 7L;
    private static final long BOB = 8L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    /** Not reached by a read; mocked so the context needs no Redis. */
    @MockBean
    private SeatHoldService seatHoldService;

    /** Not reached by a read; mocked so the context needs no event-service. */
    @MockBean
    private EventClient eventClient;

    /** Seat ids are handed out from here so no two bookings in a test share a seat. */
    private long nextSeatId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET FOREIGN_KEY_CHECKS = 0");
                statement.execute("TRUNCATE TABLE booking_seats");
                statement.execute("TRUNCATE TABLE bookings");
                statement.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
            return null;
        });
        nextSeatId = 9001L;
    }

    // ------------------------------------------------------------------------------------
    // Ownership. A security assertion, kept apart from the paging tests so it reads as one.
    // ------------------------------------------------------------------------------------

    @Test
    @DisplayName("SECURITY: a user never sees another user's bookings - not in the list, not in the count, not through a status filter")
    void theListIsScopedToTheCaller() throws Exception {
        Long alicePending = booking(ALICE, BookingStatus.PENDING, 1);
        Long aliceConfirmed = booking(ALICE, BookingStatus.CONFIRMED, 1);
        booking(BOB, BookingStatus.PENDING, 1);
        booking(BOB, BookingStatus.CONFIRMED, 1);
        booking(BOB, BookingStatus.CANCELLED, 1);

        // Unfiltered: BookingRepository.findByUserId.
        mockMvc.perform(get("/api/bookings").header("X-User-Id", ALICE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id")
                        .value(containsInAnyOrder(alicePending.intValue(), aliceConfirmed.intValue())))
                .andExpect(jsonPath("$.content[*].userId").value(everyItem(is((int) ALICE))))
                // The count leaks too, if it is not scoped: "you have 5 bookings" on a page
                // showing 2 says three more exist and whose they are not.
                .andExpect(jsonPath("$.totalElements").value(2));

        // Filtered: BookingRepository.findByUserIdAndStatusIn - a different method, with its
        // own copy of the user predicate. Bob has a PENDING booking; Alice must not get it.
        mockMvc.perform(get("/api/bookings").header("X-User-Id", ALICE).param("status", "PENDING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id").value(contains(alicePending.intValue())))
                .andExpect(jsonPath("$.totalElements").value(1));

        // Bob has a CANCELLED booking and Alice has none: an empty page, not Bob's.
        mockMvc.perform(get("/api/bookings").header("X-User-Id", ALICE).param("status", "CANCELLED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0))
                .andExpect(jsonPath("$.totalElements").value(0));

        // A user with no bookings at all gets an empty page, and it is still a 200.
        mockMvc.perform(get("/api/bookings").header("X-User-Id", 999))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    @DisplayName("the list without X-User-Id is a 400, not an unscoped read")
    void missingUserIdIsRejected() throws Exception {
        booking(ALICE, BookingStatus.PENDING, 1);

        mockMvc.perform(get("/api/bookings"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Required header 'X-User-Id' is missing"));
    }

    // ------------------------------------------------------------------------------------
    // The envelope, the order and the page size.
    // ------------------------------------------------------------------------------------

    @Test
    @DisplayName("the list is a page envelope, newest first, 20 to a page by default")
    void pagesNewestFirst() throws Exception {
        Long first = booking(ALICE, BookingStatus.EXPIRED, 1);
        Long second = booking(ALICE, BookingStatus.EXPIRED, 1);
        Long third = booking(ALICE, BookingStatus.CONFIRMED, 1);
        Long fourth = booking(ALICE, BookingStatus.CANCELLED, 1);
        Long fifth = booking(ALICE, BookingStatus.PENDING, 1);

        mockMvc.perform(get("/api/bookings").header("X-User-Id", ALICE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id").value(contains(
                        fifth.intValue(), fourth.intValue(), third.intValue(), second.intValue(), first.intValue())))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(5))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.last").value(true));

        mockMvc.perform(get("/api/bookings").header("X-User-Id", ALICE).param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id").value(contains(fifth.intValue(), fourth.intValue())))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalElements").value(5))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.last").value(false));

        mockMvc.perform(get("/api/bookings").header("X-User-Id", ALICE).param("size", "2").param("page", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id").value(contains(first.intValue())))
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.last").value(true));
    }

    @Test
    @DisplayName("page size is capped at 100: a larger size is reduced, not refused")
    void pageSizeIsCapped() throws Exception {
        booking(ALICE, BookingStatus.PENDING, 1);

        mockMvc.perform(get("/api/bookings").header("X-User-Id", ALICE).param("size", "100000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(100));
    }

    @Test
    @DisplayName("the order is not the caller's to choose: a sort parameter is ignored, valid or not")
    void aSortParameterIsIgnored() throws Exception {
        Long older = booking(ALICE, BookingStatus.PENDING, 1);
        Long newer = booking(ALICE, BookingStatus.PENDING, 1);

        // A real property, ascending: honoured, this would put the older booking first.
        mockMvc.perform(get("/api/bookings").header("X-User-Id", ALICE).param("sort", "id,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id").value(contains(newer.intValue(), older.intValue())));

        // Not a property at all. event-service's list answers 400 for this, because there
        // the sort IS the caller's; here it never reaches a query, so there is nothing to
        // refuse.
        mockMvc.perform(get("/api/bookings").header("X-User-Id", ALICE).param("sort", "nonsense"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id").value(contains(newer.intValue(), older.intValue())));
    }

    // ------------------------------------------------------------------------------------
    // status
    // ------------------------------------------------------------------------------------

    @Test
    @DisplayName("no status means every status - there is no server-side default filter")
    void noStatusReturnsEverything() throws Exception {
        booking(ALICE, BookingStatus.PENDING, 1);
        booking(ALICE, BookingStatus.CONFIRMED, 1);
        booking(ALICE, BookingStatus.CANCELLED, 1);
        booking(ALICE, BookingStatus.EXPIRED, 1);

        mockMvc.perform(get("/api/bookings").header("X-User-Id", ALICE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].status")
                        .value(containsInAnyOrder("PENDING", "CONFIRMED", "CANCELLED", "EXPIRED")))
                .andExpect(jsonPath("$.totalElements").value(4));

        // An empty value is the same as no value - what a client sends when a filter
        // control is cleared and the parameter is left in the URL. Not a 400, and not a
        // filter that matches nothing.
        mockMvc.perform(get("/api/bookings").header("X-User-Id", ALICE).param("status", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(4));
    }

    @Test
    @DisplayName("status narrows the list and the count, and can be repeated or comma-separated")
    void statusFiltersAndRepeats() throws Exception {
        booking(ALICE, BookingStatus.PENDING, 1);
        Long confirmed = booking(ALICE, BookingStatus.CONFIRMED, 1);
        Long cancelled = booking(ALICE, BookingStatus.CANCELLED, 1);
        Long expired = booking(ALICE, BookingStatus.EXPIRED, 1);

        mockMvc.perform(get("/api/bookings").header("X-User-Id", ALICE).param("status", "CONFIRMED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id").value(contains(confirmed.intValue())))
                .andExpect(jsonPath("$.totalElements").value(1));

        // The "History" tab: two values, repeated. Still newest first.
        mockMvc.perform(get("/api/bookings").header("X-User-Id", ALICE)
                        .param("status", "CANCELLED").param("status", "EXPIRED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id").value(contains(expired.intValue(), cancelled.intValue())))
                .andExpect(jsonPath("$.totalElements").value(2));

        mockMvc.perform(get("/api/bookings").header("X-User-Id", ALICE).param("status", "CANCELLED,EXPIRED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    @DisplayName("status is strict: an unknown or lower-case value is a 400 that lists the valid ones")
    void anUnknownStatusIsRefusedWithTheValidValues() throws Exception {
        booking(ALICE, BookingStatus.CONFIRMED, 1);
        String message = "Parameter 'status' must be one of PENDING, CONFIRMED, CANCELLED, EXPIRED";

        mockMvc.perform(get("/api/bookings").header("X-User-Id", ALICE).param("status", "PAID"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(message));

        // What a frontend developer types first. Refused on purpose, and loudly: the
        // message is the documentation. See BookingController#canonicalKey for why this is
        // strict when the Idempotency-Key is not.
        mockMvc.perform(get("/api/bookings").header("X-User-Id", ALICE).param("status", "confirmed"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(message));

        // One bad value among good ones refuses the request; it is not quietly dropped.
        mockMvc.perform(get("/api/bookings").header("X-User-Id", ALICE)
                        .param("status", "CONFIRMED").param("status", "nope"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(message));
    }

    // ------------------------------------------------------------------------------------
    // Seats: labels, order, and what the page costs.
    // ------------------------------------------------------------------------------------

    @Test
    @DisplayName("each booking carries its own seats, labelled, in the same order on every request")
    void seatsArriveLabelledAndInAStableOrder() throws Exception {
        Long older = booking(ALICE, BookingStatus.CONFIRMED, 3);   // seats 9001, 9002, 9003
        Long newer = booking(ALICE, BookingStatus.PENDING, 2);     // seats 9004, 9005

        for (int attempt = 0; attempt < 3; attempt++) {
            mockMvc.perform(get("/api/bookings").header("X-User-Id", ALICE))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content[0].id").value(newer))
                    .andExpect(jsonPath("$.content[0].eventTitle").value("Coldplay - Music of the Spheres"))
                    .andExpect(jsonPath("$.content[0].seats[*].showSeatId").value(contains(9004, 9005)))
                    .andExpect(jsonPath("$.content[1].id").value(older))
                    .andExpect(jsonPath("$.content[1].seats[*].showSeatId").value(contains(9001, 9002, 9003)))
                    .andExpect(jsonPath("$.content[1].seats[*].rowLabel").value(contains("C", "C", "C")))
                    .andExpect(jsonPath("$.content[1].seats[*].seatNumber").value(contains(1, 2, 3)));
        }
    }

    @Test
    @DisplayName("a page costs three statements - page, count, seats - however many bookings and seats are on it")
    void aPageIsThreeStatements() throws Exception {
        for (int i = 0; i < 5; i++) {
            booking(ALICE, BookingStatus.CONFIRMED, 3);
        }
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();

        // size=2 of 5: a full page, so Spring Data cannot infer the total and runs the count.
        statistics.clear();
        mockMvc.perform(get("/api/bookings").header("X-User-Id", ALICE).param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].seats.length()").value(3));
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(3);

        // Twice the bookings on the page, six more seats: still three. The old list read
        // each booking's lazy seats in turn, which would be 2 + 4 here and 2 + N in general.
        statistics.clear();
        mockMvc.perform(get("/api/bookings").header("X-User-Id", ALICE).param("size", "4"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(4));
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(3);
    }

    /** A booking with {@code seatCount} seats in row C, labelled as a post-V4 hold would be. */
    private Long booking(long userId, BookingStatus status, int seatCount) {
        Booking booking = new Booking();
        booking.setUserId(userId);
        booking.setShowId(SHOW_ID);
        booking.setEventId(42L);
        booking.setEventTitle("Coldplay - Music of the Spheres");
        booking.setVenueName("Phoenix Arena");
        booking.setShowStartsAt(Instant.parse("2030-01-01T18:30:00Z"));
        booking.setStatus(status);
        booking.setTotalAmount(PRICE.multiply(BigDecimal.valueOf(seatCount)));
        booking.setExpiresAt(status == BookingStatus.CONFIRMED ? null : Instant.parse("2030-01-01T00:10:00Z"));
        for (int number = 1; number <= seatCount; number++) {
            BookingSeat seat = new BookingSeat();
            seat.setShowSeatId(nextSeatId++);
            seat.setRowLabel("C");
            seat.setSeatNumber(number);
            seat.setPrice(PRICE);
            booking.addSeat(seat);
        }
        return bookingRepository.save(booking).getId();
    }
}
