package au.com.bankforge.ledger.kafka;

import au.com.bankforge.ledger.entity.LedgerEntry;
import au.com.bankforge.ledger.entity.LedgerOutboxEvent;
import au.com.bankforge.ledger.repository.LedgerEntryRepository;
import au.com.bankforge.ledger.repository.LedgerOutboxEventRepository;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
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
import java.util.UUID;

/**
 * Consumes banking.transfer.events published by Debezium CDC from account-service's outbox.
 * Writes a double-entry ledger pair and publishes a confirmation via the ledger outbox
 * (Debezium CDC path: LedgerOutboxEvent -> banking.transfer.confirmed).
 *
 * OTel trace continuity: observationEnabled=true (set in KafkaConfig) automatically reads the
 * "traceparent" Kafka header and places the auto-observation span inside the originating trace.
 * We call Span.current() directly — no manual child span or makeCurrent() needed.
 * The outbox row captures the current span's traceparent so the chain continues to
 * payment-service's confirmation consumer.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class LedgerEventListener {

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

        // Idempotency guard — true no-op if entries already exist for this transferId.
        if (ledgerEntryRepository.existsByTransferId(transferId)) {
            log.info("Ledger entries already exist for transferId={}, skipping (idempotent replay)", transferId);
            return;
        }

        // observationEnabled=true already placed this listener's span inside the originating trace.
        // Span.current() IS the auto-obs span — setAttribute works correctly here.
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

        // Capture current auto-obs span context for the outbox row traceparent.
        // Debezium lifts this column to a Kafka header via additional.placement,
        // allowing payment-service's observationEnabled listener to join the same trace.
        SpanContext outCtx = Span.current().getSpanContext();
        String outTraceparent = outCtx.isValid()
                ? "00-" + outCtx.getTraceId() + "-" + outCtx.getSpanId() + "-01"
                : null;

        LedgerOutboxEvent outboxRow = LedgerOutboxEvent.builder()
                .aggregatetype("transfer-confirmation")
                .aggregateid(transferId.toString())
                .type("TransferConfirmed")
                .payload("{\"transferId\":\"" + transferId + "\",\"status\":\"CONFIRMED\"}")
                .traceparent(outTraceparent)
                .build();
        ledgerOutboxEventRepository.save(outboxRow);

        log.info("Outbox row written for transferId={} — Debezium will publish confirmation", transferId);
    }

    private JsonNode parsePayload(String payload) {
        try {
            return objectMapper.readTree(payload);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse transfer event payload", e);
        }
    }
}
