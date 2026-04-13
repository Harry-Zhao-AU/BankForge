package au.com.bankforge.payment.kafka;

import au.com.bankforge.payment.client.AccountServiceClient;
import au.com.bankforge.payment.dto.InitiateTransferRequest;
import au.com.bankforge.payment.dto.InitiateTransferResponse;
import au.com.bankforge.payment.entity.Transfer;
import au.com.bankforge.payment.repository.TransferRepository;
import au.com.bankforge.payment.service.PaymentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static au.com.bankforge.common.enums.TransferState.CONFIRMED;
import static au.com.bankforge.common.enums.TransferState.POSTING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Integration test for the async POSTING → CONFIRMED path.
 *
 * Flow under test:
 *   1. Initiate transfer → state machine reaches POSTING (synchronous HTTP response)
 *   2. Manually publish banking.transfer.confirmed to real Kafka container
 *   3. TransferConfirmationListener consumes → calls transferStateService.confirm()
 *   4. DB state transitions to CONFIRMED (async, polled via Awaitility)
 *
 * Uses real KafkaContainer (apache/kafka:3.9.2) + PostgreSQL + Redis via Testcontainers.
 */
@SpringBootTest
@Testcontainers
class TransferConfirmationListenerIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7.2-alpine")
            .withExposedPorts(6379);

    @Container
    static KafkaContainer kafka = new KafkaContainer(
            DockerImageName.parse("apache/kafka:3.9.2")
    );

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private TransferRepository transferRepository;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @MockitoBean
    private AccountServiceClient accountServiceClient;

    @Test
    void confirmationEvent_transitionsTransferFromPostingToConfirmed() {
        // Arrange: mock account-service succeeds
        when(accountServiceClient.executeTransfer(any(), any(), any(), any()))
                .thenReturn(new AccountServiceClient.AccountTransferResponse(
                        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                        new BigDecimal("200.00"), "COMPLETED", Instant.now()));

        InitiateTransferRequest request = new InitiateTransferRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                new BigDecimal("200.00"),
                "confirm-it-" + UUID.randomUUID(),
                "Event-driven confirmation test"
        );

        // Act 1: initiate — should reach POSTING synchronously
        InitiateTransferResponse response = paymentService.initiateTransfer(request);
        assertThat(response.state()).isEqualTo(POSTING.name());

        UUID transferId = response.transferId();

        // Act 2: simulate ledger-service publishing the confirmation event
        String confirmPayload = "{\"transferId\":\"" + transferId + "\",\"status\":\"CONFIRMED\"}";
        kafkaTemplate.send("banking.transfer.confirmed", transferId.toString(), confirmPayload);

        // Assert: TransferConfirmationListener processes the event and transitions to CONFIRMED
        await().atMost(30, TimeUnit.SECONDS).untilAsserted(() -> {
            Transfer transfer = transferRepository.findById(transferId)
                    .orElseThrow(() -> new AssertionError("Transfer not found: " + transferId));
            assertThat(transfer.getState())
                    .as("Transfer %s should be CONFIRMED after receiving confirmation event", transferId)
                    .isEqualTo(CONFIRMED);
        });
    }
}
