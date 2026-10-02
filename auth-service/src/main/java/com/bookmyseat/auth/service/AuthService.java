package com.bookmyseat.auth.service;

import com.bookmyseat.auth.dto.request.LoginRequest;
import com.bookmyseat.auth.dto.request.RegisterRequest;
import com.bookmyseat.auth.dto.response.AuthResponse;
import com.bookmyseat.auth.dto.response.InternalUserResponse;
import com.bookmyseat.auth.dto.response.UserResponse;
import com.bookmyseat.auth.entity.RefreshToken;
import com.bookmyseat.auth.entity.Role;
import com.bookmyseat.auth.entity.User;
import com.bookmyseat.auth.exception.DuplicateEmailException;
import com.bookmyseat.auth.exception.InvalidCredentialsException;
import com.bookmyseat.auth.exception.InvalidRefreshTokenException;
import com.bookmyseat.auth.exception.UserNotFoundException;
import com.bookmyseat.auth.mapper.UserMapper;
import com.bookmyseat.auth.repository.RefreshTokenRepository;
import com.bookmyseat.auth.repository.UserRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final EntityManager entityManager;
    private final Clock clock;

    /**
     * One message for both duplicate-email paths - the pre-check and the unique index.
     *
     * <p>Shared rather than typed twice so the two cannot drift into two different 409s for
     * one situation, which is a difference the caller would see and could do nothing with.
     */
    private static final String DUPLICATE_EMAIL_MESSAGE = "Email is already registered";

    /**
     * Registers a user. The email is checked twice, and the second check is the real one.
     *
     * <h2>The pre-check cannot prevent a duplicate, only explain one</h2>
     * {@code existsByEmail} and the insert are two statements, so two concurrent requests for
     * the same email both pass the check before either commits, and MySQL refuses the loser on
     * the UNIQUE index on {@code users.email}. That is not exotic: a double-clicked Sign Up
     * button reproduces it. Before review finding #4 the loser got 500 "An unexpected error
     * occurred", because nothing translated the constraint violation and the catch-all in
     * {@link com.bookmyseat.auth.exception.GlobalExceptionHandler} answered for it.
     *
     * <p>So the violation is translated into the same {@link DuplicateEmailException} the
     * pre-check throws, with {@link #DUPLICATE_EMAIL_MESSAGE} shared between the two throw
     * sites. Both paths are then one 409 with one message, and a caller cannot tell which of
     * them answered - which is the point, because the difference is a race it did not take
     * part in and cannot act on.
     *
     * <p>The pre-check stays. It is the common path, it costs one indexed SELECT, and it
     * produces the 409 without a failed INSERT and without a rolled-back transaction.
     *
     * <h2>Catching it INSIDE this transaction is safe here, and would not be if this
     * recovered anything</h2>
     * A constraint violation marks the transaction rollback-only, so the rule is that no
     * further work may be done in it. This catch does no further work: it throws immediately,
     * the exception propagates through the transaction interceptor, and the transaction is
     * rolled back rather than committed - so the {@code UnexpectedRollbackException} that a
     * doomed commit would produce never happens. Verified, not assumed:
     * {@code RegisterDuplicateEmailMySqlTest} drives this path against a real MySQL unique
     * index and asserts the 409.
     *
     * <p><b>Do not add a read to this catch block.</b> booking-service's
     * {@code IdempotentBookingService} has to resolve its violation by looking up the row that
     * won, and its class javadoc explains at length why that forces the catch OUTSIDE the
     * transactional method: the recovery read would run in a transaction already doomed, and
     * the commit would fail anyway. The moment this block needs to know anything about the
     * user who won the race, it has to move out here too, in that same shape.
     *
     * @throws DuplicateEmailException 409, from the pre-check or from the unique index
     */
    @Transactional
    public UserResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new DuplicateEmailException(DUPLICATE_EMAIL_MESSAGE);
        }

        User user = new User();
        user.setEmail(request.email());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setFullName(request.fullName());
        user.setRole(Role.USER);

        try {
            // created_at is DEFAULT CURRENT_TIMESTAMP(6) and insertable=false, so the
            // persisted value only exists in the database. Flush, then refresh, or
            // the response would carry a null createdAt.
            User saved = userRepository.saveAndFlush(user);
            entityManager.refresh(saved);
            return UserMapper.toResponse(saved);
        } catch (DataIntegrityViolationException ex) {
            // The UNIQUE index firing, which means the pre-check above missed: another
            // request for this email committed while this one was between the check and the
            // insert. Same answer as the pre-check gives, deliberately.
            //
            // saveAndFlush, not save, is what makes this catchable at all - the INSERT is
            // sent here rather than at commit, where it would escape this block entirely and
            // surface as a 500 from the interceptor. It was already saveAndFlush for an
            // unrelated reason (the refresh below needs the row), and this now depends on it.
            //
            // Throwing, never continuing: see the javadoc.
            throw new DuplicateEmailException(DUPLICATE_EMAIL_MESSAGE, ex);
        }
    }

    @Transactional
    public AuthResponse login(LoginRequest request) {
        // Same exception for unknown email and wrong password: the response must
        // not reveal which accounts exist.
        User user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new InvalidCredentialsException("Invalid email or password"));

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new InvalidCredentialsException("Invalid email or password");
        }

        return issueTokens(user);
    }

    /**
     * Rotates the refresh token: the presented one is revoked and a new one is
     * issued. A replayed token is therefore already revoked and rejected.
     */
    @Transactional
    public AuthResponse refresh(String rawRefreshToken) {
        RefreshToken stored = refreshTokenRepository
                .findByTokenHash(jwtService.hashRefreshToken(rawRefreshToken))
                .orElseThrow(() -> new InvalidRefreshTokenException("Refresh token is invalid"));

        if (stored.isRevoked()) {
            throw new InvalidRefreshTokenException("Refresh token has been revoked");
        }
        // Expiry is decided here, against the injected Clock - never by SQL NOW()
        // (CLAUDE.md Timekeeping).
        if (stored.getExpiresAt().isBefore(Instant.now(clock))) {
            throw new InvalidRefreshTokenException("Refresh token has expired");
        }

        stored.setRevoked(true);
        refreshTokenRepository.save(stored);

        return issueTokens(stored.getUser());
    }

    /**
     * Idempotent by design: an unknown or already-revoked token is not an error,
     * so logout never reveals whether a token existed.
     */
    @Transactional
    public void logout(String rawRefreshToken) {
        refreshTokenRepository.findByTokenHash(jwtService.hashRefreshToken(rawRefreshToken))
                .ifPresent(token -> {
                    token.setRevoked(true);
                    refreshTokenRepository.save(token);
                });
    }

    @Transactional(readOnly = true)
    public UserResponse getCurrentUser(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new InvalidCredentialsException("User no longer exists"));
        return UserMapper.toResponse(user);
    }

    /**
     * Resolves one user for another service - the email and name behind an id.
     *
     * <p>Deliberately NOT {@link #getCurrentUser}, though both read one user by id. That
     * method serves a client holding a token and throws
     * {@link com.bookmyseat.auth.exception.InvalidCredentialsException} (401) when the user is
     * gone, because the client's credential is what became worthless. This one serves a
     * service asking about a third party, presents no credential, and so throws
     * {@link UserNotFoundException} (404): the resource is absent, nobody's authorisation
     * failed. notification-service depends on that difference to tell a deleted user from its
     * own misconfiguration.
     *
     * @throws UserNotFoundException if no user has that id
     */
    @Transactional(readOnly = true)
    public InternalUserResponse findInternal(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));
        return UserMapper.toInternalResponse(user);
    }

    private AuthResponse issueTokens(User user) {
        String accessToken = jwtService.generateAccessToken(user);
        String rawRefreshToken = jwtService.generateRefreshToken();

        RefreshToken refreshToken = new RefreshToken();
        refreshToken.setUser(user);
        refreshToken.setTokenHash(jwtService.hashRefreshToken(rawRefreshToken));
        // Truncated to microseconds at assignment (CLAUDE.md Timekeeping): expires_at is
        // TIMESTAMP(6) and MySQL rounds a nanosecond Instant into it. Nothing serialises
        // this value today, so no caller could see the two disagree - and that is exactly
        // the reasoning that was true of bookings.expires_at until a frontend needed a
        // countdown. "Nobody sees both yet" is a fact about today's callers, not the code.
        //
        // The rule is uniform - every Instant written from Java to a TIMESTAMP(6) column -
        // and it is proven once, by HoldExpiryRoundTripMySqlTest in booking-service. This
        // write site has no round-trip test of its own, and that is not an oversight.
        refreshToken.setExpiresAt(
                Instant.now(clock).plus(jwtService.getRefreshTokenTtl()).truncatedTo(ChronoUnit.MICROS));
        refreshToken.setRevoked(false);
        refreshTokenRepository.save(refreshToken);

        return new AuthResponse(
                accessToken,
                rawRefreshToken,
                "Bearer",
                jwtService.getAccessTokenTtl().toSeconds()
        );
    }
}
