package au.com.bankforge.payment.entity;

import au.com.bankforge.common.enums.TransferState;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity representing a fund transfer in the payment-service.
 *
 * State is stored as VARCHAR (enum name) via @Enumerated(EnumType.STRING).
 * Amount is DECIMAL(15,4) — no float/double per D-09.
 * idempotency_key has a unique constraint (DB-level safety net for T-1-06).
 */
@Entity
@Table(name = "transfers")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Transfer {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "from_account_id", nullable = false)
    private UUID fromAccountId;

    @Column(name = "to_account_id", nullable = false)
    private UUID toAccountId;

    @Column(precision = 15, scale = 4, nullable = false)
    private BigDecimal amount;

    /**
     * Transfer lifecycle state — stored as enum name (VARCHAR 32).
     * Only valid transitions allowed by TransferStateMachine.transition() (T-1-07).
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 32, nullable = false)
    @Builder.Default
    private TransferState state = TransferState.PENDING;

    @Column(name = "idempotency_key", unique = true, nullable = false)
    private String idempotencyKey;

    @Column(length = 500)
    private String description;

    @Column(name = "error_message", length = 500)
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
