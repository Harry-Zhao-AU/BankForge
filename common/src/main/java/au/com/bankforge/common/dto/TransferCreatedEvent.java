package au.com.bankforge.common.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Event published when a transfer is created.
 *
 * Used as the outbox event payload in Phase 1.1 Kafka integration.
 * Defined here in common for type safety across services.
 *
 * Note: amount uses BigDecimal — never double/float (D-09: IEEE 754 rounding
 * produces cent errors; banking correctness requires exact arithmetic).
 */
public record TransferCreatedEvent(
        UUID transferId,
        UUID fromAccountId,
        UUID toAccountId,
        BigDecimal amount,
        String state,
        Instant createdAt
) {}
