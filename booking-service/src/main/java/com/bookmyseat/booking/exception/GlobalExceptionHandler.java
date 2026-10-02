package com.bookmyseat.booking.exception;

import com.bookmyseat.booking.dto.response.ErrorResponse;
import com.bookmyseat.booking.dto.response.SeatConflictResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.core.ResolvableType;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.Clock;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.time.Instant;
import java.util.Locale;
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
     * A lapsed or stolen hold, a confirm on a booking that is not PENDING, a show that has
     * already started, and event-service refusing to mark the seats BOOKED (layer 2 firing
     * over there).
     *
     * <p>{@link ShowAlreadyStartedException} belongs here rather than with the 400s: the
     * request is correctly formed and names a real show, and the caller cannot edit its way
     * past the clock. A started show refused at CONFIRM arrives as
     * {@link SeatBookingRejectedException} instead, because event-service is what refuses it
     * there - same status, same group, one line further up.
     */
    @ExceptionHandler({HoldExpiredException.class, BookingNotPendingException.class,
            ShowAlreadyStartedException.class, SeatBookingRejectedException.class})
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
        // The throw site's wording, not one sentence for both paths. A hold that failed
        // wrote nothing; a confirm that failed may have booked the seats and lost the
        // answer, and must not be reported as though nothing happened. See
        // EventServiceUnavailableException.
        return build(HttpStatus.SERVICE_UNAVAILABLE, ex.getUserMessage(), request);
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

    /**
     * A path id or query parameter that cannot be converted to its type.
     *
     * <p>For an ENUM parameter the message lists what would have been accepted. "Is not a
     * valid value" is a fair thing to say about {@code /api/bookings/abc}, where the valid
     * values are every number; said about {@code ?status=confirmed} it sends the caller off
     * to find documentation for a list of four words that could have been in the response.
     *
     * <p>This is also what makes strict matching reasonable. {@code status} is exact and
     * upper-case, and the cost of that strictness is one 400 that tells you the fix - see
     * {@code BookingController#canonicalKey} for why that is the right trade here and the
     * wrong one for an Idempotency-Key.
     *
     * <p>The enum is found through the parameter's generic type because {@code status} is a
     * {@code List<BookingStatus>}: {@code ex.getRequiredType()} is {@code List}, which says
     * nothing about what belongs in it.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        Class<?> enumType = enumTypeOf(ex);
        if (enumType != null) {
            String allowed = Arrays.stream(enumType.getEnumConstants())
                    .map(constant -> ((Enum<?>) constant).name())
                    .collect(Collectors.joining(", "));
            return build(HttpStatus.BAD_REQUEST,
                    "Parameter '" + ex.getName() + "' must be one of " + allowed, request);
        }
        return build(HttpStatus.BAD_REQUEST,
                "Parameter '" + ex.getName() + "' is not a valid value", request);
    }

    /** The enum a parameter takes - itself, or as the element of a collection - or null. */
    private static Class<?> enumTypeOf(MethodArgumentTypeMismatchException ex) {
        ResolvableType type = ResolvableType.forMethodParameter(ex.getParameter());
        Class<?> raw = type.resolve();
        if (raw != null && Collection.class.isAssignableFrom(raw)) {
            raw = type.asCollection().getGeneric(0).resolve();
        }
        return raw != null && raw.isEnum() ? raw : null;
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableBody(
            HttpMessageNotReadableException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "Request body is missing or malformed", request);
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
