package au.com.bankforge.account.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO returned after successful account creation (201 Created).
 * All monetary fields use BigDecimal (D-09).
 */
public record CreateAccountResponse(
        UUID id,
        String bsb,
        String accountNumber,
        String accountName,
        BigDecimal balance,
        String currency,
        Instant createdAt
) {}
