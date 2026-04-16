package au.com.bankforge.ledger.repository;

import au.com.bankforge.ledger.entity.LedgerOutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface LedgerOutboxEventRepository extends JpaRepository<LedgerOutboxEvent, UUID> {
}
