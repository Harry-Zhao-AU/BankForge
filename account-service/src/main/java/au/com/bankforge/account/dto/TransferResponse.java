package au.com.bankforge.account.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO returned after a successful transfer execution.
 * All monetary fields use BigDecimal (D-09).
 */
public record TransferResponse(
        UUID transferId,
        UUID fromAccountId,
        UUID toAccountId,
        BigDecimal amount,
        String status,
        Instant timestamp
) {}
