package au.com.bankforge.ledger.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for the Debezium-compatible ledger outbox table.
 *
 * Column naming convention follows Debezium EventRouter SMT expectations:
 *   aggregatetype — the domain aggregate type (fixed: "transfer-confirmation")
 *   aggregateid   — the aggregate's unique identifier (transferId)
 *   type          — the event type (e.g., "TransferConfirmed")
 *   payload       — JSONB serialised event payload
 *
 * The Debezium EventRouter SMT reads these column names verbatim.
 * Do NOT rename them without updating the Debezium connector config.
 *
 * The aggregateid column is propagated as Kafka header "transaction-id" by
 * the EventRouter additional.placement configuration (D-15 Layer 1).
 */
@Entity
@Table(name = "ledger_outbox_event")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LedgerOutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 255)
    private String aggregatetype;

    @Column(nullable = false, length = 255)
    private String aggregateid;

    @Column(nullable = false, length = 255)
    private String type;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String payload;

    /**
     * W3C traceparent string (format: 00-<32-hex-traceId>-<16-hex-spanId>-<2-hex-flags>).
     * Nullable — existing rows and pre-migration events degrade gracefully.
     * Lifted to Kafka header "traceparent" by Debezium EventRouter additional.placement.
     */
    @Column(length = 55)
    private String traceparent;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
    }
}
