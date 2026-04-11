package au.com.bankforge.ledger.kafka;

import au.com.bankforge.ledger.repository.LedgerEntryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

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
    }

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

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

        kafkaTemplate.send("banking.transfer.events", transferId.toString(), payload);

        await().atMost(30, TimeUnit.SECONDS).untilAsserted(() -> {
            var entries = ledgerEntryRepository.findAll().stream()
                    .filter(e -> e.getTransferId().equals(transferId))
                    .toList();
            assertThat(entries).hasSize(2);
            assertThat(entries).anyMatch(e -> "DEBIT".equals(e.getEntryType()));
            assertThat(entries).anyMatch(e -> "CREDIT".equals(e.getEntryType()));
        });
    }
}
