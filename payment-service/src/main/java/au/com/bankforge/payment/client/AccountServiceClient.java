package au.com.bankforge.payment.client;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * REST client wrapper for the account-service internal transfer API.
 *
 * Uses Spring Framework 7 RestClient (NOT deprecated RestTemplate).
 * Target endpoint: POST /api/internal/transfers
 *
 * The RestClient bean "accountRestClient" is injected via @Qualifier to avoid
 * clashing with the AccountServiceClient @Component bean name (BeanDefinitionOverrideException).
 * Base URL is read from services.account.base-url in application.yml.
 */
@Component
public class AccountServiceClient {

    private final RestClient accountRestClient;

    public AccountServiceClient(@Qualifier("accountRestClient") RestClient accountRestClient) {
        this.accountRestClient = accountRestClient;
    }

    /**
     * Executes an ACID fund transfer in the account-service.
     *
     * @param fromAccountId source account UUID
     * @param toAccountId   destination account UUID
     * @param amount        transfer amount (BigDecimal, no float/double per D-09)
     * @param description   optional transfer description
     * @return AccountTransferResponse from account-service
     * @throws org.springframework.web.client.RestClientResponseException on 4xx/5xx response
     */
    public AccountTransferResponse executeTransfer(
            UUID fromAccountId, UUID toAccountId, BigDecimal amount, String description) {
        return accountRestClient.post()
                .uri("/api/internal/transfers")
                .body(Map.of(
                        "fromAccountId", fromAccountId,
                        "toAccountId", toAccountId,
                        "amount", amount,
                        "description", description != null ? description : ""
                ))
                .retrieve()
                .body(AccountTransferResponse.class);
    }

    /**
     * Local record matching the account-service TransferResponse JSON.
     * amount is BigDecimal per D-09.
     */
    public record AccountTransferResponse(
            UUID transferId,
            UUID fromAccountId,
            UUID toAccountId,
            BigDecimal amount,
            String status,
            Instant timestamp
    ) {
    }
}
