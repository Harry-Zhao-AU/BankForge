package au.com.bankforge.ledger.kafka;

import au.com.bankforge.ledger.entity.LedgerEntry;
import au.com.bankforge.ledger.entity.LedgerOutboxEvent;
import au.com.bankforge.ledger.repository.LedgerEntryRepository;
import au.com.bankforge.ledger.repository.LedgerOutboxEventRepository;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * Consumes banking.transfer.events published by Debezium CDC from account-service's outbox.
 * Writes a double-entry ledger pair and publishes a confirmation via the ledger outbox
 * (Debezium CDC path: LedgerOutboxEvent -> banking.transfer.confirmed).
 *
 * OTel trace continuity: reads the "traceparent" Kafka header (set by Debezium from the
 * outbox column), reconstructs the remote SpanContext via W3CTraceContextPropagator, and
 * creates a child span under the originating payment-service trace. The ledger outbox row
 * also captures traceparent so the chain continues to payment-service's confirmation consumer.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class LedgerEventListener {

    private static final Tracer TRACER = GlobalOpenTelemetry.getTracer("ledger-service");

    private final LedgerEntryRepository ledgerEntryRepository;
    private final LedgerOutboxEventRepository ledgerOutboxEventRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    @KafkaListener(
        topics = "banking.transfer.events",
        groupId = "ledger-service"
    )
    public void onTransferEvent(
            @Payload String payload,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
            @Header(KafkaHeaders.OFFSET) long offset,
            @Header(value = "traceparent", required = false) String traceparent) {

        log.info("Ledger processing transfer event: topic={} offset={}", topic, offset);

        // Reconstruct remote parent context from traceparent header and wrap business
        // logic in a child span so this consumer appears in the originating trace.
        Span processingSpan = buildChildSpan(TRACER, "banking.transfer.events process", traceparent);
        try (Scope scope = processingSpan.makeCurrent()) {

            JsonNode root = parsePayload(payload);
            JsonNode transferIdNode = root.get("transferId");
            JsonNode fromAccountIdNode = root.get("fromAccountId");
            JsonNode toAccountIdNode = root.get("toAccountId");
            JsonNode amountNode = root.get("amount");
            if (transferIdNode == null || fromAccountIdNode == null || toAccountIdNode == null || amountNode == null) {
                throw new IllegalArgumentException("Transfer event missing required field(s). payload=" + payload);
            }
            UUID transferId = UUID.fromString(transferIdNode.asText());
            UUID fromAccountId = UUID.fromString(fromAccountIdNode.asText());
            UUID toAccountId = UUID.fromString(toAccountIdNode.asText());
            BigDecimal amount = new BigDecimal(amountNode.asText());

            // Idempotency guard (D-05) — true no-op if entries already exist for this transferId.
            if (ledgerEntryRepository.existsByTransferId(transferId)) {
                log.info("Ledger entries already exist for transferId={}, skipping (idempotent replay)", transferId);
                return;
            }

            Span.current().setAttribute("banking.transaction.id", transferId.toString());

            LedgerEntry debit = LedgerEntry.builder()
                    .transferId(transferId)
                    .accountId(fromAccountId)
                    .entryType("DEBIT")
                    .amount(amount)
                    .currency("AUD")
                    .description("Transfer " + transferId + " debit")
                    .build();

            LedgerEntry credit = LedgerEntry.builder()
                    .transferId(transferId)
                    .accountId(toAccountId)
                    .entryType("CREDIT")
                    .amount(amount)
                    .currency("AUD")
                    .description("Transfer " + transferId + " credit")
                    .build();

            ledgerEntryRepository.save(debit);
            ledgerEntryRepository.save(credit);

            log.info("Ledger entries written: transferId={} debit={} credit={}", transferId, debit.getId(), credit.getId());

            // Capture current span's traceparent so the confirmation outbox row carries it.
            // Debezium lifts this column to a Kafka header, continuing the trace chain to
            // payment-service's TransferConfirmationListener.
            SpanContext outCtx = Span.current().getSpanContext();
            String outTraceparent = outCtx.isValid()
                    ? "00-" + outCtx.getTraceId() + "-" + outCtx.getSpanId() + "-01"
                    : null;

            // D-04: Write outbox row in same transaction — Debezium CDC publishes confirmation.
            LedgerOutboxEvent outboxRow = LedgerOutboxEvent.builder()
                    .aggregatetype("transfer-confirmation")
                    .aggregateid(transferId.toString())
                    .type("TransferConfirmed")
                    .payload("{\"transferId\":\"" + transferId + "\",\"status\":\"CONFIRMED\"}")
                    .traceparent(outTraceparent)
                    .build();
            ledgerOutboxEventRepository.save(outboxRow);

            log.info("Outbox row written for transferId={} — Debezium will publish confirmation", transferId);

        } finally {
            processingSpan.end();
        }
    }

    private JsonNode parsePayload(String payload) {
        try {
            return objectMapper.readTree(payload);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse transfer event payload", e);
        }
    }

    /**
     * Build a child span under the remote trace identified by the W3C traceparent header value.
     * If traceparent is null or malformed, falls back to a new root span (graceful degradation).
     */
    private Span buildChildSpan(Tracer tracer, String name, String traceparent) {
        io.opentelemetry.api.trace.SpanBuilder builder = tracer.spanBuilder(name);
        if (traceparent != null) {
            io.opentelemetry.context.Context remoteCtx =
                    W3CTraceContextPropagator.getInstance()
                            .extract(
                                    io.opentelemetry.context.Context.root(),
                                    Map.of("traceparent", traceparent),
                                    MapTextMapGetter.INSTANCE);
            builder.setParent(remoteCtx);
        }
        return builder.startSpan();
    }

    private static final class MapTextMapGetter
            implements TextMapGetter<Map<String, String>> {
        static final MapTextMapGetter INSTANCE = new MapTextMapGetter();

        @Override
        public Iterable<String> keys(Map<String, String> carrier) {
            return carrier.keySet();
        }

        @Override
        public String get(Map<String, String> carrier, String key) {
            return carrier == null ? null : carrier.get(key);
        }
    }
}
