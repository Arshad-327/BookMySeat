package com.bookmyseat.auth.exception;

import com.bookmyseat.auth.dto.response.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Clock;
import java.util.List;
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
     * A path no controller serves. 404, and it used to be a 500.
     *
     * <h2>Which exception, and why there are two in the annotation</h2>
     * The one that actually arrives is {@link NoResourceFoundException}, not the
     * NoHandlerFoundException the name of the problem suggests. With static-resource
     * mappings on (spring.web.resources.add-mappings, true by default and not overridden
     * here) Spring MVC registers a resource handler on /**, so a request that matches no
     * controller is not handler-less: it is handed to the resource handler, which finds no
     * file and throws this. Measured, not assumed - the stack trace the catch-all logged
     * read "NoResourceFoundException: No static resource api/...".
     *
     * <p>{@link NoHandlerFoundException} is listed too because it is what the same request
     * raises if those mappings are ever turned off. An API has no static resources and
     * somebody may reasonably disable them; that must not quietly turn 404s back into 500s.
     *
     * <h2>Why it was a 500</h2>
     * The same reason 405 and 415 were: the {@code Exception.class} catch-all below is
     * consulted before Spring's own resolver, so the framework's 404 never got to answer.
     * A 500 says the server broke. The truth is that the route does not exist.
     *
     * <h2>Only reachable past Spring Security</h2>
     * {@code anyRequest().authenticated()} refuses an unauthenticated request before the
     * dispatcher looks for a handler, so an unknown path with no valid token is still 401
     * "Authentication is required" and never gets here. That is deliberate and stays: an
     * anonymous caller has not earned being told which paths exist. This handler answers
     * the caller who IS authenticated, or who asked under a permitted prefix.
     *
     * <p>Not logged. A mistyped URL is the caller's mistake, and a stack trace per typo is
     * how a log stops being read.
     */
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<ErrorResponse> handleNoSuchPath(Exception ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND,
                "No endpoint for " + request.getMethod() + " " + request.getRequestURI(), request);
    }

    /**
     * Anything with no handler of its own. Always logged with its stack trace.
     *
     * <p><b>Adding a handler above is how a 500 becomes the right status.</b> Because this
     * catch-all is consulted before Spring's defaults, every exception the framework would
     * have mapped itself arrives here instead. The three that used to land here - 415, 405 and
     * a non-numeric path id - now have handlers of their own above, and
     * {@code FrameworkErrorMappingTest} keeps them there.
     *
     * <p>Nothing known is left falling through. Anything that reaches this now is genuinely
     * unexpected, which is the state it should be in: a 500 from here should send someone to
     * the log, not to this javadoc.
     *
     * <p>THIS USED TO SAY an unknown path "never reaches here", because Spring Security's
     * {@code anyRequest().authenticated()} answers 401 for it before routing. That was half
     * true, and the false half was a 500: it holds only for a request with no valid token.
     * An AUTHENTICATED caller's typo'd URL sailed past Spring Security, found no controller,
     * and landed here as NoResourceFoundException. It has a handler of its own now, above -
     * see {@code handleNoSuchPath}. The 401 for an unauthenticated caller is unchanged.
     */
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

    /**
     * A non-numeric path id, e.g. /api/internal/users/abc. Without this it would be a 500.
     *
     * <p>Word for word what booking-service and event-service already answer. auth-service
     * was the only one of the three missing it, so this is the drift being closed rather than
     * a third opinion about the wording.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST,
                "Parameter '" + ex.getName() + "' is not a valid value", request);
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
