package au.com.bankforge.payment.repository;

import au.com.bankforge.common.enums.TransferState;
import au.com.bankforge.payment.entity.Transfer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for Transfer entities.
 * findByIdempotencyKey is used to detect duplicate submissions before Redis (DB safety net).
 * findByStateAndCreatedAtBefore / findByStateAndUpdatedAtBefore are used by HungTransferDetector.
 */
@Repository
public interface TransferRepository extends JpaRepository<Transfer, UUID> {

    Optional<Transfer> findByIdempotencyKey(String idempotencyKey);

    /**
     * Finds transfers stuck in a given state whose createdAt is older than the threshold.
     * Used by HungTransferDetector to detect hung PAYMENT_PROCESSING transfers (5-min cutoff).
     */
    List<Transfer> findByStateAndCreatedAtBefore(TransferState state, Instant threshold);

    /**
     * Finds transfers stuck in a given state whose updatedAt is older than the threshold.
     * Used by HungTransferDetector to detect hung POSTING transfers (15-min cutoff).
     */
    List<Transfer> findByStateAndUpdatedAtBefore(TransferState state, Instant threshold);
}
