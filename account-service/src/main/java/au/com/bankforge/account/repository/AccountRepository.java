package au.com.bankforge.account.repository;

import au.com.bankforge.account.entity.Account;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * JPA repository for Account entities.
 *
 * findByIdForUpdate uses PESSIMISTIC_WRITE (SELECT FOR UPDATE) for all balance-reading
 * queries in the transfer context. This prevents concurrent overdraft under Read Committed
 * isolation (D-10).
 *
 * Always use findByIdForUpdate — never findById — when balance will be modified.
 */
@Repository
public interface AccountRepository extends JpaRepository<Account, UUID> {

    /**
     * Acquires a PESSIMISTIC_WRITE (SELECT FOR UPDATE) lock on the row.
     * Used by TransferService to ensure exclusive access during debit/credit operations.
     * Lock ordering: always acquire lower UUID first to prevent deadlocks.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Account a WHERE a.id = :id")
    Optional<Account> findByIdForUpdate(@Param("id") UUID id);
}
