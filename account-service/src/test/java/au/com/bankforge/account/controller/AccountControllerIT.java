package au.com.bankforge.account.controller;

import au.com.bankforge.account.dto.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for AccountController.
 * Uses Testcontainers via jdbc:tc:postgresql:17 URL (auto-starts PostgreSQL 17).
 * Flyway migrations run automatically on startup.
 *
 * Uses RestClient (Spring 6.1+, standard in Spring Boot 4) instead of
 * TestRestTemplate (removed in Spring Boot 4).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AccountControllerIT {

    @LocalServerPort
    private int port;

    private RestClient restClient;

    @BeforeEach
    void setUp() {
        restClient = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .build();
    }

    @Test
    void createAccount_validBsb_returns201() {
        var request = new CreateAccountRequest("012-345", "123456", "Test Account", null);

        ResponseEntity<CreateAccountResponse> response = restClient.post()
                .uri("/api/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .toEntity(CreateAccountResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().id()).isNotNull();
        assertThat(response.getBody().bsb()).isEqualTo("012-345");
        assertThat(response.getBody().balance()).isNotNull();
    }

    @Test
    void createAccount_invalidBsb_missingLeadingDigit_returns400() {
        var request = new CreateAccountRequest("12-345", "123456", "Test Account", null);

        assertThatThrownBy(() ->
                restClient.post()
                        .uri("/api/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(request)
                        .retrieve()
                        .toBodilessEntity()
        ).isInstanceOfSatisfying(RestClientResponseException.class, ex ->
                assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST)
        );
    }

    @Test
    void createAccount_invalidBsb_letters_returns400() {
        var request = new CreateAccountRequest("abc-def", "123456", "Test Account", null);

        assertThatThrownBy(() ->
                restClient.post()
                        .uri("/api/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(request)
                        .retrieve()
                        .toBodilessEntity()
        ).isInstanceOfSatisfying(RestClientResponseException.class, ex ->
                assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST)
        );
    }

    @Test
    void getAccount_existingId_returns200WithBalance() {
        // Create an account first
        var createRequest = new CreateAccountRequest("099-001", "111111", "Balance Test", null);
        CreateAccountResponse created = restClient.post()
                .uri("/api/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .body(createRequest)
                .retrieve()
                .body(CreateAccountResponse.class);

        assertThat(created).isNotNull();

        // Get it back
        AccountDto result = restClient.get()
                .uri("/api/accounts/" + created.id())
                .retrieve()
                .body(AccountDto.class);

        assertThat(result).isNotNull();
        assertThat(result.id()).isEqualTo(created.id());
        assertThat(result.balance()).isNotNull();
    }

    @Test
    void getTransferHistory_afterTransfer_returnsNonEmptyList() {
        // Create two accounts
        CreateAccountResponse accountA = restClient.post()
                .uri("/api/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new CreateAccountRequest("011-001", "200001", "Account A", BigDecimal.valueOf(1000)))
                .retrieve()
                .body(CreateAccountResponse.class);

        CreateAccountResponse accountB = restClient.post()
                .uri("/api/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new CreateAccountRequest("011-002", "200002", "Account B", BigDecimal.valueOf(0)))
                .retrieve()
                .body(CreateAccountResponse.class);

        assertThat(accountA).isNotNull();
        assertThat(accountB).isNotNull();

        // Execute a transfer
        TransferResponse transferResp = restClient.post()
                .uri("/api/internal/transfers")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new TransferRequest(accountA.id(), accountB.id(), BigDecimal.valueOf(100), "test transfer"))
                .retrieve()
                .body(TransferResponse.class);

        assertThat(transferResp).isNotNull();
        assertThat(transferResp.status()).isEqualTo("COMPLETED");

        // Check transfer history for account A
        TransferHistoryResponse[] history = restClient.get()
                .uri("/api/accounts/" + accountA.id() + "/transfers")
                .retrieve()
                .body(TransferHistoryResponse[].class);

        assertThat(history).isNotNull();
        assertThat(history.length).isGreaterThan(0);

        // Verify each entry has required fields
        TransferHistoryResponse entry = history[0];
        assertThat(entry.eventId()).isNotNull();
        assertThat(entry.type()).isEqualTo("TransferInitiated");
        assertThat(entry.payload()).isNotBlank();
        assertThat(entry.createdAt()).isNotNull();
    }
}
