package com.bookmyseat.auth.exception;

import com.bookmyseat.auth.dto.response.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

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

    @ExceptionHandler(DuplicateEmailException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateEmail(
            DuplicateEmailException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, ex.getMessage(), request);
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ErrorResponse> handleInvalidCredentials(
            InvalidCredentialsException ex, HttpServletRequest request) {
        return build(HttpStatus.UNAUTHORIZED, ex.getMessage(), request);
    }

    @ExceptionHandler(InvalidRefreshTokenException.class)
    public ResponseEntity<ErrorResponse> handleInvalidRefreshToken(
            InvalidRefreshTokenException ex, HttpServletRequest request) {
        return build(HttpStatus.UNAUTHORIZED, ex.getMessage(), request);
    }

    /**
     * 404, not the 401 that a missing user gets on /api/auth/me. See
     * {@link UserNotFoundException} - notification-service classifies on this status.
     */
    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleUserNotFound(
            UserNotFoundException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, ex.getMessage(), request);
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
     * Malformed or absent JSON body. Otherwise a 500.
     *
     * <h2>Why this was a 500, and why that is the interesting part</h2>
     * {@code POST /api/auth/login} with {@code {"email":} answered 500 "An unexpected error
     * occurred". Nothing here handled {@link HttpMessageNotReadableException}, and this class
     * does not extend {@code ResponseEntityExceptionHandler}, so Spring's own 400 was never
     * reached: {@code ExceptionHandlerExceptionResolver} runs before
     * {@code DefaultHandlerExceptionResolver}, the {@code Exception.class} catch-all below
     * matched first, and the framework's mapping never got a chance. A catch-all in an advice
     * silently outranks every default Spring would otherwise apply.
     *
     * <p>Identical to the handlers in booking-service and event-service, message included -
     * this was auth-service being the odd one out, not a third opinion about what to say.
     *
     * <p>Covers three shapes that all arrive as this one exception: syntactically broken JSON,
     * an absent body, and a field whose JSON type cannot be bound (an object where a string
     * belongs). Review finding #4.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableBody(
            HttpMessageNotReadableException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "Request body is missing or malformed", request);
    }

    /**
     * Anything with no handler of its own. Always logged with its stack trace.
     *
     * <p><b>Adding a handler above is how a 500 becomes the right status.</b> Because this
     * catch-all is consulted before Spring's defaults, every exception the framework would
     * have mapped itself arrives here instead. Known to still land here, each answering 500
     * where the framework would have answered better - none of them fixed in the commit that
     * wrote this comment, and none of them a surprise any more:
     *
     * <ul>
     *   <li>{@code HttpMediaTypeNotSupportedException} - a body sent as text/plain, or with no
     *       Content-Type at all. Should be 415.
     *   <li>{@code HttpRequestMethodNotSupportedException} - e.g. GET on /api/auth/login.
     *       Should be 405.
     *   <li>{@code MethodArgumentTypeMismatchException} - a non-numeric path id, e.g.
     *       /api/internal/users/abc. Should be 400, and both booking-service and
     *       event-service do map it.
     * </ul>
     *
     * <p>Not in that list, because it never reaches here: an unknown path. Spring Security's
     * {@code anyRequest().authenticated()} answers 401 for it before routing happens, so a
     * typo'd URL reads as "Authentication is required" rather than as 404. That is a
     * SecurityConfig question, not a handler one.
     */
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
