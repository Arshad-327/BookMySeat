package com.bookmyseat.event.entity;

import com.bookmyseat.event.MySqlContainerTest;
import com.bookmyseat.event.SeatFixtures;
import com.bookmyseat.event.repository.ShowSeatRepository;
import org.hibernate.StaleStateException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Layer 2, proven rather than assumed: a stale version causes a rejection.
 *
 * <p>Not a test that {@code @Version} is present - a test that it fires. Two
 * transactions load the same ShowSeat at the same version and both mutate it; the
 * second to write must fail with an optimistic-lock failure and leave no trace in
 * the row. Runs against real MySQL, with Hibernate's SQL logged so the
 * {@code where id=? and version=?} predicate is visible in the output.
 *
 * <p>Deliberately below InternalSeatService: going through the entity directly means
 * the version check alone does the rejecting, with no status guard to help it.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "logging.level.org.hibernate.SQL=DEBUG")
class ShowSeatOptimisticLockTest extends MySqlContainerTest {

    /** A value the losing writer tries to set, so its absence afterwards is checkable. */
    private static final BigDecimal LOSER_PRICE = new BigDecimal("1.00");

    @Autowired
    private ShowSeatRepository showSeatRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ApplicationContext context;

    private SeatFixtures fixtures;
    private Long seatId;

    @BeforeEach
    void setUp() {
        fixtures = new SeatFixtures(context);
        fixtures.truncateAll();
        Long showId = fixtures.createShow("Lock Arena", 1);
        seatId = fixtures.showSeatIds(showId).get(0);
    }

    @Test
    @DisplayName("stale version: the second writer of the same ShowSeat is rejected and changes nothing")
    void staleVersionIsRejected() {
        TransactionTemplate transactionA = new TransactionTemplate(transactionManager);
        TransactionTemplate transactionB = new TransactionTemplate(transactionManager);
        // REQUIRES_NEW: B is a genuinely separate transaction with its own persistence
        // context, not a nested view of A.
        transactionB.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        assertThatThrownBy(() -> transactionA.executeWithoutResult(a -> {
            // A loads the seat: AVAILABLE, version 0.
            ShowSeat staleCopy = showSeatRepository.findById(seatId).orElseThrow();
            assertThat(staleCopy.getVersion()).isZero();

            // B loads the same row at the same version, mutates it and commits: 0 -> 1.
            transactionB.executeWithoutResult(b -> {
                ShowSeat freshCopy = showSeatRepository.findById(seatId).orElseThrow();
                assertThat(freshCopy.getVersion()).isZero();
                freshCopy.setStatus(SeatStatus.BOOKED);
            });

            // A still holds version 0. It mutates its copy and writes:
            //   update show_seats set ... where id=? and version=0   -> matches 0 rows
            staleCopy.setStatus(SeatStatus.BOOKED);
            staleCopy.setPrice(LOSER_PRICE);
            showSeatRepository.flush();
        }))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class)
                // StaleStateException, not its subclass StaleObjectStateException: event-service
                // batches updates (hibernate.jdbc.batch_size), and the batch reports the zero row count.
                .hasRootCauseInstanceOf(StaleStateException.class)
                .hasMessageContaining("actual row count: 0");

        // MySQL holds B's write and nothing of A's.
        SeatFixtures.SeatRow row = fixtures.row(seatId);
        assertThat(row.status()).isEqualTo("BOOKED");
        assertThat(row.version()).isEqualTo(1L);
        assertThat(row.price()).isEqualByComparingTo(SeatFixtures.PRICE);
    }

    @Test
    @DisplayName("control: the same write holding the current version succeeds and bumps the version")
    void currentVersionIsAccepted() {
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            ShowSeat seat = showSeatRepository.findById(seatId).orElseThrow();
            seat.setStatus(SeatStatus.BOOKED);
            showSeatRepository.flush();
        });

        SeatFixtures.SeatRow row = fixtures.row(seatId);
        assertThat(row.status()).isEqualTo("BOOKED");
        assertThat(row.version()).isEqualTo(1L);
    }
}
