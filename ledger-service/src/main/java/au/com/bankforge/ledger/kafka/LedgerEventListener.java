package au.com.bankforge.ledger.kafka;

import au.com.bankforge.ledger.entity.LedgerEntry;
import au.com.bankforge.ledger.repository.LedgerEntryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.UUID;

@Component
@Slf4j
@RequiredArgsConstructor
public class LedgerEventListener {

    private final LedgerEntryRepository ledgerEntryRepository;
    private final ObjectMapper objectMapper;
    private final KafkaTemplate<String, String> kafkaTemplate;

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

        // Idempotency guard — skip DB writes if entries already exist for this transferId
        if (ledgerEntryRepository.existsByTransferId(transferId)) {
            log.info("Ledger entries already exist for transferId={}, skipping (idempotent replay)", transferId);
            // Still publish confirmation — the downstream consumer may not have received it
            String confirmPayload = "{\"transferId\":\"" + transferId + "\",\"status\":\"CONFIRMED\"}";
            kafkaTemplate.send("banking.transfer.confirmed", transferId.toString(), confirmPayload);
            return;
        }

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

        String confirmPayload = "{\"transferId\":\"" + transferId + "\",\"status\":\"CONFIRMED\"}";
        kafkaTemplate.send("banking.transfer.confirmed", transferId.toString(), confirmPayload);
        log.info("Published confirmation event: transferId={}", transferId);
    }

    private JsonNode parsePayload(String payload) {
        try {
            return objectMapper.readTree(payload);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse transfer event payload", e);
        }
    }
}
