package au.com.bankforge.ledger.kafka;

import au.com.bankforge.ledger.entity.LedgerEntry;
import au.com.bankforge.ledger.entity.LedgerOutboxEvent;
import au.com.bankforge.ledger.repository.LedgerEntryRepository;
import au.com.bankforge.ledger.repository.LedgerOutboxEventRepository;
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.context.Scope;
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
            @Header(KafkaHeaders.OFFSET) long offset) {

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

        // Idempotency guard (D-05) — true no-op if entries already exist for this transferId.
        // Debezium already published the original outbox row; no need to write again.
        if (ledgerEntryRepository.existsByTransferId(transferId)) {
            log.info("Ledger entries already exist for transferId={}, skipping (idempotent replay)", transferId);
            return;
        }

        // D-15, D-16: Set OTel Baggage so all downstream spans carry the transaction ID
        Baggage baggage = Baggage.current().toBuilder()
                .put("banking.transaction.id", transferId.toString())
                .build();
        try (Scope scope = baggage.makeCurrent()) {

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

            // D-04: Write outbox row in same transaction — Debezium CDC will publish the confirmation
            LedgerOutboxEvent outboxRow = LedgerOutboxEvent.builder()
                    .aggregatetype("transfer-confirmation")
                    .aggregateid(transferId.toString())
                    .type("TransferConfirmed")
                    .payload("{\"transferId\":\"" + transferId + "\",\"status\":\"CONFIRMED\"}")
                    .build();
            ledgerOutboxEventRepository.save(outboxRow);

            log.info("Outbox row written for transferId={} — Debezium will publish confirmation", transferId);
        }
    }

    private JsonNode parsePayload(String payload) {
        try {
            return objectMapper.readTree(payload);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse transfer event payload", e);
        }
    }
}
