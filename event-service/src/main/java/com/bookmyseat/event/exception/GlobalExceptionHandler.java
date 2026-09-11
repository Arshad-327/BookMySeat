package com.bookmyseat.event.exception;

import com.bookmyseat.event.dto.response.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.Clock;
import java.time.Instant;
import java.util.stream.Collectors;

/**
 * One error shape for the whole service: { timestamp, status, error, message, path }.
 */
@RestControllerAdvice
@RequiredArgsConstructor
@Slf4j
public class GlobalExceptionHandler {

    /** Injected rather than Instant.now() so time is never read off the host clock. */
    private final Clock clock;

    @ExceptionHandler(EventNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleEventNotFound(
            EventNotFoundException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, ex.getMessage(), request);
    }

    @ExceptionHandler(ShowNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleShowNotFound(
            ShowNotFoundException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, ex.getMessage(), request);
    }

    @ExceptionHandler(VenueNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleVenueNotFound(
            VenueNotFoundException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, ex.getMessage(), request);
    }

    @ExceptionHandler(MissingRoleException.class)
    public ResponseEntity<ErrorResponse> handleMissingRole(
            MissingRoleException ex, HttpServletRequest request) {
        return build(HttpStatus.UNAUTHORIZED, ex.getMessage(), request);
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<ErrorResponse> handleForbidden(
            ForbiddenException ex, HttpServletRequest request) {
        return build(HttpStatus.FORBIDDEN, ex.getMessage(), request);
    }

    @ExceptionHandler({SeatsAlreadyExistException.class, VenueHasNoSeatsException.class,
            SeatsAlreadyBookedException.class})
    public ResponseEntity<ErrorResponse> handleConflict(
            RuntimeException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, ex.getMessage(), request);
    }

    /** Requested show_seats ids that are not in the show: the short count, rejected. */
    @ExceptionHandler(ShowSeatsNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleShowSeatsNotFound(
            ShowSeatsNotFoundException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, ex.getMessage(), request);
    }

    /**
     * The database refusing a duplicate, most often uq_seats_venue_row_number.
     *
     * <p>Two concurrent seat-generation calls can both pass the "does this venue
     * have seats" check before either commits. The unique constraint is what
     * actually prevents the duplicate; this turns that into the same 409 the
     * pre-check would have produced, rather than a 500.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrity(
            DataIntegrityViolationException ex, HttpServletRequest request) {
        log.warn("Constraint violation on {} {}", request.getMethod(), request.getRequestURI(), ex);
        return build(HttpStatus.CONFLICT,
                "The request conflicts with existing data", request);
    }

    /**
     * Two transactions tried to write the same show_seats row concurrently.
     *
     * <p>Reachable since the booking write moved from a bulk JPQL UPDATE to managed
     * entities: each UPDATE now carries {@code WHERE version = ?}, so the second
     * writer matches zero rows and Hibernate raises this. That is the optimistic
     * lock doing its job, and it is caller-visible contention rather than a server
     * fault - so 409, not 500.
     *
     * <p>Handled here regardless of how often it fires. An unhandled exception
     * reaching the servlet container is a defect whether or not it is currently
     * reachable: it answers 500 and leaks a stack trace to the caller.
     *
     * <p>This is layer 2 firing. Proven rather than assumed: ShowSeatOptimisticLockTest
     * makes a stale version cause exactly this exception, against real MySQL.
     */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ErrorResponse> handleOptimisticLock(
            ObjectOptimisticLockingFailureException ex, HttpServletRequest request) {
        log.warn("Optimistic lock conflict on {} {}", request.getMethod(), request.getRequestURI(), ex);
        return build(HttpStatus.CONFLICT,
                "The seat was modified by another booking; please retry", request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(
            MethodArgumentNotValidException ex, HttpServletRequest request) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return build(HttpStatus.BAD_REQUEST, message, request);
    }

    /** Caller-supplied values that pass field validation but are wrong together, e.g. duplicate row labels. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(
            IllegalArgumentException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, ex.getMessage(), request);
    }

    /** Malformed or absent JSON body. Otherwise a 500. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableBody(
            HttpMessageNotReadableException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "Request body is missing or malformed", request);
    }

    /** A non-numeric path id, e.g. /api/events/abc. Without this it would surface as a 500. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST,
                "Parameter '" + ex.getName() + "' is not a valid value", request);
    }

    /**
     * An unknown Pageable sort property arrives here as PropertyReferenceException
     * wrapped by Spring Data. It is caller error, not a server fault, so it must
     * not be reported as a 500.
     */
    @ExceptionHandler(org.springframework.data.mapping.PropertyReferenceException.class)
    public ResponseEntity<ErrorResponse> handleBadSort(
            org.springframework.data.mapping.PropertyReferenceException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "Unknown sort property: " + ex.getPropertyName(), request);
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

    private ResponseEntity<ErrorResponse> build(HttpStatus status, String message, HttpServletRequest request) {
        return ResponseEntity.status(status).body(new ErrorResponse(
                Instant.now(clock),
                status.value(),
                status.getReasonPhrase(),
                message,
                request.getRequestURI()));
    }
}
