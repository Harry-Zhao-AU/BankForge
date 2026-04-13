package au.com.bankforge.payment.service;

import au.com.bankforge.payment.client.AccountServiceClient;
import au.com.bankforge.payment.dto.InitiateTransferRequest;
import au.com.bankforge.payment.dto.InitiateTransferResponse;
import au.com.bankforge.payment.entity.Transfer;
import au.com.bankforge.payment.repository.TransferRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static au.com.bankforge.common.enums.TransferState.CANCELLED;
import static au.com.bankforge.common.enums.TransferState.POSTING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Integration tests for PaymentService with mocked AccountServiceClient.
 *
 * Uses Testcontainers PostgreSQL (for JPA/Flyway) and Redis (for idempotency).
 * AccountServiceClient is mocked — no real account-service needed.
 *
 * Tests cover:
 *   - Happy path: full state machine progression to CONFIRMED
 *   - Idempotency: duplicate key returns cached response, account-service not called twice
 *   - Failure path: exception from account-service results in CANCELLED state
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class PaymentServiceTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7.2-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:29092");
    }

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private TransferRepository transferRepository;

    @MockitoBean
    private AccountServiceClient accountServiceClient;

    private InitiateTransferRequest buildRequest(String idempotencyKey) {
        return new InitiateTransferRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                new BigDecimal("100.00"),
                idempotencyKey,
                "Test transfer"
        );
    }

    @Test
    void initiateTransfer_happyPath_finalStateIsPosting() {
        // Arrange: mock successful account-service response
        when(accountServiceClient.executeTransfer(any(), any(), any(), any()))
                .thenReturn(new AccountServiceClient.AccountTransferResponse(
                        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                        new BigDecimal("100.00"), "COMPLETED", Instant.now()));

        InitiateTransferRequest request = buildRequest("happy-path-key-" + UUID.randomUUID());

        // Act
        InitiateTransferResponse response = paymentService.initiateTransfer(request);

        // Assert response state
        assertThat(response.state()).isEqualTo(POSTING.name());
        assertThat(response.transferId()).isNotNull();
        assertThat(response.amount()).isEqualByComparingTo(new BigDecimal("100.00"));

        // Assert DB state
        Optional<Transfer> dbTransfer = transferRepository.findById(response.transferId());
        assertThat(dbTransfer).isPresent();
        assertThat(dbTransfer.get().getState()).isEqualTo(POSTING);

        // Verify account-service was called exactly once
        verify(accountServiceClient, times(1)).executeTransfer(any(), any(), any(), any());
    }

    @Test
    void initiateTransfer_duplicateIdempotencyKey_returnsCachedResponseWithoutCallingAccountService() {
        // Arrange: mock successful account-service response
        when(accountServiceClient.executeTransfer(any(), any(), any(), any()))
                .thenReturn(new AccountServiceClient.AccountTransferResponse(
                        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                        new BigDecimal("100.00"), "COMPLETED", Instant.now()));

        String idempotencyKey = "dup-key-" + UUID.randomUUID();
        InitiateTransferRequest request = buildRequest(idempotencyKey);

        // Act: first call
        InitiateTransferResponse firstResponse = paymentService.initiateTransfer(request);
        // Act: second call with same idempotency key
        InitiateTransferResponse secondResponse = paymentService.initiateTransfer(request);

        // Assert both responses return the same transfer ID
        assertThat(secondResponse.transferId()).isEqualTo(firstResponse.transferId());
        assertThat(secondResponse.state()).isEqualTo(firstResponse.state());

        // Verify account-service was called exactly ONCE (not twice — idempotency works)
        verify(accountServiceClient, times(1)).executeTransfer(any(), any(), any(), any());
    }

    @Test
    void initiateTransfer_accountServiceFails_finalStateIsCancelled() {
        // Arrange: mock account-service to throw exception
        when(accountServiceClient.executeTransfer(any(), any(), any(), any()))
                .thenThrow(new RuntimeException("Insufficient funds"));

        InitiateTransferRequest request = buildRequest("fail-key-" + UUID.randomUUID());

        // Act
        InitiateTransferResponse response = paymentService.initiateTransfer(request);

        // Assert response state
        assertThat(response.state()).isEqualTo(CANCELLED.name());

        // Assert DB state and error message
        Optional<Transfer> dbTransfer = transferRepository.findById(response.transferId());
        assertThat(dbTransfer).isPresent();
        assertThat(dbTransfer.get().getState()).isEqualTo(CANCELLED);
        assertThat(dbTransfer.get().getErrorMessage()).isNotBlank();
    }

    @Test
    void getTransferStatus_returnsCurrentState() {
        // Arrange: mock successful response
        when(accountServiceClient.executeTransfer(any(), any(), any(), any()))
                .thenReturn(new AccountServiceClient.AccountTransferResponse(
                        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                        new BigDecimal("50.00"), "COMPLETED", Instant.now()));

        InitiateTransferRequest request = buildRequest("status-key-" + UUID.randomUUID());
        InitiateTransferResponse initiateResponse = paymentService.initiateTransfer(request);

        // Act
        var statusResponse = paymentService.getTransferStatus(initiateResponse.transferId());

        // Assert
        assertThat(statusResponse.transferId()).isEqualTo(initiateResponse.transferId());
        assertThat(statusResponse.state()).isEqualTo(POSTING.name());
        assertThat(statusResponse.amount()).isEqualByComparingTo(new BigDecimal("100.00"));
    }
}
