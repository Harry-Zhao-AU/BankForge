package au.com.bankforge.payment.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for IdempotencyService using Testcontainers Redis and PostgreSQL.
 *
 * PostgreSQL is required because the Spring Boot context loads JPA/Flyway.
 * Redis is required for the actual idempotency operations being tested.
 *
 * Tests cover TXNS-05: Redis setIfAbsent semantics — first call returns true,
 * second call with same key returns false (cached).
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class IdempotencyServiceTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7.2-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        // Override Testcontainers JDBC URL with the actual mapped host/port
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        // Point Redis to the dynamically allocated Testcontainers port
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired
    private IdempotencyService idempotencyService;

    @Test
    void setIfNew_returnsTrueOnFirstCall() {
        boolean result = idempotencyService.setIfNew("test-key-1", "{\"transferId\":\"abc\"}");
        assertThat(result).isTrue();
    }

    @Test
    void setIfNew_returnsFalseOnDuplicateKey() {
        idempotencyService.setIfNew("test-key-2", "{\"transferId\":\"abc\"}");
        boolean secondCall = idempotencyService.setIfNew("test-key-2", "{\"transferId\":\"different\"}");
        assertThat(secondCall).isFalse();
    }

    @Test
    void getCached_returnsCachedValueAfterSetIfNew() {
        idempotencyService.setIfNew("test-key-3", "{\"transferId\":\"xyz\"}");
        Optional<String> cached = idempotencyService.getCached("test-key-3");
        assertThat(cached).isPresent();
        assertThat(cached.get()).isEqualTo("{\"transferId\":\"xyz\"}");
    }

    @Test
    void getCached_returnsEmptyForUnknownKey() {
        Optional<String> result = idempotencyService.getCached("unknown-key-9999");
        assertThat(result).isEmpty();
    }
}
