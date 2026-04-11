package au.com.bankforge.notification.kafka;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Integration test for AustracAuditListener threshold detection.
 *
 * Uses a logback ListAppender attached to the AUSTRAC_AUDIT logger to capture
 * audit events in memory — avoiding the logback springProperty timing issue
 * that prevents file-based assertions when log.austrac.path is set via
 * @DynamicPropertySource (logback initializes before Spring dynamic properties
 * are registered).
 *
 * The ListAppender captures structured log events including all key-value pairs
 * added via StructuredArguments.kv(), satisfying the behavioral requirement:
 * threshold detection + structured field presence.
 */
@Testcontainers
@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AustracAuditListenerTest {

    @Container
    static KafkaContainer kafka = new KafkaContainer(
            DockerImageName.parse("apache/kafka:3.9.2")
    );

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("spring.flyway.enabled", () -> "false");
    }

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    private ListAppender<ILoggingEvent> listAppender;
    private Logger austracLogger;

    @BeforeEach
    void setUp() {
        // Attach an in-memory appender to the AUSTRAC_AUDIT logger
        austracLogger = (Logger) LoggerFactory.getLogger("AUSTRAC_AUDIT");
        listAppender = new ListAppender<>();
        listAppender.start();
        austracLogger.addAppender(listAppender);
    }

    @AfterEach
    void tearDown() {
        austracLogger.detachAppender(listAppender);
        listAppender.stop();
    }

    @Test
    @Order(1)
    void shouldLogAustracEventForTransferAboveThreshold() {
        UUID transferId = UUID.randomUUID();
        String payload = """
            {
              "transferId": "%s",
              "fromAccountId": "%s",
              "toAccountId": "%s",
              "amount": "15000.0000",
              "currency": "AUD"
            }
            """.formatted(transferId, UUID.randomUUID(), UUID.randomUUID());

        kafkaTemplate.send("banking.transfer.events", transferId.toString(), payload);

        // Wait for the AUSTRAC listener to consume and log the threshold event
        await().atMost(30, TimeUnit.SECONDS).untilAsserted(() -> {
            boolean found = listAppender.list.stream().anyMatch(event -> {
                // StructuredArguments.kv() adds key-value pairs to the event's argument array
                // The formatted message includes the kv pairs as JSON-like key=value
                String formattedMessage = event.getFormattedMessage();
                // Also check the marker / key-value pairs encoded in the message
                Object[] args = event.getArgumentArray();
                if (args == null) return false;
                boolean hasTransferId = false;
                boolean hasAustracEvent = false;
                for (Object arg : args) {
                    String argStr = arg.toString();
                    if (argStr.contains("transfer_id") && argStr.contains(transferId.toString())) {
                        hasTransferId = true;
                    }
                    if (argStr.contains("austrac_event") && argStr.contains("true")) {
                        hasAustracEvent = true;
                    }
                }
                return hasTransferId && hasAustracEvent;
            });
            assertThat(found)
                    .as("AUSTRAC_AUDIT logger should have received an event for transfer %s with austrac_event=true", transferId)
                    .isTrue();
        });
    }

    @Test
    @Order(2)
    void shouldNotLogAustracEventForTransferBelowThreshold() {
        UUID transferId = UUID.randomUUID();
        String payload = """
            {
              "transferId": "%s",
              "fromAccountId": "%s",
              "toAccountId": "%s",
              "amount": "5000.0000",
              "currency": "AUD"
            }
            """.formatted(transferId, UUID.randomUUID(), UUID.randomUUID());

        kafkaTemplate.send("banking.transfer.events", transferId.toString(), payload);

        // Wait long enough for consumer processing, then assert no audit entry
        await().atMost(30, TimeUnit.SECONDS).during(5, TimeUnit.SECONDS).untilAsserted(() -> {
            boolean found = listAppender.list.stream().anyMatch(event -> {
                Object[] args = event.getArgumentArray();
                if (args == null) return false;
                for (Object arg : args) {
                    String argStr = arg.toString();
                    if (argStr.contains("transfer_id") && argStr.contains(transferId.toString())) {
                        return true;
                    }
                }
                return false;
            });
            assertThat(found)
                    .as("AUSTRAC_AUDIT logger should NOT have received an event for sub-threshold transfer %s", transferId)
                    .isFalse();
        });
    }
}
