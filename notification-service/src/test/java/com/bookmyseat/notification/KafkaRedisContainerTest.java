package com.bookmyseat.notification;

import com.bookmyseat.notification.dto.event.BookingConfirmedEvent;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * Base for the consumer tests: a real Kafka broker and a real Redis, both throwaway.
 *
 * <h2>Why real infrastructure and not stubs</h2>
 * What these tests check IS this service's relationship with Kafka - that a listener which
 * throws leaves the offset uncommitted, and that the event therefore comes back. A mock
 * broker would agree with whatever the code happened to do, which proves nothing about the
 * property the whole design rests on. Redis is real for the same reason: the dedupe key's
 * presence or absence after a failure is the evidence that the check-send-mark ordering is
 * what it claims to be.
 *
 * <p>apache/kafka and redis:7-alpine, the same images as docker-compose.infra.yml, so the
 * tests and the demo run against the same things.
 *
 * <h2>The topic is created here, because booking-service is not running</h2>
 * In production booking-service declares booking.confirmed at startup and the broker has
 * auto-creation off. Neither is true in this JVM, so the topic is created explicitly with the
 * one partition the real topic has - ordering and the single-threaded dedupe argument both
 * depend on that number, so a test running against a differently shaped topic would be
 * testing a different system.
 */
public abstract class KafkaRedisContainerTest {

    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.1"))
            .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");

    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    static {
        KAFKA.start();
        REDIS.start();
        createTopic();
    }

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        // Mail is a @MockBean in every test; this only stops Boot pointing at a real host.
        registry.add("spring.mail.host", () -> "localhost");
        registry.add("spring.mail.port", () -> "1025");
        // Actuator's mail health contributor requires at least one JavaMailSenderImpl bean and
        // fails context startup with "Beans must not be empty" when the only JavaMailSender is
        // a mock. It is a health check, not behaviour under test, so it is switched off here
        // rather than worked around by mocking a concrete class.
        registry.add("management.health.mail.enabled", () -> "false");
    }

    private static void createTopic() {
        Properties config = new Properties();
        config.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        try (AdminClient admin = AdminClient.create(config)) {
            admin.createTopics(List.of(new NewTopic(BookingConfirmedEvent.TOPIC, 1, (short) 1)))
                    .all().get(30, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted creating the test topic", ex);
        } catch (Exception ex) {
            throw new IllegalStateException("could not create the test topic", ex);
        }
    }

    /** Producer settings for a test publishing onto the topic by hand. */
    protected static Map<String, Object> producerConfig() {
        return Map.of(
                "bootstrap.servers", KAFKA.getBootstrapServers(),
                "key.serializer", "org.apache.kafka.common.serialization.StringSerializer",
                "value.serializer", "org.apache.kafka.common.serialization.StringSerializer",
                "acks", "all");
    }
}
