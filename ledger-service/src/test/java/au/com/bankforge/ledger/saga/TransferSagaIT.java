package au.com.bankforge.ledger.saga;

import au.com.bankforge.ledger.repository.LedgerEntryRepository;
import au.com.bankforge.ledger.repository.LedgerOutboxEventRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.Collections;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * End-to-end Saga integration test for the ledger-service outbox pattern.
 *
 * Covers 3 scenarios (D-22, D-23, D-24, D-25):
 *   1. Happy path — event received -> debit + credit + outbox row written
 *   2. Idempotency — duplicate event -> exactly 2 ledger entries, exactly 1 outbox row
 *   3. DLT routing — poison pill -> message lands in banking.transfer.events.DLT
 *
 * CRITICAL: Uses non-transactional KafkaTemplate sends (no executeInTransaction).
 * The application's KafkaTemplate is non-transactional after EOS properties were
 * removed from application.yml in Plan 02. Tests must match that config.
 */
@Testcontainers
@SpringBootTest
class TransferSagaIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("ledgerdb")
            .withUsername("ledger")
            .withPassword("secret");

    @Container
    static KafkaContainer kafka = new KafkaContainer(
            DockerImageName.parse("apache/kafka:3.9.2")
    );

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("spring.flyway.enabled", () -> "true");
        // CRITICAL: Do NOT add spring.kafka.producer.transaction-id-prefix (D-24 / RESEARCH Pitfall 3)
        // CRITICAL: Do NOT add spring.kafka.consumer.isolation-level (RESEARCH Pitfall 2)
        // Non-transactional producer matches real application config after Plan 02 cleanup.
    }

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @Autowired
    private LedgerOutboxEventRepository ledgerOutboxEventRepository;

    /**
     * D-25 Scenario 1: Happy path.
     * Verify that a valid transfer event causes:
     * - Exactly 2 ledger entries (1 DEBIT + 1 CREDIT)
     * - Exactly 1 outbox row with the correct aggregate type and event type
     */
    @Test
    void shouldWriteEntriesAndOutboxOnTransferEvent() {
        UUID transferId = UUID.randomUUID();
        UUID fromAccount = UUID.randomUUID();
        UUID toAccount = UUID.randomUUID();
        String payload = """
                {
                  "transferId": "%s",
                  "fromAccountId": "%s",
                  "toAccountId": "%s",
                  "amount": "500.0000",
                  "currency": "AUD"
                }
                """.formatted(transferId, fromAccount, toAccount);

        // Non-transactional send — matching the post-EOS refactored application config
        kafkaTemplate.send("banking.transfer.events", transferId.toString(), payload);

        // Assert ledger entries written
        await().atMost(30, TimeUnit.SECONDS).untilAsserted(() -> {
            var entries = ledgerEntryRepository.findByTransferId(transferId);
            assertThat(entries).hasSize(2);
            assertThat(entries).anyMatch(e -> "DEBIT".equals(e.getEntryType()));
            assertThat(entries).anyMatch(e -> "CREDIT".equals(e.getEntryType()));
        });

        // Assert outbox row written — Debezium CDC will publish confirmation to banking.transfer.confirmed
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            var outboxRows = ledgerOutboxEventRepository.findAll().stream()
                    .filter(o -> transferId.toString().equals(o.getAggregateid()))
                    .toList();
            assertThat(outboxRows).hasSize(1);
            assertThat(outboxRows.getFirst().getAggregatetype()).isEqualTo("transfer-confirmation");
            assertThat(outboxRows.getFirst().getType()).isEqualTo("TransferConfirmed");
            assertThat(outboxRows.getFirst().getPayload()).contains(transferId.toString());
        });
    }

    /**
     * D-25 Scenario 2: Idempotency.
     * Duplicate delivery of the same event must produce:
     * - Exactly 2 ledger entries (not 4)
     * - Exactly 1 outbox row (not 2)
     */
    @Test
    void shouldBeIdempotentOnDuplicateDelivery() {
        UUID transferId = UUID.randomUUID();
        UUID fromAccount = UUID.randomUUID();
        UUID toAccount = UUID.randomUUID();
        String payload = """
                {
                  "transferId": "%s",
                  "fromAccountId": "%s",
                  "toAccountId": "%s",
                  "amount": "250.0000",
                  "currency": "AUD"
                }
                """.formatted(transferId, fromAccount, toAccount);

        // Send the same event twice to simulate at-least-once redelivery
        kafkaTemplate.send("banking.transfer.events", transferId.toString(), payload);
        kafkaTemplate.send("banking.transfer.events", transferId.toString(), payload);

        // Wait for both to be processed — result must be exactly 2 entries (1 DEBIT + 1 CREDIT)
        await().atMost(30, TimeUnit.SECONDS).untilAsserted(() -> {
            var entries = ledgerEntryRepository.findByTransferId(transferId);
            // Idempotency guard: duplicate delivery must NOT produce 4 entries
            assertThat(entries).hasSize(2);
        });

        // Exactly 1 outbox row (not 2) — idempotency no-op skips outbox write
        var outboxRows = ledgerOutboxEventRepository.findAll().stream()
                .filter(o -> transferId.toString().equals(o.getAggregateid()))
                .toList();
        assertThat(outboxRows).hasSize(1);
    }

    /**
     * D-25 Scenario 3: DLT routing.
     * A poison pill (invalid JSON) must land in banking.transfer.events.DLT
     * after retry exhaustion via DeadLetterPublishingRecoverer.
     *
     * ExponentialBackOff(1000, 2.0) maxElapsed=30000 means ~3 retries over ~7s.
     */
    @Test
    void shouldRoutePoisonPillToDlt() {
        String poisonPayload = "this is not valid JSON {{{";
        String poisonKey = "poison-key-" + UUID.randomUUID();

        kafkaTemplate.send("banking.transfer.events", poisonKey, poisonPayload);

        // Create a consumer for the DLT topic to verify routing
        Properties consumerProps = new Properties();
        consumerProps.put("bootstrap.servers", kafka.getBootstrapServers());
        consumerProps.put("group.id", "it-dlt-verifier-" + UUID.randomUUID());
        consumerProps.put("auto.offset.reset", "earliest");
        consumerProps.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        consumerProps.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(consumerProps)) {
            consumer.subscribe(Collections.singletonList("banking.transfer.events.DLT"));

            // ExponentialBackOff(1000, 2.0) maxElapsed=30000 means ~3 retries over ~7s.
            // Add generous timeout for container startup variance.
            await().atMost(60, TimeUnit.SECONDS).pollInterval(Duration.ofSeconds(1)).untilAsserted(() -> {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                boolean found = false;
                for (ConsumerRecord<String, String> r : records) {
                    if (poisonKey.equals(r.key())) {
                        found = true;
                        assertThat(r.value()).isEqualTo(poisonPayload);
                    }
                }
                assertThat(found).as("Poison pill should land in banking.transfer.events.DLT topic").isTrue();
            });
        }
    }
}
