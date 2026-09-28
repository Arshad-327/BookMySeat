package com.bookmyseat.event.exception;

import com.bookmyseat.event.dto.response.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.time.Instant;
import java.util.stream.Collectors;

/**
 * One error shape for the whole service: { timestamp, status, error, message, path }.
 *
 * <h2>The catch-all below outranks every default Spring would apply</h2>
 * {@code ExceptionHandlerExceptionResolver} is consulted before
 * {@code DefaultHandlerExceptionResolver}, so the {@code @ExceptionHandler(Exception.class)}
 * method at the bottom of this class matches first, and every framework exception Spring
 * would have mapped itself arrives there as a 500 instead. That is why 405, 415 and a
 * non-numeric path id are handled explicitly here: not because Spring cannot map them, but
 * because this class stops it from getting the chance.
 *
 * <h2>Why this does not extend ResponseEntityExceptionHandler</h2>
 * That class would map all three at once, and it was tried. It does not fit, and the reason
 * was measured rather than assumed:
 *
 * <ul>
 *   <li><b>The context does not start.</b> It declares ONE {@code final} handler method
 *       covering a list of exception types that includes
 *       {@code MethodArgumentNotValidException} and {@code HttpMessageNotReadableException},
 *       both handled explicitly in this class. The result is {@code IllegalStateException:
 *       Ambiguous @ExceptionHandler method mapped for [class
 *       org.springframework.web.bind.MethodArgumentNotValidException]} at startup.
 *   <li><b>Deleting those to resolve it would change their bodies.</b> Its responses are
 *       built through {@code createProblemDetail}: RFC 7807
 *       { type, title, status, detail, instance }, not the shape above. Its
 *       {@code handleException} is {@code final}, so restoring the shape means overriding
 *       {@code handleExceptionInternal} and rebuilding the body - more code than the three
 *       handlers it would have saved, and the shape logic would move out of {@code build}
 *       into an override.
 * </ul>
 *
 * <p>Every service in this repo emits the same five fields, so a client that can parse one
 * service's errors can parse all of them. That consistency is worth three explicit handlers.
 */
@RestControllerAdvice
@RequiredArgsConstructor
@Slf4j
public class GlobalExceptionHandler {

    /** Constraint names from V1__initial_schema.sql. */
    private static final String VENUE_SEAT_CONSTRAINT = "uq_seats_venue_row_number";
    private static final String SHOW_SEAT_CONSTRAINT = "uq_show_seats_show_seat";

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

    /**
     * The conflicts: a state that refuses the request, where the request itself is fine.
     *
     * <p>{@link ShowAlreadyStartedException} belongs with these rather than with the 400s.
     * Its request names a real show and real seats and is correctly formed; what refuses it
     * is the clock, which the caller cannot edit its way past.
     */
    @ExceptionHandler({SeatsAlreadyExistException.class, VenueHasNoSeatsException.class,
            SeatsAlreadyBookedException.class, ShowAlreadyStartedException.class})
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
     *
     * <h2>The message names the rule, and this service used to answer generically</h2>
     * booking-service has always chosen its message from the violated constraint while this
     * one answered "The request conflicts with existing data" for everything. Two services
     * disagreeing about one 409, and the naming side won:
     *
     * <ul>
     *   <li><b>It leaks nothing.</b> The constraint NAME never reaches the caller - it goes in
     *       the log line below. What the caller gets is a hand-written sentence, one per rule,
     *       saying which rule it hit. There is no SQL, no column list and no schema in the
     *       response, and nothing a client could not already infer from having received a 409.
     *   <li><b>It makes the pre-check and the constraint agree.</b> AdminVenueService's javadoc
     *       already claimed this handler "turns that violation into the same 409" as
     *       {@link SeatsAlreadyExistException}; with a generic message that was only true of
     *       the status code. Now both paths say a venue already has seats.
     *   <li><b>The generic answer is still there, unchanged, as the fallback</b> - byte for
     *       byte the sentence this method used to return for everything. An unrecognised
     *       constraint is no worse off than before, which is what makes this purely additive.
     * </ul>
     *
     * <p>The one risk the generic version does not carry is in {@link #violatedConstraint}:
     * when Hibernate extracted no name it falls back to substring-matching the driver's
     * message. A wrong guess there costs a less specific message and never a wrong status, and
     * it fails to the generic sentence. Judged worth it - the same trade booking-service has
     * been running on.
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

    /**
     * The wrong HTTP method for a path. 405, and it used to be a 500 - see the class javadoc.
     *
     * <p><b>Allow is not decoration.</b> RFC 9110 requires a 405 to name the methods the path
     * does support, and Spring's own handler sets it. Hand-writing this one means setting it
     * here or silently dropping something the framework was already doing.
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED);
        Set<HttpMethod> allowed = ex.getSupportedHttpMethods();
        if (allowed != null && !allowed.isEmpty()) {
            response.allow(allowed.toArray(new HttpMethod[0]));
        }
        return response.body(errorBody(HttpStatus.METHOD_NOT_ALLOWED,
                "Method " + ex.getMethod() + " is not supported for this path", request));
    }

    /**
     * A body sent as something other than JSON. 415, and it used to be a 500 - see the class
     * javadoc.
     *
     * <p>{@code Accept} lists what the endpoint would have taken, mirroring what Spring's own
     * handler sets.
     *
     * <h2>What {@code getContentType()} actually returns, measured</h2>
     * Both branches below exist because the obvious assumption was wrong:
     *
     * <ul>
     *   <li>A request with <b>no Content-Type header at all</b> does NOT arrive with null.
     *       Spring defaults it to {@code application/octet-stream}, so it is reported as an
     *       unsupported type like any other.
     *   <li>Null means the value could not be PARSED - "application/", "not-a-media-type".
     *       That is the only way to reach the first branch.
     * </ul>
     *
     * <p>Parameters are stripped from the echoed value, so {@code text/plain} and
     * {@code text/plain;charset=UTF-8} produce the same message. The charset is never the
     * reason the request was refused, and leaving it in made the message depend on whether the
     * caller happened to send one.
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMediaTypeNotSupported(
            HttpMediaTypeNotSupportedException ex, HttpServletRequest request) {
        MediaType contentType = ex.getContentType();
        String message = contentType == null
                ? "Content-Type is not a valid media type"
                : "Content-Type '"
                        + new MediaType(contentType.getType(), contentType.getSubtype())
                        + "' is not supported";

        ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        List<MediaType> supported = ex.getSupportedMediaTypes();
        if (!supported.isEmpty()) {
            // Set as a raw header, not through a builder method: ResponseEntity.BodyBuilder has
            // allow() but no accept() - accept() belongs to RequestEntity.BodyBuilder, where it
            // states what the CALLER will take. This is the response side saying what the
            // endpoint would have accepted, which is what Spring's own resolver puts here.
            response.header(HttpHeaders.ACCEPT, MediaType.toString(supported));
        }
        return response.body(errorBody(HttpStatus.UNSUPPORTED_MEDIA_TYPE, message, request));
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

    /**
     * One sentence per rule the caller can actually hit, and the old generic one otherwise.
     *
     * <p>Deliberately not the constraint name itself: the name is a schema detail and belongs
     * in the log, while the caller needs to know what to do differently. The wording of the
     * first case mirrors {@link SeatsAlreadyExistException}, which is the pre-check for the
     * same rule - minus the venue id, which a constraint violation does not carry.
     */
    private static String conflictMessage(String constraint) {
        if (constraint.contains(VENUE_SEAT_CONSTRAINT)) {
            return "This venue already has seats; generating again would duplicate them";
        }
        if (constraint.contains(SHOW_SEAT_CONSTRAINT)) {
            return "Seats for this show have already been created";
        }
        return "The request conflicts with existing data";
    }

    /**
     * The violated constraint's name, lower-cased. Falls back to the driver's own message -
     * MySQL names the key in it - when Hibernate extracted no name. Never null.
     *
     * <p>Identical to booking-service's, deliberately: two services reading the same exception
     * from the same driver should not have two ways of finding out which rule fired. There is
     * no shared module to put it in (CLAUDE.md keeps services independent), so it is duplicated
     * knowingly rather than abstracted across a boundary that does not exist.
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
        return ResponseEntity.status(status).body(errorBody(status, message, request));
    }

    /**
     * The five fields alone, for the handlers that also set a header.
     *
     * <p>Split out of {@link #build} rather than duplicated: 405 has to carry Allow and 415
     * carries Accept, so neither can use a helper that has already finished the
     * ResponseEntity. One place still decides what the body is.
     */
    private ErrorResponse errorBody(HttpStatus status, String message, HttpServletRequest request) {
        return new ErrorResponse(
                Instant.now(clock),
                status.value(),
                status.getReasonPhrase(),
                message,
                request.getRequestURI());
    }
}
