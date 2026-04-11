package au.com.bankforge.account.service;

import au.com.bankforge.account.dto.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for TransferService via REST endpoints.
 * Uses Testcontainers (jdbc:tc:postgresql:17) for a real PostgreSQL 17 database.
 * Uses RestClient (Spring 6.1+, standard in Spring Boot 4) — TestRestTemplate was removed.
 *
 * Tests:
 *   - Successful transfer: debits source, credits target, inserts exactly 1 outbox_event row
 *   - Insufficient funds: returns 400, no balance changes, no outbox_event row created
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class TransferServiceIT {

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private RestClient restClient;

    @BeforeEach
    void setUp() {
        restClient = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .build();
    }

    private CreateAccountResponse createAccount(String bsb, String accountNumber, BigDecimal balance) {
        var request = new CreateAccountRequest(bsb, accountNumber, "Test Account " + accountNumber, balance);
        CreateAccountResponse response = restClient.post()
                .uri("/api/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(CreateAccountResponse.class);
        assertThat(response).isNotNull();
        return response;
    }

    @Test
    void executeTransfer_success_debitsSourceCreditTargetWritesOutbox() {
        // Setup: Account A has 1000.00, Account B has 500.00
        CreateAccountResponse accountA = createAccount("082-001", "1000001", BigDecimal.valueOf(1000));
        CreateAccountResponse accountB = createAccount("082-002", "1000002", BigDecimal.valueOf(500));

        // Count outbox rows before transfer
        int outboxBefore = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE aggregatetype = 'transfer'", Integer.class);

        // Execute transfer: A -> B, $250
        var transferReq = new TransferRequest(
                accountA.id(), accountB.id(), BigDecimal.valueOf(250), "test transfer");
        TransferResponse resp = restClient.post()
                .uri("/api/internal/transfers")
                .contentType(MediaType.APPLICATION_JSON)
                .body(transferReq)
                .retrieve()
                .body(TransferResponse.class);

        assertThat(resp).isNotNull();
        assertThat(resp.status()).isEqualTo("COMPLETED");

        // Verify A balance is now 750.0000
        AccountDto aUpdated = restClient.get()
                .uri("/api/accounts/" + accountA.id())
                .retrieve()
                .body(AccountDto.class);
        assertThat(aUpdated.balance()).isEqualByComparingTo(BigDecimal.valueOf(750));

        // Verify B balance is now 750.0000
        AccountDto bUpdated = restClient.get()
                .uri("/api/accounts/" + accountB.id())
                .retrieve()
                .body(AccountDto.class);
        assertThat(bUpdated.balance()).isEqualByComparingTo(BigDecimal.valueOf(750));

        // Verify exactly 1 new outbox_event row was created
        int outboxAfter = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE aggregatetype = 'transfer'", Integer.class);
        assertThat(outboxAfter).isEqualTo(outboxBefore + 1);
    }

    @Test
    void executeTransfer_insufficientFunds_returns400_noBalanceChange() {
        // Setup: Account C has 100.00, Account D has 50.00
        CreateAccountResponse accountC = createAccount("083-001", "2000001", BigDecimal.valueOf(100));
        CreateAccountResponse accountD = createAccount("083-002", "2000002", BigDecimal.valueOf(50));

        // Count outbox rows before (to verify no new row created on failure)
        int outboxBefore = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE aggregatetype = 'transfer'", Integer.class);

        // Attempt transfer: C -> D, $500 (more than C's balance)
        var transferReq = new TransferRequest(
                accountC.id(), accountD.id(), BigDecimal.valueOf(500), "overspend attempt");

        assertThatThrownBy(() ->
                restClient.post()
                        .uri("/api/internal/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(transferReq)
                        .retrieve()
                        .toBodilessEntity()
        ).isInstanceOfSatisfying(RestClientResponseException.class, ex ->
                assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST)
        );

        // Verify C balance unchanged at 100.0000 (ACID rollback)
        AccountDto cResult = restClient.get()
                .uri("/api/accounts/" + accountC.id())
                .retrieve()
                .body(AccountDto.class);
        assertThat(cResult.balance()).isEqualByComparingTo(BigDecimal.valueOf(100));

        // Verify D balance unchanged at 50.0000
        AccountDto dResult = restClient.get()
                .uri("/api/accounts/" + accountD.id())
                .retrieve()
                .body(AccountDto.class);
        assertThat(dResult.balance()).isEqualByComparingTo(BigDecimal.valueOf(50));

        // Verify no new outbox_event row was created (transaction rolled back)
        int outboxAfter = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE aggregatetype = 'transfer'", Integer.class);
        assertThat(outboxAfter).isEqualTo(outboxBefore);
    }
}
