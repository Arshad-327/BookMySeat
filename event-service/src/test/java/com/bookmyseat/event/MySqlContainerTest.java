package com.bookmyseat.event;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base for tests that need a real MySQL.
 *
 * <p>The concurrency guarantees under test are enforced by MySQL itself - the
 * version predicate on an UPDATE, InnoDB's row counts - so a mock or an in-memory
 * database cannot prove them. This starts a throwaway mysql:8.4, the same image as
 * docker-compose.infra.yml, and Flyway migrates it from scratch. Dev data in
 * bookmyseat-mysql is never touched.
 *
 * <p>One container per test JVM: started once in the static initializer, shared by
 * every subclass, and removed by Testcontainers' reaper when the JVM exits.
 */
public abstract class MySqlContainerTest {

    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
            .withDatabaseName("event_db")
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
