package au.com.bankforge.account.service;

import au.com.bankforge.account.dto.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concurrent transfer test proving PESSIMISTIC_WRITE prevents overdraft.
 *
 * 10 threads each attempt to transfer $100 from an account with $500 balance.
 * With SELECT FOR UPDATE, exactly 5 should succeed and 5 should fail.
 * Final source balance must be $0, final target balance must be $500.
 *
 * Uses RestClient (Spring 6.1+, standard in Spring Boot 4) — TestRestTemplate was removed.
 * Each thread creates its own RestClient instance (thread-safe by design).
 *
 * This validates D-10: PESSIMISTIC_WRITE + lock ordering prevents concurrent overdraft.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ConcurrentTransferIT {

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
    void tenConcurrentTransfers_from500Balance_exactly5Succeed() throws Exception {
        // Setup: X has $500, Y has $0
        CreateAccountResponse accountX = createAccount("099-100", "9000001", BigDecimal.valueOf(500));
        CreateAccountResponse accountY = createAccount("099-200", "9000002", BigDecimal.valueOf(0));

        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        List<Future<Integer>> futures = new ArrayList<>();

        // Each thread gets its own RestClient — RestClient is stateless and thread-safe
        String baseUrl = "http://localhost:" + port;

        // Launch 10 concurrent $100 transfers from X to Y
        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                RestClient threadClient = RestClient.builder().baseUrl(baseUrl).build();
                var req = new TransferRequest(
                        accountX.id(), accountY.id(), BigDecimal.valueOf(100), "concurrent test");
                try {
                    threadClient.post()
                            .uri("/api/internal/transfers")
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(req)
                            .retrieve()
                            .toBodilessEntity();
                    successCount.incrementAndGet();
                    return 200;
                } catch (RestClientResponseException ex) {
                    failureCount.incrementAndGet();
                    return ex.getStatusCode().value();
                }
            }));
        }

        // Wait for all threads to complete
        executor.shutdown();
        boolean allDone = executor.awaitTermination(30, TimeUnit.SECONDS);
        assertThat(allDone).as("All threads should complete within 30 seconds").isTrue();

        // All futures should complete without exception
        for (Future<Integer> f : futures) {
            f.get(); // throws if thread threw an unexpected exception
        }

        // CRITICAL: Exactly 5 succeed, 5 fail — no overdraft
        assertThat(successCount.get())
                .as("Exactly 5 of 10 transfers should succeed ($500 / $100 = 5)")
                .isEqualTo(5);
        assertThat(failureCount.get())
                .as("Exactly 5 of 10 transfers should fail (insufficient funds)")
                .isEqualTo(5);

        // X balance should be exactly $0
        AccountDto xFinal = restClient.get()
                .uri("/api/accounts/" + accountX.id())
                .retrieve()
                .body(AccountDto.class);
        assertThat(xFinal.balance())
                .as("Account X final balance must be exactly 0 (no overdraft)")
                .isEqualByComparingTo(BigDecimal.ZERO);

        // Y balance should be exactly $500
        AccountDto yFinal = restClient.get()
                .uri("/api/accounts/" + accountY.id())
                .retrieve()
                .body(AccountDto.class);
        assertThat(yFinal.balance())
                .as("Account Y final balance must be exactly 500")
                .isEqualByComparingTo(BigDecimal.valueOf(500));
    }

    private CreateAccountResponse createAccount(String bsb, String accountNumber, BigDecimal balance) {
        var request = new CreateAccountRequest(bsb, accountNumber, "Concurrent Test " + accountNumber, balance);
        CreateAccountResponse response = restClient.post()
                .uri("/api/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(CreateAccountResponse.class);
        assertThat(response).isNotNull();
        return response;
    }
}
