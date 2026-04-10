package au.com.bankforge.account.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Request DTO for executing an ACID fund transfer.
 *
 * amount — minimum 0.01 AUD; uses BigDecimal (D-09 — never double/float).
 * fromAccountId / toAccountId — both required UUIDs.
 */
public record TransferRequest(
        @NotNull UUID fromAccountId,
        @NotNull UUID toAccountId,
        @NotNull @DecimalMin(value = "0.01", message = "Transfer amount must be at least 0.01") BigDecimal amount,
        String description
) {}
