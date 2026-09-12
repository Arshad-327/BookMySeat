package com.bookmyseat.booking.service;

import com.bookmyseat.booking.dto.request.CreateBookingRequest;
import com.bookmyseat.booking.dto.response.BookingResponse;
import com.bookmyseat.booking.exception.IdempotencyKeyConflictException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Makes POST /api/bookings/hold safe to retry, and POST /{id}/confirm convenient to.
 *
 * <h2>Two layers, and why both</h2>
 * Redis is the fast path and MySQL is the guarantee, the same shape as layers 1 and 3
 * on the seat itself:
 *
 * <ul>
 *   <li><b>Redis - fast but evictable.</b> {@code idem:{key} -> bookingId} answers a
 *       replay in one round trip without touching the database. It is a cache: it can
 *       be evicted under memory pressure, lost to a restart, or simply time out. It
 *       can never be relied on to be there.</li>
 *   <li><b>The unique index on bookings.idempotency_key - slow but true.</b> It is
 *       checked on every insert, by the database, with no cooperation from this code.
 *       It cannot be evicted, and it holds across restarts, deploys and bugs.</li>
 * </ul>
 *
 * <p>So the fast path is an optimisation and the constraint is the promise. Remove
 * Redis and this class still never creates two bookings for one key - it just costs a
 * failed insert to find out. Remove the constraint and Redis alone would let a replay
 * through the moment a key was evicted. Neither layer is redundant, and the cheap one
 * is the one allowed to fail.
 *
 * <h2>Why this class exists at all, rather than a few lines inside BookingService</h2>
 * <b>Do not fold this back into {@link BookingService}. It will not work, and the way
 * it fails is quiet.</b> Two separate reasons, both about Spring's transaction model:
 *
 * <ol>
 *   <li><b>A constraint violation cannot be caught inside the transaction that caused
 *       it.</b> When MySQL refuses the insert, that transaction is already marked
 *       rollback-only. Catching {@link DataIntegrityViolationException} inside it and
 *       then reading the existing booking does not recover anything: the recovery read
 *       runs in a transaction that is doomed to roll back, and the commit fails anyway
 *       with an UnexpectedRollbackException. The recovery has to happen <b>after</b>
 *       the failed transaction has ended, which means the catch must sit outside it -
 *       so this class is deliberately NOT annotated {@code @Transactional}, and
 *       {@link BookingService#hold} keeps its own.</li>
 *   <li><b>A same-bean call would bypass the proxy.</b> Even written as two methods on
 *       BookingService, an inner {@code this.hold(...)} call never passes through the
 *       transactional proxy, so the inner {@code @Transactional} would not start a
 *       transaction of its own - the insert would join the outer one and the first
 *       problem returns, with nothing in the code to show why.</li>
 * </ol>
 *
 * <p>Hence: a separate bean, calling {@link BookingService} through the container.
 *
 * <p><b>This class must not be called from inside an existing transaction.</b> It
 * relies on {@link BookingService#hold} beginning and ending a transaction of its own;
 * an outer transaction would enclose the failed insert and reintroduce exactly the
 * problem described above. The controller calls it directly, which is the only caller
 * there should be.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IdempotentBookingService {

    private final BookingService bookingService;
    private final IdempotencyService idempotencyService;

    /**
     * The booking, and whether it is one this request created.
     *
     * <p>{@code replayed} is what lets the controller answer 201 for a booking it just
     * made and 200 for one it is handing back, which is the only externally visible
     * difference between the two.
     */
    public record HoldOutcome(BookingResponse booking, boolean replayed) {
    }

    /**
     * Step one, made safe to retry. Returns the existing booking rather than creating
     * a second one when the key has been seen before.
     *
     * @throws IdempotencyKeyConflictException 409, the key belongs to another user
     */
    public HoldOutcome hold(Long userId, String idempotencyKey, CreateBookingRequest request) {
        // Fast path. A hit here skips the insert entirely.
        Optional<BookingResponse> cached = fromCache(userId, idempotencyKey);
        if (cached.isPresent()) {
            return new HoldOutcome(cached.get(), true);
        }

        BookingResponse created;
        try {
            created = bookingService.hold(userId, idempotencyKey, request);
        } catch (DataIntegrityViolationException ex) {
            // The guarantee firing. Either Redis never knew this key (evicted, expired,
            // unreachable) or a concurrent request carrying the same key committed first
            // while this one was in flight. Both mean the same thing: a booking for this
            // key already exists, and the caller should be given it.
            //
            // This runs only after bookingService.hold()'s transaction has rolled back -
            // see the class javadoc for why that ordering is not optional.
            if (!isIdempotencyKeyViolation(ex)) {
                // A different constraint. Not ours to interpret; let the handler map it.
                throw ex;
            }

            BookingResponse existing = fromDatabase(userId, idempotencyKey)
                    .orElseThrow(() -> ex);

            // Repopulate the fast path. Reaching here means Redis did not know a key
            // the database did, so without this every later replay would pay for
            // another failed insert to learn the same answer. The cache heals itself
            // rather than staying cold until the TTL it no longer has.
            idempotencyService.record(idempotencyKey, existing.id());

            log.info("idempotency key {} replayed for user {} - returning existing booking {} "
                            + "(resolved through the unique index, not Redis)",
                    idempotencyKey, userId, existing.id());
            return new HoldOutcome(existing, true);
        }

        // Committed. Record the key so the next replay takes the fast path; if this
        // fails, the constraint still answers a replay correctly.
        idempotencyService.record(idempotencyKey, created.id());
        return new HoldOutcome(created, false);
    }

    /**
     * Step two, made convenient to retry.
     *
     * <h2>Why confirm gets Redis only, and no database guarantee</h2>
     * <b>Confirm is a state transition, not a creation.</b> Replaying it cannot produce
     * two of anything: the booking already exists, and the PENDING check rejects the
     * second attempt on its own. Idempotency keys exist to make CREATION safe, which is
     * why hold gets the database guarantee and confirm does not need one.
     *
     * <p>So the key here buys exactly one thing: a replay is answered 200 with the
     * booking, instead of the 409 the PENDING check would otherwise give it. That is a
     * convenience for clients that retry, not a fourth layer of protection, and nothing
     * about correctness rests on it. It is also why the key is not written to
     * bookings.idempotency_key - that column holds the key that CREATED the booking, and
     * there is exactly one such key per row.
     */
    public BookingResponse confirm(Long userId, Long bookingId, String idempotencyKey) {
        if (idempotencyKey == null) {
            return bookingService.confirm(userId, bookingId);
        }

        Optional<BookingResponse> cached = fromCache(userId, idempotencyKey);
        if (cached.isPresent()) {
            return cached.get();
        }

        BookingResponse confirmed = bookingService.confirm(userId, bookingId);
        idempotencyService.record(idempotencyKey, confirmed.id());
        return confirmed;
    }

    /**
     * The fast path: Redis, and nothing else.
     *
     * <p>Deliberately does NOT fall back to a lookup by key in the database. A miss
     * here is overwhelmingly the common case - every genuinely new request is one - and
     * querying the database on each of them would put an extra round trip on the hot
     * path to catch a case the insert is about to detect anyway, for free, via the
     * unique index. The database lookup belongs in {@link #fromDatabase}, on the
     * recovery path, where it runs only when a replay has actually been detected.
     */
    private Optional<BookingResponse> fromCache(Long userId, String idempotencyKey) {
        return idempotencyService.findBookingId(idempotencyKey)
                .flatMap(bookingService::findByIdOptional)
                .map(booking -> checkOwnership(userId, idempotencyKey, booking, "Redis"));
    }

    /** The slow path: resolve the key against bookings.idempotency_key itself. */
    private Optional<BookingResponse> fromDatabase(Long userId, String idempotencyKey) {
        return bookingService.findByIdempotencyKey(idempotencyKey)
                .map(booking -> checkOwnership(userId, idempotencyKey, booking, "the unique index"));
    }

    /**
     * Refuses to hand a booking to anyone but its owner.
     *
     * <p>Not ceremony. An idempotency key is supplied by the client, so two callers can
     * present the same one - by collision, by copying a sample request, or deliberately.
     * Returning the booking without checking would hand one user another user's booking,
     * seats and total. A mismatch is refused as a conflict rather than served.
     *
     * @param source where the answer came from, for the log line only
     */
    private BookingResponse checkOwnership(
            Long userId, String idempotencyKey, BookingResponse booking, String source) {
        if (!booking.userId().equals(userId)) {
            log.warn("user {} presented idempotency key {}, which belongs to user {} "
                            + "(booking {}, found via {})",
                    userId, idempotencyKey, booking.userId(), booking.id(), source);
            throw new IdempotencyKeyConflictException(idempotencyKey);
        }
        return booking;
    }

    /**
     * Whether this violation is the idempotency key's unique index rather than some
     * other constraint.
     *
     * <p>Matched on the constraint name, which MySQL includes in the message even when
     * Hibernate did not extract it - the same approach GlobalExceptionHandler uses.
     */
    private static boolean isIdempotencyKeyViolation(DataIntegrityViolationException ex) {
        String message = ex.getMostSpecificCause().getMessage();
        return message != null
                && message.toLowerCase(java.util.Locale.ROOT).contains("uq_bookings_idempotency_key");
    }
}
