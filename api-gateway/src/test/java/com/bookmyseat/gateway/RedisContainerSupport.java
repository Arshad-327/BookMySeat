package com.bookmyseat.gateway;

import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * One throwaway redis:7-alpine per test JVM, shared by the rate-limit tests. Same image as
 * docker-compose.infra.yml. Never the dev Redis on 6379.
 */
final class RedisContainerSupport {

    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    static {
        REDIS.start();
    }

    private RedisContainerSupport() {
    }

    static String host() {
        return REDIS.getHost();
    }

    static int port() {
        return REDIS.getMappedPort(6379);
    }

    /** redis-cli inside the container, so the check does not go through the client under test. */
    static String cli(String... args) {
        String[] command = new String[args.length + 1];
        command[0] = "redis-cli";
        System.arraycopy(args, 0, command, 1, args.length);
        try {
            Container.ExecResult result = REDIS.execInContainer(command);
            return result.getStdout().trim();
        } catch (IOException ex) {
            throw new UncheckedIOException("redis-cli failed", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted running redis-cli", ex);
        }
    }
}
