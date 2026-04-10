package au.com.bankforge.payment.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Response for POST /api/payments/transfers.
 * Returned for both new (HTTP 201) and idempotent replay (HTTP 200) per TXNS-05.
 * Amount is BigDecimal per D-09.
 */
public record InitiateTransferResponse(
        UUID transferId,
        UUID fromAccountId,
        UUID toAccountId,
        BigDecimal amount,
        String state,
        Instant createdAt
) {
}
