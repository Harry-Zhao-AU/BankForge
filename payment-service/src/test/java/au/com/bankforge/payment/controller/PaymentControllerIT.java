package au.com.bankforge.payment.controller;

import au.com.bankforge.payment.client.AccountServiceClient;
import au.com.bankforge.payment.dto.InitiateTransferResponse;
import au.com.bankforge.payment.dto.TransferStatusResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Integration tests for PaymentController using a real embedded Spring Boot server.
 *
 * Uses RestClient (not TestRestTemplate — removed in Spring Boot 4) with @LocalServerPort.
 * AccountServiceClient is mocked — no real account-service required.
 * Testcontainers: PostgreSQL 17 for JPA/Flyway, Redis 7.2 for idempotency.
 *
 * Tests cover CORE-03 and TXNS-05:
 *   - POST returns 201 for new transfer
 *   - POST returns 200 for idempotent replay (same idempotency key)
 *   - GET returns 200 with current state
 *   - POST with missing idempotencyKey returns 400
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class PaymentControllerIT {

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
    }

    @LocalServerPort
    int port;

    @MockitoBean
    private AccountServiceClient accountServiceClient;

    private RestClient restClient;

    @BeforeEach
    void setUp() {
        restClient = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .build();

        // Default mock: account-service succeeds
        when(accountServiceClient.executeTransfer(any(), any(), any(), any()))
                .thenReturn(new AccountServiceClient.AccountTransferResponse(
                        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                        new BigDecimal("100.00"), "COMPLETED", Instant.now()));
    }

    private Map<String, Object> buildTransferBody(String idempotencyKey) {
        return Map.of(
                "fromAccountId", UUID.randomUUID().toString(),
                "toAccountId", UUID.randomUUID().toString(),
                "amount", "100.00",
                "idempotencyKey", idempotencyKey,
                "description", "Test transfer"
        );
    }

    @Test
    void postTransfer_newTransfer_returns201() {
        String idempotencyKey = "new-transfer-" + UUID.randomUUID();

        ResponseEntity<InitiateTransferResponse> response = restClient.post()
                .uri("/api/payments/transfers")
                .body(buildTransferBody(idempotencyKey))
                .retrieve()
                .toEntity(InitiateTransferResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().transferId()).isNotNull();
        assertThat(response.getBody().state()).isEqualTo("CONFIRMED");
    }

    @Test
    void postTransfer_duplicateIdempotencyKey_returns200() {
        String idempotencyKey = "dup-transfer-" + UUID.randomUUID();
        Map<String, Object> body = buildTransferBody(idempotencyKey);

        // First request — should be 201
        ResponseEntity<InitiateTransferResponse> first = restClient.post()
                .uri("/api/payments/transfers")
                .body(body)
                .retrieve()
                .toEntity(InitiateTransferResponse.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // Second request with same idempotency key — should be 200
        ResponseEntity<InitiateTransferResponse> second = restClient.post()
                .uri("/api/payments/transfers")
                .body(body)
                .retrieve()
                .toEntity(InitiateTransferResponse.class);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Both responses should have the same transfer ID
        assertThat(second.getBody()).isNotNull();
        assertThat(second.getBody().transferId()).isEqualTo(first.getBody().transferId());
    }

    @Test
    void getTransferStatus_existingTransfer_returns200WithState() {
        String idempotencyKey = "get-status-" + UUID.randomUUID();

        // Create a transfer first
        InitiateTransferResponse created = restClient.post()
                .uri("/api/payments/transfers")
                .body(buildTransferBody(idempotencyKey))
                .retrieve()
                .body(InitiateTransferResponse.class);

        assertThat(created).isNotNull();

        // Then get its status
        ResponseEntity<TransferStatusResponse> statusResponse = restClient.get()
                .uri("/api/payments/transfers/" + created.transferId())
                .retrieve()
                .toEntity(TransferStatusResponse.class);

        assertThat(statusResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(statusResponse.getBody()).isNotNull();
        assertThat(statusResponse.getBody().transferId()).isEqualTo(created.transferId());
        assertThat(statusResponse.getBody().state()).isEqualTo("CONFIRMED");
    }

    @Test
    void postTransfer_missingIdempotencyKey_returns400() {
        Map<String, Object> bodyWithoutKey = Map.of(
                "fromAccountId", UUID.randomUUID().toString(),
                "toAccountId", UUID.randomUUID().toString(),
                "amount", "100.00"
        );

        assertThatThrownBy(() -> restClient.post()
                .uri("/api/payments/transfers")
                .body(bodyWithoutKey)
                .retrieve()
                .body(String.class))
                .isInstanceOf(RestClientResponseException.class)
                .satisfies(ex -> assertThat(((RestClientResponseException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }
}
