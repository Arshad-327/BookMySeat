package com.bookmyseat.auth;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base for tests that need a real MySQL.
 *
 * <p>The first of its kind in this service, which was otherwise all {@code @WebMvcTest}
 * slices and plain unit tests. It exists because registration's duplicate-email 409 has a
 * cause that only a database can produce: two concurrent registrations both pass the
 * {@code existsByEmail} pre-check, and the UNIQUE index on {@code users.email} refuses the
 * loser. A mocked repository can be told to throw {@code DataIntegrityViolationException},
 * which proves the translation and not that MySQL raises it - nor that the translation
 * survives being made inside a transaction the violation has already marked rollback-only.
 *
 * <p>Deliberately the same shape as event-service's and booking-service's base classes,
 * including the two timekeeping parameters, so there is one arrangement in the repo rather
 * than three. Flyway migrates the throwaway container from scratch; dev data in
 * bookmyseat-mysql is never touched.
 *
 * <p>One container per test JVM: started once in the static initializer, shared by every
 * subclass, and removed by Testcontainers' reaper when the JVM exits.
 */
public abstract class MySqlContainerTest {

    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
            .withDatabaseName("auth_db")
            // CLAUDE.md Timekeeping: the server zone matches the compose container...
            .withCommand("--default-time-zone=+00:00")
            // ...and the JDBC URL pins the same two parameters as application.yml.
            .withUrlParam("connectionTimeZone", "UTC")
            .withUrlParam("preserveInstants", "true");

    static {
        MYSQL.start();
    }

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }
}
