package au.com.bankforge.account.repository;

import au.com.bankforge.account.entity.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * JPA repository for OutboxEvent entities.
 *
 * Supports two query patterns:
 * 1. findByAggregateidOrderByCreatedAtDesc — find all events for a given aggregateid (transfer ID)
 * 2. findTransfersByAccountId — native JSONB query to find Transfer events involving a given account
 *    (used by AccountService.getTransferHistory to support GET /api/accounts/{id}/transfers)
 */
@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * Returns all outbox events for a given aggregateid, ordered by creation time descending.
     * Primarily used for event replay and debugging.
     */
    List<OutboxEvent> findByAggregateidOrderByCreatedAtDesc(String aggregateid);

    /**
     * Native PostgreSQL JSONB query to find all Transfer outbox events involving a specific account.
     * Matches on payload->>'fromAccountId' OR payload->>'toAccountId'.
     * Used by GET /api/accounts/{id}/transfers to return transfer history for an account.
     */
    @Query(value = "SELECT * FROM outbox_event WHERE aggregatetype = 'transfer' " +
            "AND (payload->>'fromAccountId' = :accountId OR payload->>'toAccountId' = :accountId) " +
            "ORDER BY created_at DESC",
            nativeQuery = true)
    List<OutboxEvent> findTransfersByAccountId(@Param("accountId") String accountId);
}
