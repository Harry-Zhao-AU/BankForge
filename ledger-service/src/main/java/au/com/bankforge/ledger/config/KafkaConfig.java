package au.com.bankforge.ledger.config;

import jakarta.persistence.EntityManagerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.transaction.KafkaTransactionManager;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.util.backoff.ExponentialBackOff;

@Configuration(proxyBeanMethods = false)
public class KafkaConfig {

    /**
     * Re-declare the JPA transaction manager as the primary "transactionManager" bean.
     * This is required because Spring Boot auto-configuration stops registering a default
     * transactionManager bean when a second PlatformTransactionManager (KafkaTransactionManager)
     * is present in the context. Spring Data JPA's @Transactional on repository methods
     * resolves by qualifier "transactionManager", so this bean must be present by that name.
     */
    @Bean
    @Primary
    public JpaTransactionManager transactionManager(EntityManagerFactory entityManagerFactory) {
        return new JpaTransactionManager(entityManagerFactory);
    }

    @Bean
    public KafkaTransactionManager<String, String> kafkaTransactionManager(
            ProducerFactory<String, String> producerFactory) {
        return new KafkaTransactionManager<>(producerFactory);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory,
            KafkaTransactionManager<String, String> kafkaTransactionManager) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.getContainerProperties().setTransactionManager(kafkaTransactionManager);
        factory.getContainerProperties().setEosMode(ContainerProperties.EOSMode.V2);

        ExponentialBackOff backOff = new ExponentialBackOff(1000L, 2.0);
        backOff.setMaxElapsedTime(30000L);
        factory.setCommonErrorHandler(new DefaultErrorHandler(backOff));

        return factory;
    }
}
