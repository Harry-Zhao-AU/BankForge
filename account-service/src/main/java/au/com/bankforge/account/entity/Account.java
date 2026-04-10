package au.com.bankforge.account.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for a bank account.
 *
 * balance uses DECIMAL(15,4) — never double/float (D-09: IEEE 754 rounding
 * produces cent errors; banking correctness requires exact arithmetic).
 *
 * Timestamps managed via @PrePersist / @PreUpdate lifecycle callbacks.
 */
@Entity
@Table(name = "accounts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 7)
    private String bsb;

    @Column(name = "account_number", nullable = false, length = 10)
    private String accountNumber;

    @Column(name = "account_name", nullable = false, length = 100)
    private String accountName;

    @Column(nullable = false, precision = 15, scale = 4)
    private BigDecimal balance;

    @Column(nullable = false, length = 3)
    @Builder.Default
    private String currency = "AUD";

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.balance == null) {
            this.balance = BigDecimal.ZERO;
        }
        if (this.currency == null) {
            this.currency = "AUD";
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
