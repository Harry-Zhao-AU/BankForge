package au.com.bankforge.payment.kafka;

import au.com.bankforge.payment.service.TransferStateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.BackOff;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

/**
 * Consumes banking.transfer.confirmed events published by ledger-service after writing
 * the double-entry ledger pair. Transitions the transfer from POSTING → CONFIRMED.
 *
 * Closes the Saga loop: ledger-service has persisted the double-entry pair and
 * publishes confirmation → payment-service transitions POSTING → CONFIRMED.
 *
 * Resilience: @RetryableTopic provides exponential backoff + DLT. If a confirmation
 * cannot be applied (e.g., transfer not found), the event is routed to the DLT after
 * 4 attempts. POSTING state transfers that don't receive confirmation within the retry
 * window will require manual investigation.
 *
 * JACKSON 3: Uses tools.jackson.databind.ObjectMapper (Spring Boot 4 / Jackson 3).
 * Do NOT use com.fasterxml.jackson.databind.ObjectMapper (Jackson 2, will not compile).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TransferConfirmationListener {

    private final TransferStateService transferStateService;
    private final ObjectMapper objectMapper; // tools.jackson.databind.ObjectMapper (Jackson 3)

    @RetryableTopic(
        attempts = "4",
        backOff = @BackOff(delay = 1000, multiplier = 2.0, maxDelay = 10000),
        dltTopicSuffix = "-dlt",
        autoCreateTopics = "true",
        numPartitions = "1",
        replicationFactor = "1"
    )
    @KafkaListener(topics = "banking.transfer.confirmed", groupId = "payment-service")
    public void onTransferConfirmed(
            @Payload String payload,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
            @Header(KafkaHeaders.OFFSET) long offset) {

        log.info("Payment-service received transfer confirmation: topic={} offset={}", topic, offset);

        UUID transferId = parseTransferId(payload);
        transferStateService.confirm(transferId);
        log.info("Transfer confirmed: transferId={}", transferId);
    }

    @DltHandler
    public void handleDlt(
            @Payload String payload,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic) {
        log.error("CONFIRMATION DLT: topic={} payload={}", topic, payload);
    }

    private UUID parseTransferId(String payload) {
        try {
            JsonNode root = objectMapper.readTree(payload);
            JsonNode node = root.get("transferId");
            if (node == null) {
                throw new IllegalArgumentException("Missing transferId field in payload: " + payload);
            }
            return UUID.fromString(node.asText());
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse confirmation payload: " + payload, e);
        }
    }
}
