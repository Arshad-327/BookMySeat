package com.bookmyseat.notification;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Controller;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "It is a consumer, not a service" - enforced by the build rather than by discipline.
 *
 * <h2>Why this test exists</h2>
 * notification-service keeps spring-boot-starter-web on the classpath, for the actuator
 * health endpoint and for RestClient. That makes adding a @RestController to this module a
 * one-file change that would compile, start and work - and would quietly turn a consumer into
 * a service with an HTTP surface nobody decided to expose. Nothing else in the codebase would
 * object.
 *
 * <p>So the constraint is asserted. An endpoint added here fails this test with a message
 * naming it, and whoever added it has to justify it rather than discover the rule later.
 *
 * <p>Note what is NOT claimed: this does not prove the port is closed, and it should not.
 * Actuator's own mappings are expected and are excluded by name. The property under test is
 * that this service exposes no HTTP surface OF ITS OWN.
 *
 * <p>Kafka is pointed at a dead port: the listener container retries connecting in the
 * background and never touches the application context, so no broker is needed to inspect the
 * handler mappings.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "spring.kafka.bootstrap-servers=localhost:1",
        "spring.kafka.listener.auto-startup=false"
})
class NoHttpSurfaceTest {

    @Autowired
    private ApplicationContext context;

    /**
     * Qualified by name: actuator contributes a second RequestMappingHandlerMapping
     * (controllerEndpointHandlerMapping), and it is the application's own mapping that must be
     * empty - actuator's endpoints are expected.
     */
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @Test
    @DisplayName("this module defines no @Controller or @RestController at all")
    void definesNoControllers() {
        List<String> controllers = List.of(
                        context.getBeanNamesForAnnotation(RestController.class))
                .stream()
                .toList();
        List<String> plainControllers = List.of(
                        context.getBeanNamesForAnnotation(Controller.class))
                .stream()
                // Spring's own BasicErrorController and friends are @Controller-annotated
                // infrastructure, not this module's code.
                .filter(name -> context.getType(name) != null
                        && context.getType(name).getPackageName().startsWith("com.bookmyseat"))
                .toList();

        assertThat(controllers)
                .as("notification-service is a consumer: work arrives from Kafka, not from HTTP")
                .isEmpty();
        assertThat(plainControllers)
                .as("notification-service is a consumer: work arrives from Kafka, not from HTTP")
                .isEmpty();
    }

    @Test
    @DisplayName("the only HTTP endpoints mapped are actuator's - none belong to this service")
    void mapsNoEndpointsOfItsOwn() {
        List<String> ours = handlerMapping.getHandlerMethods().entrySet().stream()
                .filter(entry -> isOurs(entry.getValue()))
                .map(entry -> entry.getKey() + " -> " + entry.getValue())
                .toList();

        assertThat(ours)
                .as("an HTTP endpoint appeared in a service that is supposed to have none")
                .isEmpty();
    }

    @Test
    @DisplayName("health is still served - the one endpoint this service does answer")
    void healthIsStillAvailable() {
        // Stated explicitly so the two tests above can never be "satisfied" by breaking
        // actuator, which is the only HTTP this service is meant to answer.
        Map<String, ?> health = context.getBeansOfType(
                org.springframework.boot.actuate.health.HealthEndpoint.class);

        assertThat(health).isNotEmpty();
    }

    private static boolean isOurs(HandlerMethod handlerMethod) {
        return handlerMethod.getBeanType().getPackageName().startsWith("com.bookmyseat");
    }
}
