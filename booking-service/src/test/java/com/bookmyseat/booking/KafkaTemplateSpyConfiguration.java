package com.bookmyseat.booking;

import org.mockito.Mockito;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Wraps the application's REAL, auto-configured KafkaTemplate in a Mockito spy, so a test can
 * verify how it was used while every call still goes to the genuine template.
 *
 * <p>Not {@code @SpyBean}, which fails here: Boot registers the template conditionally, and when
 * {@code @SpyBean} runs before that registration it defines a KafkaTemplate bean of its own. The
 * auto-configuration then backs off because a KafkaTemplate already exists, and Spring tries to
 * build the spy's definition with a no-argument constructor KafkaTemplate does not have. Wrapping
 * the bean after it is initialised avoids the ordering entirely.
 *
 * <p>Invocations accumulate across tests sharing a context; clear them in {@code @BeforeEach}.
 */
@TestConfiguration
public class KafkaTemplateSpyConfiguration {

    @Bean
    static BeanPostProcessor kafkaTemplateSpy() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                return bean instanceof KafkaTemplate<?, ?> ? Mockito.spy(bean) : bean;
            }
        };
    }
}
