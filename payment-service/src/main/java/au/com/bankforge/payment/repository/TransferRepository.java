package au.com.bankforge.payment.repository;

import au.com.bankforge.payment.entity.Transfer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for Transfer entities.
 * findByIdempotencyKey is used to detect duplicate submissions before Redis (DB safety net).
 */
@Repository
public interface TransferRepository extends JpaRepository<Transfer, UUID> {

    Optional<Transfer> findByIdempotencyKey(String idempotencyKey);
}
