package au.com.bankforge.payment.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Request body for POST /api/payments/transfers.
 *
 * All monetary fields are BigDecimal per D-09 (no float/double).
 * @NotNull on account IDs and @DecimalMin on amount for T-1-08 input validation.
 * @NotBlank on idempotencyKey ensures every transfer has a client-generated dedup key (TXNS-05).
 */
public record InitiateTransferRequest(
        @NotNull UUID fromAccountId,
        @NotNull UUID toAccountId,
        @NotNull @DecimalMin("0.01") BigDecimal amount,
        @NotBlank String idempotencyKey,
        String description
) {
}
