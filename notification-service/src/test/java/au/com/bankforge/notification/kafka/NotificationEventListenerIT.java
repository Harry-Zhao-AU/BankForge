package au.com.bankforge.notification.kafka;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;

@Testcontainers
@SpringBootTest
class NotificationEventListenerIT {

    @Container
    static KafkaContainer kafka = new KafkaContainer(
            DockerImageName.parse("apache/kafka:3.9.2")
    );

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("spring.flyway.enabled", () -> "false");
    }

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @MockitoSpyBean
    private NotificationEventListener notificationEventListener;

    @Test
    void shouldConsumeTransferEventWithoutError() {
        UUID transferId = UUID.randomUUID();
        String payload = """
            {
              "transferId": "%s",
              "fromAccountId": "%s",
              "toAccountId": "%s",
              "amount": "250.0000",
              "currency": "AUD"
            }
            """.formatted(transferId, UUID.randomUUID(), UUID.randomUUID());

        kafkaTemplate.send("banking.transfer.events", transferId.toString(), payload);

        await().atMost(30, TimeUnit.SECONDS).untilAsserted(() ->
            Mockito.verify(notificationEventListener, atLeastOnce())
                .onTransferEvent(anyString(), anyString(), anyLong())
        );
    }
}
