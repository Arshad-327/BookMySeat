package com.bookmyseat.auth.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI authServiceOpenApi() {
        return new OpenAPI().info(new Info()
                .title("BookMySeat - auth-service")
                .version("v1")
                .description("""
                        Accounts and tokens. The only service that issues a JWT, and the \
                        only one that stores a password.

                        **Register and log in** - `POST /api/auth/register` creates a \
                        `USER`; there is no endpoint that creates an `ADMIN`. \
                        `POST /api/auth/login` returns a short-lived access token and a \
                        refresh token.

                        **Refresh rotates** - `POST /api/auth/refresh` revokes the token \
                        presented and issues a new one, so a refresh token replayed after \
                        use is already revoked and is refused. `POST /api/auth/logout` \
                        revokes without issuing, and is idempotent: an unknown or \
                        already-revoked token is not an error.

                        **Who the caller is** - `GET /api/auth/me` reads the access token \
                        and answers 401 when it is missing, invalid or expired. \
                        api-gateway verifies the same token with the same secret and sets \
                        `X-User-Id` and `X-User-Role` from it for every other service.

                        `GET /api/internal/users/{id}` is service-to-service only, for \
                        notification-service, and returns id, email and name. It answers \
                        **404** for an unknown id where `/me` answers 401, and that \
                        difference is load-bearing: it is how a consumer tells a deleted \
                        user, which can never succeed, from a lookup that failed and \
                        should be retried. Like every `/api/internal/**` path it has no \
                        gateway route and is reachable only on the internal network."""));
    }
}
