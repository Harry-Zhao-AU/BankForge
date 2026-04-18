package au.com.bankforge.ledger.kafka;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Dead Letter Topic (DLT) handler for exhausted-retry messages.
 *
 * Consumes from banking.transfer.events-dlt — messages routed here by
 * DeadLetterPublishingRecoverer after ExponentialBackOff is exhausted (D-12, D-13).
 *
 * SECURITY (T-01.2-04): This handler is READ-ONLY. It logs and discards.
 * It must NOT re-publish consumed messages to any topic — doing so would
 * create a DLT feedback loop where each consumed DLT message triggers a
 * new error that lands back in DLT.
 *
 * Spring Kafka DLT headers automatically added by DeadLetterPublishingRecoverer:
 *   kafka_original-topic        — original source topic
 *   kafka_original-partition    — original partition
 *   kafka_original-offset       — original offset
 *   kafka_exception-fqcn        — exception class that caused routing to DLT
 *   kafka_exception-message     — exception message
 */
@Component
@Slf4j
public class DltHandler {

    @KafkaListener(
        topics = "banking.transfer.events-dlt",
        groupId = "ledger-service-dlt"
    )
    public void onDltMessage(ConsumerRecord<String, String> record) {
        log.error("DLT message received: topic={} partition={} offset={} key={} value={}",
            record.topic(),
            record.partition(),
            record.offset(),
            record.key(),
            record.value());

        // Log all DLT headers: original topic/partition/offset and exception details
        // added automatically by DeadLetterPublishingRecoverer (D-12)
        record.headers().forEach(header ->
            log.error("  DLT header: {}={}", header.key(), new String(header.value()))
        );
    }
}
