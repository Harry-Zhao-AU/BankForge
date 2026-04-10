package au.com.bankforge.account.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for the Debezium-compatible outbox table.
 *
 * Column naming convention follows Debezium EventRouter SMT expectations:
 *   aggregatetype — the domain aggregate type (e.g., "Transfer")
 *   aggregateid   — the aggregate's unique identifier (e.g., transferId)
 *   type          — the event type (e.g., "TransferInitiated")
 *   payload       — JSONB serialised event payload
 *
 * The Debezium EventRouter SMT reads these column names verbatim.
 * Do NOT rename them without updating the Debezium connector config.
 */
@Entity
@Table(name = "outbox_event")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 255)
    private String aggregatetype;

    @Column(nullable = false, length = 255)
    private String aggregateid;

    @Column(nullable = false, length = 255)
    private String type;

    @Column(nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
    }
}
