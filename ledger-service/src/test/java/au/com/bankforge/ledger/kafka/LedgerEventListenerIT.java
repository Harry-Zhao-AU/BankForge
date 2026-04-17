package au.com.bankforge.ledger.kafka;

import au.com.bankforge.ledger.entity.LedgerEntry;
import au.com.bankforge.ledger.repository.LedgerEntryRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.Collections;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

@Testcontainers
@SpringBootTest
class LedgerEventListenerIT {

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
        registry.add("spring.kafka.producer.transaction-id-prefix", () -> "test-ledger-tx-");
        registry.add("spring.kafka.consumer.isolation-level", () -> "read_committed");
    }

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @MockitoSpyBean
    private LedgerEntryRepository spiedLedgerEntryRepository;

    @Autowired
    private LedgerEventListener listener;

    @Test
    void shouldWriteDebitAndCreditOnTransferEvent() {
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

        kafkaTemplate.executeInTransaction(ops -> {
            ops.send("banking.transfer.events", transferId.toString(), payload);
            return null;
        });

        await().atMost(30, TimeUnit.SECONDS).untilAsserted(() -> {
            var entries = spiedLedgerEntryRepository.findAll().stream()
                    .filter(e -> e.getTransferId().equals(transferId))
                    .toList();
            assertThat(entries).hasSize(2);
            assertThat(entries).anyMatch(e -> "DEBIT".equals(e.getEntryType()));
            assertThat(entries).anyMatch(e -> "CREDIT".equals(e.getEntryType()));
        });

        // Also verify that banking.transfer.confirmed was published by LedgerEventListener
        Properties consumerProps = new Properties();
        consumerProps.put("bootstrap.servers", kafka.getBootstrapServers());
        consumerProps.put("group.id", "it-verifier-" + UUID.randomUUID());
        consumerProps.put("auto.offset.reset", "earliest");
        consumerProps.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        consumerProps.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        consumerProps.put("isolation.level", "read_committed");

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(consumerProps)) {
            consumer.subscribe(Collections.singletonList("banking.transfer.confirmed"));
            await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                boolean found = false;
                for (ConsumerRecord<String, String> r : records) {
                    if (r.key().equals(transferId.toString())) {
                        found = true;
                        assertThat(r.value()).contains(transferId.toString());
                    }
                }
                assertThat(found).as("Confirmation message for transferId=%s not found", transferId).isTrue();
            });
        }
    }

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

        // Send the same event twice to simulate duplicate delivery
        kafkaTemplate.executeInTransaction(ops -> {
            ops.send("banking.transfer.events", transferId.toString(), payload);
            return null;
        });
        kafkaTemplate.executeInTransaction(ops -> {
            ops.send("banking.transfer.events", transferId.toString(), payload);
            return null;
        });

        // Wait for both messages to be processed — result must be exactly 2 entries (1 DEBIT + 1 CREDIT)
        await().atMost(30, TimeUnit.SECONDS).untilAsserted(() -> {
            var entries = spiedLedgerEntryRepository.findAll().stream()
                    .filter(e -> e.getTransferId().equals(transferId))
                    .toList();
            // Idempotency guard: duplicate delivery must NOT produce 4 entries
            assertThat(entries).hasSize(2);
            assertThat(entries).anyMatch(e -> "DEBIT".equals(e.getEntryType()));
            assertThat(entries).anyMatch(e -> "CREDIT".equals(e.getEntryType()));
        });
    }

    @Test
    void shouldRollbackBothEntriesWhenSecondSaveFails() {
        UUID transferId = UUID.randomUUID();
        UUID fromAccount = UUID.randomUUID();
        UUID toAccount = UUID.randomUUID();
        String payload = """
            {
              "transferId": "%s",
              "fromAccountId": "%s",
              "toAccountId": "%s",
              "amount": "100.0000",
              "currency": "AUD"
            }
            """.formatted(transferId, fromAccount, toAccount);

        // Make the second save() call throw — simulates a crash mid-transaction
        AtomicInteger saveCallCount = new AtomicInteger(0);
        doAnswer(invocation -> {
            if (saveCallCount.incrementAndGet() == 2) {
                throw new RuntimeException("Simulated crash after first save");
            }
            return invocation.callRealMethod();
        }).when(spiedLedgerEntryRepository).save(any(LedgerEntry.class));

        try {
            kafkaTemplate.executeInTransaction(ops -> {
                ops.send("banking.transfer.events", transferId.toString(), payload);
                return null;
            });

            // Wait long enough for the message to be consumed, error handler to exhaust retries
            // DefaultErrorHandler ExponentialBackOff(1000, 2.0) maxElapsed=30s
            await().atMost(45, TimeUnit.SECONDS).pollDelay(5, TimeUnit.SECONDS).untilAsserted(() -> {
                var entries = spiedLedgerEntryRepository.findByTransferId(transferId);
                assertThat(entries).as("@Transactional should roll back both entries on exception").isEmpty();
            });
        } finally {
            // Reset spy to prevent stubbing from leaking into other tests
            reset(spiedLedgerEntryRepository);
        }
    }

    @Test
    void shouldNotDuplicateEntriesOnRetryAfterKafkaFailure() {
        UUID transferId = UUID.randomUUID();
        UUID fromAccount = UUID.randomUUID();
        UUID toAccount = UUID.randomUUID();
        String payload = """
            {
              "transferId": "%s",
              "fromAccountId": "%s",
              "toAccountId": "%s",
              "amount": "75.0000",
              "currency": "AUD"
            }
            """.formatted(transferId, fromAccount, toAccount);

        // First delivery — normal processing, entries written + confirmation published
        kafkaTemplate.executeInTransaction(ops -> {
            ops.send("banking.transfer.events", transferId.toString(), payload);
            return null;
        });

        await().atMost(30, TimeUnit.SECONDS).untilAsserted(() -> {
            var entries = spiedLedgerEntryRepository.findByTransferId(transferId);
            assertThat(entries).hasSize(2);
        });

        // Simulate retry: call the listener method directly with the same payload.
        // This represents the case where JPA committed but Kafka TX failed,
        // causing the broker to redeliver the message.
        listener.onTransferEvent(payload, "banking.transfer.events", 999L, null);

        // Still exactly 2 entries — idempotency guard prevented duplicates
        var entries = spiedLedgerEntryRepository.findByTransferId(transferId);
        assertThat(entries).as("Idempotency guard must prevent duplicate entries on retry").hasSize(2);
    }
}
