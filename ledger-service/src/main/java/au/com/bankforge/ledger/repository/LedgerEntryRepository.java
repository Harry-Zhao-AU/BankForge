package au.com.bankforge.ledger.repository;

import au.com.bankforge.ledger.entity.LedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Repository for ledger entries. Used in Phase 1.1+ when Kafka events trigger double-entry writes.
 */
@Repository
public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID> {

    List<LedgerEntry> findByTransferId(UUID transferId);

    List<LedgerEntry> findByAccountId(UUID accountId);

    boolean existsByTransferId(UUID transferId);
}
