package au.com.bankforge.payment.kafka;

import au.com.bankforge.payment.service.TransferStateService;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
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

import java.util.Map;
import java.util.UUID;

/**
 * Consumes banking.transfer.confirmed events published by ledger-service after writing
 * the double-entry ledger pair. Transitions the transfer from POSTING -> CONFIRMED.
 *
 * Closes the Saga loop: ledger-service has persisted the double-entry pair and
 * publishes confirmation via Debezium CDC -> payment-service transitions POSTING -> CONFIRMED.
 *
 * Resilience: @RetryableTopic provides exponential backoff + DLT. If a confirmation
 * cannot be applied (e.g., transfer not found), the event is routed to the DLT after
 * 4 attempts. POSTING state transfers that don't receive confirmation within the retry
 * window will require manual investigation.
 *
 * OTel trace continuity: reads the "traceparent" Kafka header (set by Debezium from the
 * ledger outbox column), reconstructs the remote SpanContext via W3CTraceContextPropagator,
 * and creates a child span under the originating payment-service trace so the full saga
 * appears as one connected waterfall in Jaeger.
 *
 * JACKSON 3: Uses tools.jackson.databind.ObjectMapper (Spring Boot 4 / Jackson 3).
 * Do NOT use com.fasterxml.jackson.databind.ObjectMapper (Jackson 2, will not compile).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TransferConfirmationListener {

    private static final Tracer TRACER = GlobalOpenTelemetry.getTracer("payment-service");

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
            @Header(KafkaHeaders.OFFSET) long offset,
            @Header(value = "traceparent", required = false) String traceparent) {

        log.info("Payment-service received transfer confirmation: topic={} offset={}", topic, offset);

        // Reconstruct remote parent context from traceparent header and wrap business
        // logic in a child span so this consumer appears in the originating trace.
        Span processingSpan = buildChildSpan(TRACER, "banking.transfer.confirmed process", traceparent);
        try (Scope scope = processingSpan.makeCurrent()) {

            UUID transferId = parseTransferId(payload);
            // setAttribute makes this span searchable by banking.transaction.id in Jaeger.
            Span.current().setAttribute("banking.transaction.id", transferId.toString());
            transferStateService.confirm(transferId);
            log.info("Transfer confirmed: transferId={}", transferId);

        } finally {
            processingSpan.end();
        }
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
