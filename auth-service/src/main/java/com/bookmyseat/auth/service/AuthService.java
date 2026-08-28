package com.bookmyseat.auth.service;

import com.bookmyseat.auth.dto.request.LoginRequest;
import com.bookmyseat.auth.dto.request.RegisterRequest;
import com.bookmyseat.auth.dto.response.AuthResponse;
import com.bookmyseat.auth.dto.response.UserResponse;
import com.bookmyseat.auth.entity.RefreshToken;
import com.bookmyseat.auth.entity.Role;
import com.bookmyseat.auth.entity.User;
import com.bookmyseat.auth.exception.DuplicateEmailException;
import com.bookmyseat.auth.exception.InvalidCredentialsException;
import com.bookmyseat.auth.exception.InvalidRefreshTokenException;
import com.bookmyseat.auth.mapper.UserMapper;
import com.bookmyseat.auth.repository.RefreshTokenRepository;
import com.bookmyseat.auth.repository.UserRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final EntityManager entityManager;
    private final Clock clock;

    @Transactional
    public UserResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new DuplicateEmailException("Email is already registered");
        }

        User user = new User();
        user.setEmail(request.email());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setFullName(request.fullName());
        user.setRole(Role.USER);

        // created_at is DEFAULT CURRENT_TIMESTAMP(6) and insertable=false, so the
        // persisted value only exists in the database. Flush, then refresh, or
        // the response would carry a null createdAt.
        User saved = userRepository.saveAndFlush(user);
        entityManager.refresh(saved);
        return UserMapper.toResponse(saved);
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

    private AuthResponse issueTokens(User user) {
        String accessToken = jwtService.generateAccessToken(user);
        String rawRefreshToken = jwtService.generateRefreshToken();

        RefreshToken refreshToken = new RefreshToken();
        refreshToken.setUser(user);
        refreshToken.setTokenHash(jwtService.hashRefreshToken(rawRefreshToken));
        refreshToken.setExpiresAt(Instant.now(clock).plus(jwtService.getRefreshTokenTtl()));
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
