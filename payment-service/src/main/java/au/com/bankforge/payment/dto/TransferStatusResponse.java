package au.com.bankforge.payment.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Response for GET /api/payments/transfers/{id}.
 * Includes errorMessage field populated on compensation path.
 * Amount is BigDecimal per D-09.
 */
public record TransferStatusResponse(
        UUID transferId,
        String state,
        BigDecimal amount,
        UUID fromAccountId,
        UUID toAccountId,
        String errorMessage,
        Instant createdAt,
        Instant updatedAt
) {
}
