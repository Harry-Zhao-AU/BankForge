package au.com.bankforge.account.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO for a single entry in transfer history.
 * Represents one outbox_event row — returned by GET /api/accounts/{id}/transfers.
 *
 * payload is the raw JSONB string from the outbox_event table.
 * Clients parse the JSON to extract transfer details (amount, counterparty, etc.).
 */
public record TransferHistoryResponse(
        UUID eventId,
        String type,
        String payload,
        Instant createdAt
) {}
