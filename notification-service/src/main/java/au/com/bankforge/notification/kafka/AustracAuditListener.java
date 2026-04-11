package au.com.bankforge.notification.kafka;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.BackOff;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * AUSTRAC threshold detection listener.
 *
 * Monitors all transfer events. When transfer amount >= AUD $10,000,
 * logs a structured JSON audit event to the AUSTRAC_AUDIT logger.
 *
 * Uses a SEPARATE consumer group ("austrac-audit") from the notification
 * consumer ("notification-service") so each receives every message independently.
 *
 * NOTE: The DLT topic "banking.transfer.events-dlt" is shared with
 * NotificationEventListener and LedgerEventListener. Each consumer group
 * produces its own DLT entries independently — a message failing in
 * austrac-audit does NOT affect the notification-service or ledger-service
 * consumer groups. Spring Kafka @RetryableTopic creates per-group retry
 * topics but shares the base DLT topic name by convention.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class AustracAuditListener {

    private static final BigDecimal THRESHOLD = new BigDecimal("10000.00");
    private static final Logger AUSTRAC_LOG = LoggerFactory.getLogger("AUSTRAC_AUDIT");

    private final ObjectMapper objectMapper;

    @RetryableTopic(
        attempts = "4",
        backOff = @BackOff(delay = 1000, multiplier = 2.0, maxDelay = 10000),
        dltTopicSuffix = "-dlt",
        autoCreateTopics = "true",
        numPartitions = "1",
        replicationFactor = "1"
    )
    @KafkaListener(
        topics = "banking.transfer.events",
        groupId = "austrac-audit"
    )
    public void onTransferEvent(
            @Payload String payload,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
            @Header(KafkaHeaders.OFFSET) long offset) {

        JsonNode root = parsePayload(payload);

        JsonNode amountNode = root.get("amount");
        if (amountNode == null) {
            throw new IllegalArgumentException("Transfer event missing required field: amount. payload=" + payload);
        }
        BigDecimal amount = new BigDecimal(amountNode.asText());

        // Use compareTo, NOT equals — BigDecimal.equals considers scale:
        // new BigDecimal("10000.00").equals(new BigDecimal("10000.0000")) returns FALSE
        // new BigDecimal("10000.00").compareTo(new BigDecimal("10000.0000")) returns 0
        if (amount.compareTo(THRESHOLD) >= 0) {
            JsonNode transferIdNode = root.get("transferId");
            JsonNode fromAccountIdNode = root.get("fromAccountId");
            JsonNode toAccountIdNode = root.get("toAccountId");
            if (transferIdNode == null || fromAccountIdNode == null || toAccountIdNode == null) {
                throw new IllegalArgumentException("Transfer event missing required field(s) for AUSTRAC audit. payload=" + payload);
            }
            String transferId = transferIdNode.asText();
            String fromAccountId = fromAccountIdNode.asText();
            String toAccountId = toAccountIdNode.asText();

            AUSTRAC_LOG.info("AUSTRAC threshold transfer detected",
                    kv("austrac_event", true),
                    kv("threshold_aud", THRESHOLD),
                    kv("transfer_id", transferId),
                    kv("from_account_id", fromAccountId),
                    kv("to_account_id", toAccountId),
                    kv("amount", amount.toPlainString()),
                    kv("currency", "AUD"),
                    kv("event_timestamp", Instant.now().toString()));

            log.info("AUSTRAC threshold triggered: transferId={} amount={}", transferId, amount);
        }
    }

    @DltHandler
    public void handleDlt(
            @Payload String payload,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic) {
        log.error("AUSTRAC AUDIT DLT: topic={} payload={}", topic, payload);
    }

    private JsonNode parsePayload(String payload) {
        try {
            return objectMapper.readTree(payload);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse transfer event payload for AUSTRAC audit", e);
        }
    }
}
