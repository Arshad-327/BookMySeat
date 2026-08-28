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
import com.bookmyseat.auth.repository.RefreshTokenRepository;
import com.bookmyseat.auth.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    private static final ZoneId ZONE = ZoneId.of("UTC");
    private static final Instant FIXED_INSTANT = Instant.parse("2026-01-01T12:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_INSTANT, ZONE);

    @Mock
    private UserRepository userRepository;
    @Mock
    private RefreshTokenRepository refreshTokenRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private JwtService jwtService;
    @Mock
    private EntityManager entityManager;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        // A real fixed Clock, not a mock: time is pinned rather than stubbed.
        authService = new AuthService(
                userRepository, refreshTokenRepository, passwordEncoder, jwtService, entityManager, FIXED_CLOCK);
    }

    private User existingUser() {
        User user = new User();
        user.setId(7L);
        user.setEmail("someone@example.com");
        user.setPasswordHash("stored-bcrypt-hash");
        user.setFullName("Some One");
        user.setRole(Role.USER);
        return user;
    }

    private void stubTokenIssuing() {
        when(jwtService.generateAccessToken(any(User.class))).thenReturn("issued.access.token");
        when(jwtService.generateRefreshToken()).thenReturn("raw-refresh-token");
        when(jwtService.hashRefreshToken("raw-refresh-token")).thenReturn("hashed-refresh-token");
        when(jwtService.getRefreshTokenTtl()).thenReturn(Duration.ofDays(7));
        when(jwtService.getAccessTokenTtl()).thenReturn(Duration.ofMinutes(15));
    }

    @Test
    @DisplayName("register: persists the user with a hashed password and role USER")
    void registerSucceeds() {
        RegisterRequest request = new RegisterRequest("new@example.com", "PlainPassw0rd", "New User");
        when(userRepository.existsByEmail("new@example.com")).thenReturn(false);
        when(passwordEncoder.encode("PlainPassw0rd")).thenReturn("encoded-hash");
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(invocation -> {
            User toSave = invocation.getArgument(0);
            toSave.setId(1L);
            return toSave;
        });

        UserResponse response = authService.register(request);

        assertThat(response.id()).isEqualTo(1L);
        assertThat(response.email()).isEqualTo("new@example.com");
        assertThat(response.fullName()).isEqualTo("New User");
        assertThat(response.role()).isEqualTo("USER");

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getPasswordHash()).isEqualTo("encoded-hash");
        // The raw password must never reach the database.
        assertThat(saved.getValue().getPasswordHash()).isNotEqualTo("PlainPassw0rd");
        assertThat(saved.getValue().getRole()).isEqualTo(Role.USER);
    }

    @Test
    @DisplayName("register: a duplicate email is rejected and nothing is persisted")
    void registerRejectsDuplicateEmail() {
        RegisterRequest request = new RegisterRequest("taken@example.com", "PlainPassw0rd", "Taken");
        when(userRepository.existsByEmail("taken@example.com")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(request))
                .isInstanceOf(DuplicateEmailException.class)
                .hasMessage("Email is already registered");

        verify(userRepository, never()).saveAndFlush(any(User.class));
        verify(passwordEncoder, never()).encode(anyString());
    }

    @Test
    @DisplayName("login: correct password returns both tokens and stores the hashed refresh token")
    void loginSucceeds() {
        User user = existingUser();
        when(userRepository.findByEmail("someone@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("CorrectPassw0rd", "stored-bcrypt-hash")).thenReturn(true);
        stubTokenIssuing();

        AuthResponse response = authService.login(new LoginRequest("someone@example.com", "CorrectPassw0rd"));

        assertThat(response.accessToken()).isEqualTo("issued.access.token");
        assertThat(response.refreshToken()).isEqualTo("raw-refresh-token");
        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.expiresInSeconds()).isEqualTo(900L);

        ArgumentCaptor<RefreshToken> saved = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenRepository).save(saved.capture());
        // The stored value is the hash, never the token handed to the client.
        assertThat(saved.getValue().getTokenHash()).isEqualTo("hashed-refresh-token");
        assertThat(saved.getValue().isRevoked()).isFalse();
        // Instant arithmetic against the pinned instant, so this assertion says
        // nothing about the host's zone (CLAUDE.md Timekeeping).
        assertThat(saved.getValue().getExpiresAt())
                .isEqualTo(FIXED_INSTANT.plus(Duration.ofDays(7)));
    }

    @Test
    @DisplayName("login: a wrong password is rejected and issues no tokens")
    void loginRejectsWrongPassword() {
        User user = existingUser();
        when(userRepository.findByEmail("someone@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("WrongPassword", "stored-bcrypt-hash")).thenReturn(false);

        assertThatThrownBy(() -> authService.login(new LoginRequest("someone@example.com", "WrongPassword")))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessage("Invalid email or password");

        verify(refreshTokenRepository, never()).save(any(RefreshToken.class));
        verify(jwtService, never()).generateAccessToken(any(User.class));
    }

    @Test
    @DisplayName("refresh: rotates - revokes the presented token and issues a new one")
    void refreshRotatesToken() {
        User user = existingUser();
        RefreshToken stored = new RefreshToken();
        stored.setId(11L);
        stored.setUser(user);
        stored.setTokenHash("hash-of-presented");
        stored.setExpiresAt(FIXED_INSTANT.plus(Duration.ofDays(3)));
        stored.setRevoked(false);

        when(jwtService.hashRefreshToken("presented-token")).thenReturn("hash-of-presented");
        when(refreshTokenRepository.findByTokenHash("hash-of-presented")).thenReturn(Optional.of(stored));
        stubTokenIssuing();

        AuthResponse response = authService.refresh("presented-token");

        assertThat(response.accessToken()).isEqualTo("issued.access.token");
        assertThat(response.refreshToken()).isEqualTo("raw-refresh-token");

        ArgumentCaptor<RefreshToken> saved = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenRepository, Mockito.times(2)).save(saved.capture());
        List<RefreshToken> allSaved = saved.getAllValues();

        // First save revokes the token that was presented.
        assertThat(allSaved.get(0).getId()).isEqualTo(11L);
        assertThat(allSaved.get(0).isRevoked()).isTrue();

        // Second save is a distinct, live token.
        assertThat(allSaved.get(1).getId()).isNull();
        assertThat(allSaved.get(1).isRevoked()).isFalse();
        assertThat(allSaved.get(1).getTokenHash()).isEqualTo("hashed-refresh-token");
        assertThat(allSaved.get(1).getTokenHash()).isNotEqualTo(allSaved.get(0).getTokenHash());
    }

    @Test
    @DisplayName("refresh: an already-revoked token is rejected")
    void refreshRejectsRevokedToken() {
        RefreshToken revoked = new RefreshToken();
        revoked.setId(12L);
        revoked.setUser(existingUser());
        revoked.setTokenHash("hash-of-revoked");
        revoked.setExpiresAt(FIXED_INSTANT.plus(Duration.ofDays(3)));
        revoked.setRevoked(true);

        when(jwtService.hashRefreshToken("revoked-token")).thenReturn("hash-of-revoked");
        when(refreshTokenRepository.findByTokenHash("hash-of-revoked")).thenReturn(Optional.of(revoked));

        assertThatThrownBy(() -> authService.refresh("revoked-token"))
                .isInstanceOf(InvalidRefreshTokenException.class)
                .hasMessage("Refresh token has been revoked");

        verify(refreshTokenRepository, never()).save(any(RefreshToken.class));
        verify(jwtService, never()).generateAccessToken(any(User.class));
    }
}
