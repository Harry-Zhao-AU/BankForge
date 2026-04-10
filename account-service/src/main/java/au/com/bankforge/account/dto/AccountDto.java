package au.com.bankforge.account.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * DTO returned for GET /api/accounts/{id}.
 * All monetary fields use BigDecimal (D-09).
 */
public record AccountDto(
        UUID id,
        String bsb,
        String accountNumber,
        String accountName,
        BigDecimal balance,
        String currency,
        Instant createdAt
) {}
