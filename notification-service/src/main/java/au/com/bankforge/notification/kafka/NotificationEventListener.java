package au.com.bankforge.notification.kafka;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
import java.util.UUID;

@Component
@Slf4j
@RequiredArgsConstructor
public class NotificationEventListener {

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
        groupId = "notification-service"
    )
    public void onTransferEvent(
            @Payload String payload,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
            @Header(KafkaHeaders.OFFSET) long offset) {

        log.info("Notification processing transfer event: topic={} offset={}", topic, offset);

        JsonNode root = parsePayload(payload);
        UUID transferId = UUID.fromString(root.get("transferId").asText());
        UUID fromAccountId = UUID.fromString(root.get("fromAccountId").asText());
        UUID toAccountId = UUID.fromString(root.get("toAccountId").asText());
        BigDecimal amount = new BigDecimal(root.get("amount").asText());

        log.info("NOTIFICATION: Transfer {} completed. From={} To={} Amount={}",
                transferId, fromAccountId, toAccountId, amount);
    }

    @DltHandler
    public void handleDlt(
            @Payload String payload,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic) {
        log.error("NOTIFICATION DLT: topic={} payload={}", topic, payload);
    }

    private JsonNode parsePayload(String payload) {
        try {
            return objectMapper.readTree(payload);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse transfer event payload", e);
        }
    }
}
