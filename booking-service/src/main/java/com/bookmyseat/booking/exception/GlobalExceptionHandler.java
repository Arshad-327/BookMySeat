package com.bookmyseat.booking.exception;

import com.bookmyseat.booking.dto.response.ErrorResponse;
import com.bookmyseat.booking.dto.response.SeatConflictResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * One error shape for the whole service: { timestamp, status, error, message, path }.
 */
@RestControllerAdvice
@RequiredArgsConstructor
@Slf4j
public class GlobalExceptionHandler {

    /** Constraint names from V2__unique_booking_constraints.sql. */
    private static final String SOLD_SEAT_CONSTRAINT = "uq_booking_seats_sold_show_seat";
    private static final String IDEMPOTENCY_KEY_CONSTRAINT = "uq_bookings_idempotency_key";

    /** Injected rather than Instant.now() so time is never read off the host clock. */
    private final Clock clock;

    @ExceptionHandler({ShowNotFoundException.class, BookingNotFoundException.class})
    public ResponseEntity<ErrorResponse> handleNotFound(
            RuntimeException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, ex.getMessage(), request);
    }

    @ExceptionHandler(SeatNotAvailableException.class)
    public ResponseEntity<ErrorResponse> handleSeatNotAvailable(
            SeatNotAvailableException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, ex.getMessage(), request);
    }

    @ExceptionHandler(UnknownSeatException.class)
    public ResponseEntity<ErrorResponse> handleUnknownSeat(
            UnknownSeatException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, ex.getMessage(), request);
    }

    /** A malformed Idempotency-Key. The caller can fix it, so 400 rather than 409. */
    @ExceptionHandler(InvalidIdempotencyKeyException.class)
    public ResponseEntity<ErrorResponse> handleInvalidIdempotencyKey(
            InvalidIdempotencyKeyException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, ex.getMessage(), request);
    }

    /**
     * An Idempotency-Key presented by a user other than the one it belongs to.
     *
     * <p>409 rather than 403: the key is a conflict with existing data, and the caller
     * is not being denied access to something of their own. Deliberately not served as
     * a replay - see IdempotentBookingService.
     */
    @ExceptionHandler(IdempotencyKeyConflictException.class)
    public ResponseEntity<ErrorResponse> handleIdempotencyKeyConflict(
            IdempotencyKeyConflictException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, ex.getMessage(), request);
    }

    /**
     * The only handler that returns a shape other than ErrorResponse.
     *
     * <p>It carries the conflicting seat ids as a list so a client can act on them
     * - grey out those seats, keep the rest of the selection - instead of parsing
     * ids back out of the message. The five standard fields are still there, so a
     * client with generic error handling is unaffected.
     */
    @ExceptionHandler(SeatsAlreadyHeldException.class)
    public ResponseEntity<SeatConflictResponse> handleSeatsAlreadyHeld(
            SeatsAlreadyHeldException ex, HttpServletRequest request) {
        HttpStatus status = HttpStatus.CONFLICT;
        return ResponseEntity.status(status).body(new SeatConflictResponse(
                Instant.now(clock),
                status.value(),
                status.getReasonPhrase(),
                ex.getMessage(),
                request.getRequestURI(),
                ex.getConflictingSeatIds()));
    }

    /**
     * A lapsed or stolen hold, a confirm on a booking that is not PENDING, and
     * event-service refusing to mark the seats BOOKED (layer 2 firing over there).
     */
    @ExceptionHandler({HoldExpiredException.class, BookingNotPendingException.class,
            SeatBookingRejectedException.class})
    public ResponseEntity<ErrorResponse> handleBookingConflict(
            RuntimeException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, ex.getMessage(), request);
    }

    /**
     * Layer 3 firing: MySQL refused a write that would break a constraint. Always 409.
     *
     * <p>This is the database guarantee doing its job - a second confirmation for a
     * seat that is already sold, or a reused idempotency key. It is a conflict with
     * existing data, not a server fault, so it must never surface as a 500. The
     * message is chosen from the constraint name, so the caller learns which rule it
     * hit without being shown any SQL.
     *
     * <p><b>A reused idempotency key normally never reaches here.</b>
     * IdempotentBookingService catches that violation on the hold path and returns the
     * original booking with 200, which is the whole point of the key. This stays as the
     * backstop for any other path that writes a booking without going through it -
     * better a 409 naming the rule than a 500 naming nothing.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrity(
            DataIntegrityViolationException ex, HttpServletRequest request) {
        String constraint = violatedConstraint(ex);
        log.warn("constraint [{}] rejected a write on {} {}",
                constraint, request.getMethod(), request.getRequestURI(), ex);
        return build(HttpStatus.CONFLICT, conflictMessage(constraint), request);
    }

    /**
     * Redis is unreachable, so no seat hold could be taken or verified.
     *
     * <p>503 and nothing written. A seat hold FAILS CLOSED: without it there is no
     * mutual exclusion during checkout. Layers 2 and 3 would still stop a double sale
     * at confirm, but only after every contender had gone through checkout for one
     * seat. See SeatHoldService for the full reasoning, and for why a rate limiter
     * facing the same outage should do the opposite and fail open.
     */
    @ExceptionHandler(HoldUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleHoldUnavailable(
            HoldUnavailableException ex, HttpServletRequest request) {
        log.error("seat hold unavailable on {} {}", request.getMethod(), request.getRequestURI(), ex);
        return build(HttpStatus.SERVICE_UNAVAILABLE,
                "Seat holds are temporarily unavailable, please retry", request);
    }

    @ExceptionHandler(EventServiceUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleEventServiceDown(
            EventServiceUnavailableException ex, HttpServletRequest request) {
        // Logged with the cause: a 503 with no stack trace is very hard to diagnose,
        // and the cause here is usually a timeout or connection refusal.
        log.error("event-service call failed on {} {}", request.getMethod(), request.getRequestURI(), ex);
        return build(HttpStatus.SERVICE_UNAVAILABLE,
                "event-service is unavailable, please retry", request);
    }

    /** A missing X-User-Id. Without this it would surface as a 500. */
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ErrorResponse> handleMissingHeader(
            MissingRequestHeaderException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST,
                "Required header '" + ex.getHeaderName() + "' is missing", request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(
            MethodArgumentNotValidException ex, HttpServletRequest request) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return build(HttpStatus.BAD_REQUEST, message, request);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST,
                "Parameter '" + ex.getName() + "' is not a valid value", request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableBody(
            HttpMessageNotReadableException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "Request body is missing or malformed", request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(
            Exception ex, HttpServletRequest request) {
        // The client is deliberately told nothing. The server is deliberately told
        // everything: without this line a 500 leaves no trace in any log, and the
        // only evidence of the failure is the status code the caller happens to see.
        log.error("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred", request);
    }

    private static String conflictMessage(String constraint) {
        if (constraint.contains(SOLD_SEAT_CONSTRAINT)) {
            return "One or more of these seats has already been sold to another booking";
        }
        if (constraint.contains(IDEMPOTENCY_KEY_CONSTRAINT)) {
            return "A booking with this idempotency key already exists";
        }
        return "The request conflicts with existing data";
    }

    /**
     * The violated constraint's name, lower-cased. Falls back to the driver's own
     * message - MySQL names the key in it - when Hibernate extracted no name. Never null.
     */
    private static String violatedConstraint(DataIntegrityViolationException ex) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation
                    && violation.getConstraintName() != null) {
                return violation.getConstraintName().toLowerCase(Locale.ROOT);
            }
        }
        String message = ex.getMostSpecificCause().getMessage();
        return message == null ? "" : message.toLowerCase(Locale.ROOT);
    }

    private ResponseEntity<ErrorResponse> build(HttpStatus status, String message, HttpServletRequest request) {
        return ResponseEntity.status(status).body(new ErrorResponse(
                Instant.now(clock),
                status.value(),
                status.getReasonPhrase(),
                message,
                request.getRequestURI()));
    }
}
