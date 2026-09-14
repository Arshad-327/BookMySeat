package com.bookmyseat.booking;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base for tests that need a real MySQL.
 *
 * <p>Layer 3 is a unique index, so only MySQL can prove it fires: a mock would only
 * prove the test's own assumptions. This starts a throwaway mysql:8.4, the same image
 * as docker-compose.infra.yml, and Flyway applies V1 and V2 from scratch. Dev data in
 * bookmyseat-mysql is never touched.
 *
 * <p>One container per test JVM: started once in the static initializer, shared by
 * every subclass, and removed by Testcontainers' reaper when the JVM exits.
 */
public abstract class MySqlContainerTest {

    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
            .withDatabaseName("booking_db")
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
        // No background ExpiredBookingSweeper: it would change rows under assertions.
        // Tests that need a sweep call it directly.
        registry.add("app.scheduling.enabled", () -> "false");
    }
}
