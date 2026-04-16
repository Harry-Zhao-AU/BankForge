package au.com.bankforge.ledger.config;

import jakarta.persistence.EntityManagerFactory;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.util.backoff.ExponentialBackOff;

@Configuration(proxyBeanMethods = false)
public class KafkaConfig {

    /**
     * Re-declare the JPA transaction manager as the primary "transactionManager" bean.
     * This is required because Spring Boot auto-configuration stops registering a default
     * transactionManager bean when multiple PlatformTransactionManager beans are present.
     * Spring Data JPA's @Transactional on repository methods resolves by qualifier
     * "transactionManager", so this bean must be present by that name.
     */
    @Bean
    @Primary
    public JpaTransactionManager transactionManager(EntityManagerFactory entityManagerFactory) {
        return new JpaTransactionManager(entityManagerFactory);
    }

    /**
     * Kafka listener container factory with DLT recoverer and exponential backoff.
     *
     * EOS/KafkaTransactionManager removed (D-06, D-07): DeadLetterPublishingRecoverer requires
     * a non-transactional KafkaTemplate. The auto-configured template is non-transactional
     * after removing transaction-id-prefix from application.yml.
     *
     * DLT routing (D-10): Exhausted-retry messages are routed to banking.transfer.events.DLT.
     * Partition is preserved so DLT consumers can correlate with the original partition.
     *
     * Backoff (D-11): 1s initial, 2x multiplier, 30s max elapsed — ~3 attempts before DLT routing.
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory,
            KafkaTemplate<String, String> kafkaTemplate) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);

        DeadLetterPublishingRecoverer recoverer =
                new DeadLetterPublishingRecoverer(kafkaTemplate,
                        (r, e) -> new TopicPartition("banking.transfer.events.DLT", r.partition()));

        ExponentialBackOff backOff = new ExponentialBackOff(1000L, 2.0);
        backOff.setMaxElapsedTime(30000L);
        factory.setCommonErrorHandler(new DefaultErrorHandler(recoverer, backOff));

        return factory;
    }
}
