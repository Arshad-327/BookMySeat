package com.bookmyseat.auth.config;

import com.bookmyseat.auth.dto.response.ErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Clock;
import java.time.Instant;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig implements WebMvcConfigurer {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final ObjectMapper objectMapper;

    /** Injected rather than Instant.now() so time is never read off the host clock. */
    private final Clock clock;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/api/auth/register",
                                "/api/auth/login",
                                "/api/auth/refresh",
                                // Public on purpose: the refresh token in the body IS the
                                // credential being revoked. Requiring a valid access token
                                // would make it impossible to log out once the access token
                                // has expired, which is exactly when clients log out.
                                "/api/auth/logout")
                        .permitAll()
                        .requestMatchers(
                                "/swagger-ui/**",
                                "/swagger-ui.html",
                                "/v3/api-docs/**")
                        .permitAll()
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        // Service-to-service, deliberately unauthenticated - the same trust
                        // model as event-service's /api/internal/**. A caller here is another
                        // service, which holds no token of its own and has no user to act as;
                        // requiring one would mean minting service credentials, which this
                        // project does not have and does not need while the only protection
                        // that matters is network reachability.
                        //
                        // THAT protection is the whole of it: api-gateway has no route for
                        // /api/internal/**, so these paths are reachable only on the Docker
                        // network by service name. RoutingTableTest pins that, per endpoint.
                        // Permitting this pattern and routing it publicly would, together,
                        // publish every user's email address; neither half is safe alone.
                        .requestMatchers("/api/internal/**").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex
                        // Without these, failures raised inside the filter chain never reach
                        // @RestControllerAdvice and would return a body unlike every other error.
                        .authenticationEntryPoint((request, response, authException) ->
                                writeError(response, request.getRequestURI(),
                                        HttpStatus.UNAUTHORIZED, "Authentication is required"))
                        .accessDeniedHandler((request, response, deniedException) ->
                                writeError(response, request.getRequestURI(),
                                        HttpStatus.FORBIDDEN, "Access is denied")))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    private void writeError(HttpServletResponse response, String path, HttpStatus status, String message)
            throws java.io.IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), new ErrorResponse(
                Instant.now(clock),
                status.value(),
                status.getReasonPhrase(),
                message,
                path));
    }
}
