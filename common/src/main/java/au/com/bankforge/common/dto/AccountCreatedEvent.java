package au.com.bankforge.common.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Event published when a new account is created.
 *
 * Used as an outbox event payload in Phase 1.1 Kafka integration.
 * Defined in common for type safety across services.
 */
public record AccountCreatedEvent(
        UUID accountId,
        String bsb,
        String accountNumber,
        Instant createdAt
) {}
