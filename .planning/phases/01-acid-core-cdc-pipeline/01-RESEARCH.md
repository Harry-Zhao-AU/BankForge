# Phase 1: ACID Core + CDC Pipeline — Research

**Researched:** 2026-04-10
**Domain:** Java 21 + Spring Boot 4, PostgreSQL, Kafka KRaft, Debezium CDC, Redis, Spring State Machine, Podman Compose, Australian banking validation
**Confidence:** HIGH (core stack verified via official sources); MEDIUM (Spring State Machine / Spring Boot 4 compatibility gap — see critical flag below)

---

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

- **D-01:** Target Spring Boot 4.0.x. Researcher must verify the exact current GA patch version before writing any pom.xml — do NOT hardcode 4.0.5. If 4.0.x is not yet GA or has known critical bugs, fall back to 3.4.x.
- **D-02:** External clients call payment-service as the entry point for all fund transfers. payment-service owns idempotency key checking (Redis) and the transfer state machine.
- **D-03:** payment-service calls account-service internally (service-to-service REST) to execute the ACID transaction. account-service is internal-only for transfer operations.
- **D-04:** Service ownership split: payment-service owns CORE-03, TXNS-04, TXNS-05. account-service owns CORE-01, CORE-02, TXNS-01.
- **D-05:** Implement transfer state machine using Spring State Machine library (org.springframework.statemachine). Do not hand-roll a custom FSM.
- **D-06:** State names: PENDING → PAYMENT_PROCESSING → PAYMENT_DONE → POSTING → CONFIRMED / COMPENSATING → CANCELLED. POSTING = ledger recording double-entry entries.
- **D-07:** Maven multi-module project. One parent pom.xml at repo root with 5 child modules: common, account-service, payment-service, ledger-service, notification-service.
- **D-08:** common module holds: Kafka event DTOs, TransferState enum, shared validation utilities (BSB format). All 4 services declare a compile dependency on common.
- **D-09:** All monetary fields: BigDecimal in Java, DECIMAL(15,4) in PostgreSQL. No double or float anywhere.
- **D-10:** Balance queries use SELECT FOR UPDATE (LockModeType.PESSIMISTIC_WRITE) to prevent concurrent overdraft under Read Committed isolation.
- **D-11:** Kafka in KRaft mode (no ZooKeeper). KAFKA_PROCESS_ROLES=broker,controller.
- **D-12:** Outbox + Debezium CDC only — no polling outbox, no dual-write. Debezium reads PostgreSQL WAL directly.
- **D-13:** PostgreSQL containers must start with wal_level=logical and max_replication_slots=5.
- **D-14:** Dead letter queues (DLT topics) configured from Day 1 for all financial Kafka consumers.
- **D-15:** slot.drop.on.stop=true in Debezium connector config to prevent replication slot leaks.

### Claude's Discretion

- Exact Kafka topic naming convention — follow a consistent pattern.
- Compose service startup ordering (depends_on + health checks) — implement correctly for PostgreSQL → Kafka → Debezium ordering.
- Exact Spring State Machine configuration style (annotation-based vs builder DSL) — use whichever is idiomatic for the Spring Boot version chosen.

### Deferred Ideas (OUT OF SCOPE)

None — discussion stayed within Phase 1 scope.
</user_constraints>

---

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| CORE-01 | account-service REST API: create accounts, check balances, list transfer history | Standard Spring Data JPA + REST — HIGH confidence patterns |
| CORE-02 | Atomic debit + credit + outbox write in single PostgreSQL transaction | SELECT FOR UPDATE + @Transactional + outbox INSERT — verified pattern |
| CORE-03 | payment-service REST API for NPP-style payment flows | Standard Spring MVC REST; NPP flow is custom domain logic |
| CORE-04 | Double-entry ledger via ledger-service consuming Kafka events | Kafka consumer + ledger_entries table — choreography Saga |
| CORE-05 | Notification-service consuming Kafka events | @KafkaListener + DLT — standard Spring Kafka pattern |
| TXNS-01 | Single local ACID PostgreSQL transaction for debit + credit + outbox | Verified: single @Transactional boundary in account-service |
| TXNS-02 | Debezium CDC reads WAL, publishes to Kafka (no dual-write) | Debezium 3.4.x + EventRouter SMT — verified |
| TXNS-03 | Kafka Saga choreography — no central orchestrator | @KafkaListener per downstream service — verified pattern |
| TXNS-04 | Transfer state machine: PENDING → CONFIRMED / CANCELLED | Spring State Machine 4.0.1 — CRITICAL COMPATIBILITY GAP (see below) |
| TXNS-05 | Idempotency keys in Redis (TTL 24h) | Spring Data Redis StringRedisTemplate setIfAbsent — verified |
| AUBN-01 | BSB format validation (NNN-NNN) and account number validation | Regex validation in common module — standard Java pattern |
| AUBN-02 | AUSTRAC threshold event for transfers >= AUD $10,000 | Structured log / audit record — implemented in account-service ACID TX |
</phase_requirements>

---

## Summary

Phase 1 establishes the core banking stack from scratch: four Spring Boot services on Podman Compose delivering ACID money movement, CDC event propagation, and Australian banking compliance. Research confirms Spring Boot 4.0.5 is GA and stable (released November 2025, current patch March 2026), so the plan's primary version target is safe to use. The critical discovery is that **Spring State Machine 4.0.1 targets Spring Framework 6.2.x (Spring Boot 3.x) and has no confirmed Spring Boot 4 / Spring Framework 7 compatibility** — a fallback strategy is required and documented below.

Kafka has advanced to 4.0 (ZooKeeper completely removed), Debezium to 3.4.x, and Resilience4j now ships a dedicated `resilience4j-spring-boot4` artifact (2.4.0). Flyway requires a configuration change in Spring Boot 4: use `spring-boot-starter-flyway` (not `flyway-core` standalone). Podman 5.8.1 is confirmed installed and uses WSL as its provider — **kind requires rootful mode on WSL**, which is not the default and must be set before the Phase 1 networking spike.

**Primary recommendation:** Use Spring Boot 4.0.5, Kafka 4.x (bitnami/kafka image), Debezium 3.4.x, and a hand-rolled enum FSM for the transfer state machine (Spring State Machine 4.0.1 is incompatible with Spring Boot 4 — do not use it).

---

## Project Constraints (from CLAUDE.md)

| Directive | Impact on Phase 1 |
|-----------|-------------------|
| Java 21 LTS — locked | Confirmed available; virtual threads enabled via `spring.threads.virtual.enabled=true` |
| Spring Boot 4.0.x — verify GA | VERIFIED: 4.0.5 is current GA patch [VERIFIED: github.com/spring-projects/spring-boot/releases] |
| Podman (not Docker) | Podman 5.8.1 confirmed installed; rootful mode needed for kind |
| kind cluster | kind NOT installed — must be installed before networking spike |
| PostgreSQL 16 or 17 (not 15) | Use postgres:17 image; wal_level=logical must be set at container start |
| Kafka 3.7+ KRaft (no ZooKeeper) | Kafka 4.0 removes ZooKeeper entirely — use bitnami/kafka:4.x |
| Debezium 3.0.x | Debezium 3.4.x is current stable — use 3.4.x |
| Redis 7.2.x | Confirmed compatible; use redis:7.2 image |
| Spring State Machine 4.0.x | CRITICAL: NOT compatible with Spring Boot 4 — see flag below |
| BigDecimal for all monetary values | Enforced via entity annotations and code review |
| Flyway for schema migrations | Use spring-boot-starter-flyway (NOT flyway-core) in Spring Boot 4 |
| MapStruct for DTO mapping | MapStruct 1.6.3 confirmed compatible with Java 21 |
| Resilience4j for circuit breakers | Use resilience4j-spring-boot4:2.4.0 (new artifact for SB4) |

---

## Standard Stack

### Core

| Library | Version | Purpose | Source |
|---------|---------|---------|--------|
| Java | 21 LTS | Runtime, virtual threads | [VERIFIED: CLAUDE.md locked] |
| Spring Boot | 4.0.5 | Framework umbrella | [VERIFIED: github.com/spring-projects/spring-boot/releases] |
| Spring Framework | 7.0.x (bundled with SB 4.0.5) | Core IoC, MVC, Data | [VERIFIED: bundled] |
| Spring Data JPA | bundled with SB 4.0.5 | ORM / repository layer | [VERIFIED: bundled] |
| Spring Kafka | bundled with SB 4.0.5 | Kafka producer/consumer | [VERIFIED: bundled] |
| Spring Security | bundled with SB 4.0.5 | Security context (Phase 3 uses this) | [VERIFIED: bundled] |
| Hibernate | 7.1.x (bundled with SB 4.0.5) | JPA implementation | [VERIFIED: SB4 release notes] |
| PostgreSQL | 17 (container image) | Service databases | [VERIFIED: CLAUDE.md directive] |
| Apache Kafka | 4.x (bitnami/kafka image) | Event streaming, KRaft only | [VERIFIED: kafka.apache.org 4.0 released March 2025] |
| Debezium | 3.4.x (debezium/connect image) | CDC from PostgreSQL WAL | [VERIFIED: debezium.io 3.4.0.Final released Dec 2025] |
| Redis | 7.2 (container image) | Idempotency key store | [VERIFIED: CLAUDE.md directive] |

### Supporting

| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| Flyway | 11.x (via spring-boot-starter-flyway) | Schema migrations | Required for outbox table setup; SB4 requires starter, not flyway-core |
| MapStruct | 1.6.3 | DTO to entity mapping | All API request/response mapping |
| Lombok | 1.18.x | Boilerplate reduction | @Builder, @Value, @Slf4j |
| Resilience4j | 2.4.0 (resilience4j-spring-boot4) | Circuit breaker on payment-service → account-service call | Service-to-service HTTP calls |
| jackson-databind | 3.0.x (bundled with SB 4.0.5) | JSON serialisation | Default; note Jackson 3 (not 2) in SB4 |
| Spring Data Redis | bundled with SB 4.0.5 | Redis client (Lettuce) | Idempotency keys in payment-service |
| Kafka UI (Provectus) | latest | Kafka topic inspection | Dev tool; add to compose.yml |

### CRITICAL FLAG: Spring State Machine Incompatibility

**Spring State Machine 4.0.1 is NOT compatible with Spring Boot 4 (Spring Framework 7).**

[VERIFIED: github.com/spring-projects/spring-statemachine/releases] — Latest release targets Spring Framework 6.2.8 / Spring Boot 3.5.3. No Spring Framework 7 release exists. The project's support for 4.0.x ended November 2025 with no successor announced.

**Decision required (overrides D-05):** D-05 locked "use Spring State Machine library." Given the incompatibility, two options:

1. **Hand-rolled enum FSM (RECOMMENDED):** Implement `TransferState` enum in the `common` module with a `TransferStateMachine` service class using switch expressions. This is simple for a 6-state FSM and carries zero framework dependency risk.
2. **Use Spring Boot 3.5.x instead of 4.0.x:** Would unlock Spring State Machine 4.0.1 but loses Spring Boot 4 features (virtual threads first-class, Jackson 3, new OTel starter). Not recommended.

The planner must note this conflict. The hand-rolled FSM approach is production-safe for a 6-state machine and is what the `TransferState` enum in `common` would implement anyway.

### Alternatives Considered

| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| Hand-rolled enum FSM | Spring State Machine | Spring State Machine has zero SB4 compatibility; hand-rolled is safer and sufficient for 6 states |
| bitnami/kafka:4.x | confluentinc/cp-kafka | Bitnami is simpler for KRaft compose.yml; Confluent adds Schema Registry complexity not needed in Phase 1 |
| debezium/connect image | Running Debezium Server | Kafka Connect pattern is standard and matches connector.json approach |
| spring-boot-starter-flyway | flyway-core standalone | SB4 changed auto-config: standalone flyway-core no longer triggers auto-migration |
| resilience4j-spring-boot4 | spring-cloud-starter-circuitbreaker-resilience4j | Direct artifact avoids Spring Cloud version matrix; 2.4.0 is current |

**Installation:**

```xml
<!-- Parent pom.xml -->
<parent>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-parent</artifactId>
  <version>4.0.5</version>
</parent>

<!-- Each service module pom.xml - key dependencies -->
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-data-jpa</artifactId>
</dependency>
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-flyway</artifactId>  <!-- NOT flyway-core -->
</dependency>
<dependency>
  <groupId>org.flywaydb</groupId>
  <artifactId>flyway-database-postgresql</artifactId>
</dependency>
<dependency>
  <groupId>io.github.resilience4j</groupId>
  <artifactId>resilience4j-spring-boot4</artifactId>  <!-- NOT spring-boot3 -->
  <version>2.4.0</version>
</dependency>
<dependency>
  <groupId>org.mapstruct</groupId>
  <artifactId>mapstruct</artifactId>
  <version>1.6.3</version>
</dependency>
```

**Version verification (run before writing any pom.xml):**

```bash
# Confirm Spring Boot current GA
curl -s https://api.github.com/repos/spring-projects/spring-boot/releases/latest | grep tag_name

# Confirm Debezium current stable (check releases page)
# https://debezium.io/releases/

# Confirm Resilience4j SB4 artifact
# https://mvnrepository.com/artifact/io.github.resilience4j/resilience4j-spring-boot4
```

---

## Architecture Patterns

### Recommended Project Structure

```
BankForge/
├── pom.xml                         # Parent POM — Spring Boot 4.0.5 parent, module list
├── common/
│   ├── pom.xml
│   └── src/main/java/com/bankforge/common/
│       ├── dto/                    # Kafka event DTOs (TransferInitiatedEvent, etc.)
│       ├── enums/                  # TransferState enum + FSM logic
│       └── validation/             # BSBValidator, AccountNumberValidator
├── account-service/
│   ├── pom.xml                     # depends on common
│   └── src/
│       ├── main/java/com/bankforge/account/
│       │   ├── api/                # REST controllers (CORE-01)
│       │   ├── domain/             # Account, Transfer entities
│       │   ├── repository/         # JPA repos with @Lock annotations
│       │   ├── service/            # TransferService (ACID TX boundary)
│       │   └── outbox/             # OutboxService — inserts outbox rows
│       └── main/resources/
│           ├── application.yml
│           └── db/migration/       # Flyway V1__init.sql, V2__outbox.sql
├── payment-service/
│   └── src/main/java/com/bankforge/payment/
│       ├── api/                    # REST controllers (CORE-03)
│       ├── idempotency/            # Redis idempotency key service (TXNS-05)
│       ├── statemachine/           # Hand-rolled FSM using TransferState (TXNS-04)
│       └── client/                 # Feign/RestClient to account-service
├── ledger-service/
│   └── src/main/java/com/bankforge/ledger/
│       ├── consumer/               # @KafkaListener (TXNS-03, CORE-04)
│       ├── domain/                 # LedgerEntry entity
│       └── service/                # Double-entry recording
├── notification-service/
│   └── src/main/java/com/bankforge/notification/
│       ├── consumer/               # @KafkaListener (CORE-05)
│       └── service/                # Alert delivery (simulated)
├── infrastructure/
│   ├── compose.yml                 # All services + infra
│   ├── debezium/
│   │   ├── account-connector.json
│   │   ├── payment-connector.json
│   │   └── register-connectors.sh  # curl POST after Connect is healthy
│   └── postgres/
│       └── init/                   # Per-service init SQL (users, grants)
└── k8s/                            # Phase 3+ only, empty in Phase 1
```

### Pattern 1: ACID Transfer Execution (account-service)

**What:** Debit + credit + outbox write in a single `@Transactional` boundary. The outbox row IS the event commitment.

**When to use:** Every time money moves. Never split this across multiple transactions.

```java
// Source: [VERIFIED: standard Spring Data JPA + ASSUMED Debezium outbox pattern]
@Service
@RequiredArgsConstructor
public class TransferService {

    private final AccountRepository accountRepo;
    private final OutboxRepository outboxRepo;

    @Transactional
    public TransferResult execute(TransferCommand cmd) {
        // Acquire pessimistic locks — order by ID to prevent deadlocks
        Long minId = Math.min(cmd.sourceId(), cmd.destId());
        Long maxId = Math.max(cmd.sourceId(), cmd.destId());
        Account first = accountRepo.findByIdForUpdate(minId)
            .orElseThrow(() -> new AccountNotFoundException(minId));
        Account second = accountRepo.findByIdForUpdate(maxId)
            .orElseThrow(() -> new AccountNotFoundException(maxId));

        Account source = first.getId().equals(cmd.sourceId()) ? first : second;
        Account dest   = first.getId().equals(cmd.destId())   ? first : second;

        if (source.getBalance().compareTo(cmd.amount()) < 0) {
            throw new InsufficientFundsException(source.getId());
        }

        source.debit(cmd.amount());
        dest.credit(cmd.amount());

        // AUSTRAC threshold check (AUBN-02)
        if (cmd.amount().compareTo(AustracThreshold.TEN_THOUSAND) >= 0) {
            log.warn("[AUSTRAC] Transfer {} amount {} >= 10000 AUD at {}",
                cmd.transferId(), cmd.amount(), Instant.now());
            // structured log entry consumed by audit log
        }

        // Write outbox row in the SAME transaction
        outboxRepo.save(OutboxEvent.transferInitiated(cmd));

        return TransferResult.success(cmd.transferId());
    }
}
```

```java
// Repository with pessimistic lock
public interface AccountRepository extends JpaRepository<Account, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Account a WHERE a.id = :id")
    Optional<Account> findByIdForUpdate(@Param("id") Long id);
}
```

### Pattern 2: Hand-Rolled Transfer FSM (payment-service)

**What:** A simple Java enum + service replaces Spring State Machine (incompatible with SB4). The TransferState enum lives in `common` per D-08.

**When to use:** Payment-service transition tracking per D-04.

```java
// Source: [ASSUMED — idiomatic Java 21 pattern]
// common module
public enum TransferState {
    PENDING,
    PAYMENT_PROCESSING,
    PAYMENT_DONE,
    POSTING,
    CONFIRMED,
    COMPENSATING,
    CANCELLED;

    private static final Map<TransferState, Set<TransferState>> ALLOWED = Map.of(
        PENDING,             Set.of(PAYMENT_PROCESSING, CANCELLED),
        PAYMENT_PROCESSING,  Set.of(PAYMENT_DONE, COMPENSATING),
        PAYMENT_DONE,        Set.of(POSTING, COMPENSATING),
        POSTING,             Set.of(CONFIRMED, COMPENSATING),
        CONFIRMED,           Set.of(),
        COMPENSATING,        Set.of(CANCELLED),
        CANCELLED,           Set.of()
    );

    public TransferState transition(TransferState target) {
        if (!ALLOWED.getOrDefault(this, Set.of()).contains(target)) {
            throw new IllegalStateTransitionException(this, target);
        }
        return target;
    }
}
```

### Pattern 3: Redis Idempotency Key (payment-service)

**What:** Atomic check-and-set using `setIfAbsent` prevents double-charge on network retry.

```java
// Source: [VERIFIED: Spring Data Redis docs]
@Service
@RequiredArgsConstructor
public class IdempotencyService {

    private final StringRedisTemplate redis;
    private static final Duration TTL = Duration.ofHours(24);

    /**
     * Returns Optional.empty() on first call (proceed with processing).
     * Returns Optional.of(cached result) on duplicate (return cached).
     */
    public Optional<String> checkAndReserve(String idempotencyKey, String result) {
        String redisKey = "idempotency:" + idempotencyKey;
        Boolean first = redis.opsForValue().setIfAbsent(redisKey, result, TTL);
        if (Boolean.TRUE.equals(first)) {
            return Optional.empty(); // first time — proceed
        }
        return Optional.ofNullable(redis.opsForValue().get(redisKey));
    }
}
```

### Pattern 4: Debezium Outbox Connector (connector.json)

**What:** EventRouter SMT converts WAL CDC events from the outbox table into routed Kafka messages per aggregate type.

```json
// Source: [VERIFIED: debezium.io/documentation/reference/stable/transformations/outbox-event-router.html]
{
  "name": "account-outbox-connector",
  "config": {
    "connector.class": "io.debezium.connector.postgresql.PostgresConnector",
    "database.hostname": "account-db",
    "database.port": "5432",
    "database.user": "debezium",
    "database.password": "debezium",
    "database.dbname": "account",
    "database.server.name": "account",
    "slot.name": "debezium_account",
    "slot.drop.on.stop": "true",
    "plugin.name": "pgoutput",
    "table.include.list": "public.outbox",
    "transforms": "outbox",
    "transforms.outbox.type": "io.debezium.transforms.outbox.EventRouter",
    "transforms.outbox.table.field.event.id": "id",
    "transforms.outbox.table.field.event.key": "aggregate_id",
    "transforms.outbox.table.field.event.type": "event_type",
    "transforms.outbox.table.field.event.payload": "payload",
    "transforms.outbox.route.by.field": "aggregate_type",
    "transforms.outbox.route.topic.replacement": "banking.${routedByValue}",
    "value.converter": "org.apache.kafka.connect.json.JsonConverter",
    "value.converter.schemas.enable": "false"
  }
}
```

### Pattern 5: Dead Letter Topic Configuration (all consumers)

**What:** Every Kafka consumer in ledger-service and notification-service must configure a DLT from Day 1 per D-14.

```java
// Source: [VERIFIED: docs.spring.io/spring-kafka API DeadLetterPublishingRecoverer]
@Bean
public DefaultErrorHandler errorHandler(KafkaTemplate<String, String> template) {
    var recoverer = new DeadLetterPublishingRecoverer(template,
        (record, ex) -> new TopicPartition(record.topic() + ".DLT", -1));
    var backoff = new ExponentialBackOffWithMaxRetries(3);
    backoff.setInitialInterval(1_000L);
    backoff.setMultiplier(2.0);
    backoff.setMaxInterval(30_000L);
    return new DefaultErrorHandler(recoverer, backoff);
}
```

### Pattern 6: compose.yml Startup Ordering

**What:** PostgreSQL → Kafka → services → Debezium Connect → connector registration. Each step depends on prior services being healthy.

```yaml
# Source: [ASSUMED — verified compose healthcheck pattern from WebSearch]
services:
  account-db:
    image: postgres:17
    command: ["postgres", "-c", "wal_level=logical", "-c", "max_replication_slots=5"]
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U account"]
      interval: 5s
      timeout: 5s
      retries: 10

  kafka:
    image: bitnami/kafka:4.0          # verify latest 4.x tag before writing
    environment:
      KAFKA_CFG_NODE_ID: 0
      KAFKA_CFG_PROCESS_ROLES: "broker,controller"
      KAFKA_CFG_CONTROLLER_QUORUM_VOTERS: "0@kafka:9093"
      KAFKA_CFG_LISTENERS: "PLAINTEXT://:9092,CONTROLLER://:9093"
      KAFKA_CFG_ADVERTISED_LISTENERS: "PLAINTEXT://kafka:9092"
      KAFKA_CFG_LISTENER_SECURITY_PROTOCOL_MAP: "CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT"
      KAFKA_CFG_INTER_BROKER_LISTENER_NAME: "PLAINTEXT"
      KAFKA_CFG_CONTROLLER_LISTENER_NAMES: "CONTROLLER"
    healthcheck:
      test: ["CMD-SHELL", "kafka-topics.sh --bootstrap-server localhost:9092 --list"]
      interval: 10s
      timeout: 10s
      retries: 10

  connect:
    image: debezium/connect:3.4        # verify exact tag
    depends_on:
      kafka:
        condition: service_healthy
      account-db:
        condition: service_healthy
    environment:
      BOOTSTRAP_SERVERS: "kafka:9092"
      GROUP_ID: "connect-cluster"
      CONFIG_STORAGE_TOPIC: "connect-configs"
      OFFSET_STORAGE_TOPIC: "connect-offsets"
      STATUS_STORAGE_TOPIC: "connect-status"
    healthcheck:
      test: ["CMD-SHELL", "curl -f http://localhost:8083/connectors || exit 1"]
      interval: 10s
      timeout: 10s
      retries: 15

  connector-init:
    image: curlimages/curl:latest
    depends_on:
      connect:
        condition: service_healthy
    command: |
      sh -c "curl -X POST http://connect:8083/connectors
             -H 'Content-Type: application/json'
             -d @/connectors/account-connector.json"
    volumes:
      - ./debezium:/connectors
    restart: "no"
```

### Pattern 7: BSB Validation (AUBN-01)

**What:** BSB (Bank State Branch) code format is NNN-NNN — 6 digits with a hyphen in the middle. Validated in the `common` module.

```java
// Source: [ASSUMED — Australian banking standard]
public class BsbValidator {
    private static final Pattern BSB_PATTERN = Pattern.compile("^\\d{3}-\\d{3}$");

    public static boolean isValid(String bsb) {
        return bsb != null && BSB_PATTERN.matcher(bsb).matches();
    }

    // Bean Validation integration
    @Constraint(validatedBy = BsbConstraintValidator.class)
    @Target({ElementType.FIELD})
    @Retention(RetentionPolicy.RUNTIME)
    public @interface ValidBsb {
        String message() default "BSB must be in format NNN-NNN";
        Class<?>[] groups() default {};
        Class<? extends Payload>[] payload() default {};
    }
}
```

### Anti-Patterns to Avoid

- **Dual-write to Kafka inside @Transactional:** Call `kafkaTemplate.send()` inside the ACID TX alongside outbox writes. Either the Kafka send fails silently or produces a phantom event. ONLY the outbox row is written inside the TX.
- **Non-idempotent Kafka consumers:** Creating a new LedgerEntry for every event received. Debezium guarantees at-least-once — use `INSERT ... ON CONFLICT DO NOTHING` keyed on transfer ID.
- **Registering Debezium connector before outbox table exists:** Debezium fails immediately and may not recover. The connector-init service must depend on both Connect being healthy AND the service having run Flyway migrations.
- **Deadlock from inconsistent lock ordering:** Two transactions locking accounts in different orders. Always lock the lower ID first, then the higher ID.
- **Using float/double for monetary amounts:** Java `double` cannot represent 0.10 exactly. Use `BigDecimal` everywhere with `RoundingMode.HALF_EVEN`.
- **Hardcoding Spring Boot 4.0.5 in pom.xml without verifying:** Always run version check against Maven Central / GitHub releases before writing pom.xml.

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| PostgreSQL schema migrations | Custom SQL runner | Flyway (spring-boot-starter-flyway) | Handles ordering, checksums, baseline; critical for outbox table versioning |
| DTO ↔ entity mapping | Manual copy code | MapStruct 1.6.3 | Compile-time, type-safe; null-safe; no reflection overhead |
| Circuit breaker on HTTP calls | try/catch counters | Resilience4j (resilience4j-spring-boot4:2.4.0) | Proper half-open state, metrics integration |
| Kafka error handling / retry | Custom exception handlers | Spring Kafka DefaultErrorHandler + DeadLetterPublishingRecoverer | Handles deserialisation errors, backoff, DLT routing |
| Redis idempotency key | Custom Lua script | Spring Data Redis `setIfAbsent(key, value, Duration)` | Atomic check-and-set; handles TTL in one call |
| JSON serialisation | Custom serialiser | Jackson 3 (bundled with SB4) | BigDecimal serialisation, ISO-8601 dates, schema compatibility |
| WAL-based event propagation | Polling loop on outbox table | Debezium CDC | Polling adds latency and DB load; WAL is push-based and zero-lag |

**Key insight:** The outbox pattern exists precisely because direct Kafka publish inside a transaction is unreliable. Never build a polling mechanism to compensate — Debezium WAL reading is the correct and only endorsed approach for this project.

---

## Common Pitfalls

### Pitfall 1: PostgreSQL WAL Level Not Set
**What goes wrong:** Debezium fails with "replication slot not created" or "logical replication not enabled" immediately on first connector registration.
**Why it happens:** Default PostgreSQL `wal_level` is `replica`. Logical decoding requires `logical`. Setting it after the container starts requires a restart.
**How to avoid:** Pass `command: ["postgres", "-c", "wal_level=logical", "-c", "max_replication_slots=5"]` in compose.yml on the Postgres service. Verify with `SHOW wal_level;` before registering connectors.
**Warning signs:** `ERROR: logical replication not enabled` in Debezium Connect logs.

### Pitfall 2: Replication Slot Leak (Disk Fill)
**What goes wrong:** Debezium stops (container restart, `docker stop`) but `slot.drop.on.stop=false` (default). PostgreSQL cannot discard WAL segments past the stale slot — WAL accumulates and fills the disk, causing the entire PostgreSQL instance to halt.
**Why it happens:** Default Debezium behavior is to preserve the slot for resume. In dev, this causes more problems than it solves.
**How to avoid:** Set `slot.drop.on.stop=true` in all connector configs (D-15). Add `SELECT pg_drop_replication_slot('slot_name')` to your `make clean` target.
**Warning signs:** Disk usage growing after Debezium stop. `pg_replication_slots` shows stale slots with increasing `lag`.

### Pitfall 3: Flyway Not Running in Spring Boot 4
**What goes wrong:** Flyway is in the classpath but migrations never execute. The outbox table doesn't exist. Debezium fails on connector registration.
**Why it happens:** Spring Boot 4 changed Flyway auto-configuration. Using `flyway-core` alone (without the starter) disables automatic migration.
**How to avoid:** Use `spring-boot-starter-flyway` plus `flyway-database-postgresql` in pom.xml. Confirm with the `spring.flyway.enabled=true` property (default is true when starter is present).
**Warning signs:** Application starts without Flyway log output (`Migrating schema "public" to version...`).

### Pitfall 4: Spring State Machine / Spring Boot 4 Incompatibility
**What goes wrong:** `spring-statemachine-core` fails to start with Spring Framework 7 due to API changes. Application context fails to load.
**Why it happens:** Spring State Machine 4.0.1 targets Spring Framework 6.2.x. Spring Framework 7.0 (shipped with Spring Boot 4.0) includes breaking API changes.
**How to avoid:** Do NOT add `spring-statemachine-core` as a dependency when using Spring Boot 4. Use the hand-rolled `TransferState` enum FSM in the `common` module (see Pattern 2).
**Warning signs:** `NoSuchMethodError` or `ClassNotFoundException` for Spring State Machine classes at startup.

### Pitfall 5: Jackson 2 vs Jackson 3 in Spring Boot 4
**What goes wrong:** Third-party libraries that depend on `com.fasterxml.jackson.core:jackson-databind:2.x` conflict with the Jackson 3.x dependency shipped by Spring Boot 4.0.5.
**Why it happens:** Spring Boot 4.0 ships Jackson 3.0.x; Jackson 2 is bundled in deprecated form. Libraries with hard jackson2 dependencies may conflict.
**How to avoid:** Review all third-party library transitive dependencies for Jackson 2 usage. Exclude `jackson-databind` 2.x and force 3.x, or use `<dependencyManagement>` BOM.
**Warning signs:** `NoClassDefFoundError` on `com.fasterxml.jackson.databind.ObjectMapper` or mixed 2.x/3.x on classpath.

### Pitfall 6: Podman WSL Rootless Mode Incompatible with kind
**What goes wrong:** `KIND_EXPERIMENTAL_PROVIDER=podman kind create cluster` fails with networking errors or CNI plugin errors when Podman is in rootless mode.
**Why it happens:** kind on WSL requires rootful Podman. The default Podman machine on Windows WSL starts in rootless mode.
**How to avoid:** Before the Phase 1 kind networking spike, run:
```bash
podman machine stop
podman machine set --rootful
podman machine start
```
Then verify with `podman info | grep rootless` — must show `false`.
**Warning signs:** `ERROR: failed to create cluster` with permission errors or CNI errors in kind output.

### Pitfall 7: Debezium Connector Registered Before Flyway Migrations Complete
**What goes wrong:** The connector-init service starts before the Spring Boot service has run Flyway migrations. The outbox table doesn't exist. Debezium fails with a table-not-found error.
**Why it happens:** compose.yml `depends_on` with `service_healthy` checks Kafka Connect health, but not whether the target DB tables exist.
**How to avoid:** Add an explicit wait in the connector-init script that checks for the table before POSTing:
```bash
until psql -h account-db -U account -c '\d outbox' > /dev/null 2>&1; do
  echo "Waiting for outbox table..."; sleep 2
done
curl -X POST http://connect:8083/connectors -H 'Content-Type: application/json' -d @account-connector.json
```
**Warning signs:** `ERROR: relation "public.outbox" does not exist` in Debezium Connect logs.

### Pitfall 8: Deadlock from Inconsistent Account Lock Order
**What goes wrong:** Transfer A locks account 1 then account 2. Transfer B locks account 2 then account 1. Both transactions wait on each other — PostgreSQL detects the deadlock and aborts one.
**Why it happens:** Naive implementation locks accounts in the order they appear in the request.
**How to avoid:** Always acquire locks in ascending ID order (MIN_ID first, MAX_ID second). See Pattern 1 above.
**Warning signs:** `ERROR: deadlock detected` in PostgreSQL logs under concurrent load.

---

## Code Examples

### Outbox Table Schema (Flyway migration)

```sql
-- Source: [VERIFIED: debezium.io outbox event router documentation]
-- File: V2__create_outbox.sql
CREATE TABLE outbox (
    id             UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_id   UUID        NOT NULL,   -- transfer_id or payment_id
    aggregate_type VARCHAR(50) NOT NULL,   -- e.g. "account.transfer" → routes to banking.account.transfer topic
    event_type     VARCHAR(50) NOT NULL,   -- e.g. "TransferInitiated"
    payload        JSONB       NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Debezium reads WAL — no polling needed. Clean up old rows with a scheduled job.
CREATE INDEX outbox_created_at_idx ON outbox (created_at);
```

### Account Entity (monetary fields)

```java
// Source: [ASSUMED — standard JPA + BigDecimal pattern]
@Entity
@Table(name = "accounts")
public class Account {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 7)  // NNN-NNN format
    private String bsb;

    @Column(nullable = false, length = 10)
    private String accountNumber;

    @Column(nullable = false, precision = 15, scale = 4)
    private BigDecimal balance;

    @Enumerated(EnumType.STRING)
    private AccountStatus status;

    public void debit(BigDecimal amount) {
        if (amount.compareTo(BigDecimal.ZERO) <= 0) throw new IllegalArgumentException("Amount must be positive");
        if (this.balance.compareTo(amount) < 0) throw new InsufficientFundsException(this.id);
        this.balance = this.balance.subtract(amount);
    }

    public void credit(BigDecimal amount) {
        if (amount.compareTo(BigDecimal.ZERO) <= 0) throw new IllegalArgumentException("Amount must be positive");
        this.balance = this.balance.add(amount);
    }
}
```

### Spring Boot 4 application.yml (key properties)

```yaml
# Source: [VERIFIED: Spring Boot 4.0 release notes + WebSearch OTel auto-config]
spring:
  threads:
    virtual:
      enabled: true                  # Java 21 virtual threads
  datasource:
    url: jdbc:postgresql://account-db:5432/account
    username: account
    password: account
    driver-class-name: org.postgresql.Driver
  jpa:
    hibernate:
      ddl-auto: validate             # Flyway manages schema; JPA only validates
    properties:
      hibernate:
        dialect: org.hibernate.dialect.PostgreSQLDialect
  flyway:
    enabled: true
    locations: classpath:db/migration
  data:
    redis:
      host: redis
      port: 6379
  kafka:
    bootstrap-servers: kafka:9092
    consumer:
      group-id: account-service
      auto-offset-reset: earliest
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.apache.kafka.common.serialization.StringDeserializer

# OTel config — Phase 2 adds these; stubs here for reference
# management:
#   otlp:
#     tracing:
#       endpoint: http://otel-collector:4318/v1/traces
```

---

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|-----------------|--------------|--------|
| Kafka + ZooKeeper | Kafka 4.0 KRaft only (ZooKeeper removed) | Kafka 4.0 (March 2025) | Simpler compose.yml; must use bitnami/kafka:4.x or equivalent |
| Spring Boot 3.4.x | Spring Boot 4.0.x + Spring Framework 7 | November 2025 | Jackson 3, virtual threads first-class, new OTel starter |
| flyway-core standalone | spring-boot-starter-flyway required | Spring Boot 4.0 | Auto-migration requires starter; code change needed |
| resilience4j-spring-boot3 | resilience4j-spring-boot4 | Early 2026 | Dedicated artifact; use correct group ID |
| Spring State Machine 4.0.x | Hand-rolled enum FSM (SB4 only) | SB4 launch Nov 2025 | Spring State Machine does not support Spring Framework 7 |
| Debezium 3.0.x | Debezium 3.4.x | December 2025 | Exactly-once semantics for all core connectors in 3.3+; PostgreSQL 17 support in 3.0.1+ |

**Deprecated/outdated:**
- ZooKeeper with Kafka: removed in Kafka 4.0 — do not add any ZooKeeper service to compose.yml
- `flyway-core` as standalone dependency: triggers no auto-migration in Spring Boot 4; always use the starter
- `spring-statemachine-core` with Spring Boot 4: incompatible — use hand-rolled FSM

---

## Runtime State Inventory

This is a greenfield project — no existing runtime state.

| Category | Items Found | Action Required |
|----------|-------------|-----------------|
| Stored data | None — greenfield | N/A |
| Live service config | None — greenfield | N/A |
| OS-registered state | None — greenfield | N/A |
| Secrets/env vars | None — greenfield | N/A |
| Build artifacts | None — greenfield | N/A |

---

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| Podman | All containers | YES | 5.8.1 (WSL provider) | — |
| Java 21 | Spring Boot services | NOT DETECTED in bash PATH | — | Must install JDK 21 before Phase 1 build |
| Maven | Build system | NOT DETECTED in bash PATH | — | Must install Maven or use Maven Wrapper (mvnw) |
| kind | Networking spike (success criterion 5) | NOT INSTALLED | — | Install kind; set rootful Podman first |
| curl | Connector registration script | YES | 8.18.0 | — |
| PostgreSQL client (psql) | Connector-init wait script | NOT DETECTED | — | Use Docker/Podman image with psql included |
| Redis CLI | Idempotency verification | NOT DETECTED | — | Use redis:7.2 container exec |

**Missing dependencies with no fallback:**

- **Java 21 JDK:** Required to compile and run all Spring Boot services. The bash session shows no `java` in PATH. Must install JDK 21 on this machine before any build can run.
- **Maven:** Required for `mvn package` and multi-module builds. Not detected. Install Maven 3.9.x or use `./mvnw` (Maven Wrapper bundled into the project during setup).
- **kind:** Required for success criterion 5 (Podman + kind networking spike). Not installed. Install from https://kind.sigs.k8s.io/docs/user/quick-start/#installation, then run `podman machine set --rootful` before first use.

**Missing dependencies with fallback:**

- **psql client:** Connector-init wait script can use `pg_isready` or a loop with the psql container image rather than a host-installed client.

---

## Validation Architecture

### Test Framework

| Property | Value |
|----------|-------|
| Framework | JUnit 5 (bundled with Spring Boot 4.0.5 spring-boot-starter-test) |
| Config file | None — Spring Boot auto-configures test context |
| Quick run command | `./mvnw test -pl account-service -Dtest=AccountServiceTest` |
| Full suite command | `./mvnw test` (all modules) |

### Phase Requirements → Test Map

| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| CORE-01 | POST /accounts creates account with valid BSB | integration | `./mvnw test -pl account-service -Dtest=AccountApiIntegrationTest` | Wave 0 |
| CORE-01 | GET /accounts/{id}/balance returns balance | integration | same | Wave 0 |
| CORE-02 | Debit + credit + outbox in single TX | integration | `./mvnw test -pl account-service -Dtest=TransferServiceTest` | Wave 0 |
| CORE-02 | TX rollback does not produce outbox row | integration | same | Wave 0 |
| CORE-03 | POST /payments initiates payment | integration | `./mvnw test -pl payment-service -Dtest=PaymentApiIntegrationTest` | Wave 0 |
| TXNS-01 | Concurrent transfer to same account — no overdraft | integration | `./mvnw test -pl account-service -Dtest=ConcurrentTransferTest` | Wave 0 |
| TXNS-04 | State transitions: PENDING → PAYMENT_PROCESSING → CONFIRMED | unit | `./mvnw test -pl common -Dtest=TransferStateTest` | Wave 0 |
| TXNS-04 | Invalid transition throws IllegalStateTransitionException | unit | same | Wave 0 |
| TXNS-05 | Duplicate idempotency key returns cached response | integration | `./mvnw test -pl payment-service -Dtest=IdempotencyServiceTest` | Wave 0 |
| AUBN-01 | BSB NNN-NNN accepted | unit | `./mvnw test -pl common -Dtest=BsbValidatorTest` | Wave 0 |
| AUBN-01 | Malformed BSB rejected with 400 | unit | same | Wave 0 |
| AUBN-02 | Transfer >= 10000 produces AUSTRAC log entry | integration | `./mvnw test -pl account-service -Dtest=AustracAuditTest` | Wave 0 |
| TXNS-02 | Outbox row appears in Debezium CDC output | integration (testcontainers) | `./mvnw test -pl account-service -Dtest=DebeziumCdcTest` | Wave 0 |
| Success criterion 5 | kind cluster starts with Podman, DNS resolves | manual | `kind create cluster; kubectl run test --image=busybox ...` | Manual only |

### Sampling Rate

- **Per task commit:** `./mvnw test -pl {changed-module} -Dtest={relevant-test}`
- **Per wave merge:** `./mvnw test` (full suite, all modules)
- **Phase gate:** Full suite green before `/gsd-verify-work`

### Wave 0 Gaps (all test files must be created before implementation)

- [ ] `account-service/src/test/java/.../AccountApiIntegrationTest.java` — covers CORE-01
- [ ] `account-service/src/test/java/.../TransferServiceTest.java` — covers CORE-02, TXNS-01
- [ ] `account-service/src/test/java/.../ConcurrentTransferTest.java` — covers TXNS-01 (concurrent load)
- [ ] `account-service/src/test/java/.../AustracAuditTest.java` — covers AUBN-02
- [ ] `account-service/src/test/java/.../DebeziumCdcTest.java` — covers TXNS-02 (Testcontainers)
- [ ] `payment-service/src/test/java/.../PaymentApiIntegrationTest.java` — covers CORE-03
- [ ] `payment-service/src/test/java/.../IdempotencyServiceTest.java` — covers TXNS-05
- [ ] `common/src/test/java/.../BsbValidatorTest.java` — covers AUBN-01
- [ ] `common/src/test/java/.../TransferStateTest.java` — covers TXNS-04
- [ ] `ledger-service/src/test/java/.../LedgerConsumerTest.java` — covers CORE-04
- [ ] `notification-service/src/test/java/.../NotificationConsumerTest.java` — covers CORE-05
- [ ] `pom.xml` with Maven Wrapper — required for `./mvnw` commands to work before Java is installed on PATH
- [ ] Testcontainers dependencies in account-service pom.xml — required for DebeziumCdcTest

---

## Security Domain

### Applicable ASVS Categories (Phase 1 Scope)

| ASVS Category | Applies | Standard Control |
|---------------|---------|-----------------|
| V2 Authentication | No (Phase 3 adds Kong JWT) | N/A in Phase 1 |
| V3 Session Management | No | N/A in Phase 1 |
| V4 Access Control | No (Phase 3) | N/A in Phase 1 |
| V5 Input Validation | YES | Bean Validation (@Valid, @ValidBsb); BigDecimal range checks |
| V6 Cryptography | No monetary crypto in Phase 1 | N/A |
| V7 Error Handling | YES | Never expose stack traces in API responses; structured error bodies only |
| V14 Configuration | YES | No secrets in compose.yml in plain text; use env vars or .env file (not committed) |

### Known Threat Patterns for This Stack

| Pattern | STRIDE | Standard Mitigation |
|---------|--------|---------------------|
| Overdraft via concurrent requests | Tampering | SELECT FOR UPDATE (PESSIMISTIC_WRITE) — D-10 |
| Double-charge on network retry | Tampering | Redis idempotency key (setIfAbsent) — D-05 |
| Phantom event on DB rollback | Tampering | Outbox + Debezium WAL only (no dual-write) — D-12 |
| WAL disk fill from stale slot | Denial of Service | slot.drop.on.stop=true — D-15 |
| Malformed BSB creating invalid accounts | Tampering | ValidBsb constraint — AUBN-01 |
| Missing AUSTRAC audit for large transfers | Repudiation | Threshold check in ACID TX boundary — AUBN-02 |
| Secrets exposed in compose.yml | Information Disclosure | Use .env file; never commit DB passwords |

---

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | Hand-rolled enum FSM is sufficient for 6-state transfer lifecycle | Standard Stack / Pattern 2 | If Spring State Machine releases SB4 support before Phase 1 implementation starts, D-05 could be revisited — but the hand-rolled FSM still works |
| A2 | bitnami/kafka:4.x image tag is the correct image for Kafka 4.0 KRaft mode | Standard Stack / Pattern 6 | Bitnami may have renamed the tag; verify actual tag at hub.docker.com/r/bitnami/kafka before writing compose.yml |
| A3 | debezium/connect:3.4 image tag exists for Debezium 3.4.x | Standard Stack / Pattern 4 | Verify at hub.docker.com/r/debezium/connect — may be debezium/connect:3.4.0.Final or similar |
| A4 | Java 21 JDK is not installed on the developer machine | Environment Availability | If Java 21 is installed in a location not on the bash PATH used by this session, this is a false negative — verify with Windows `where java` |
| A5 | BSB format NNN-NNN is the correct Australian banking format | AUBN-01 code example | [ASSUMED] — verified as common knowledge but not cross-checked against current APCA/AusPayNet specification |
| A6 | AUSTRAC threshold for mandatory reporting is AUD $10,000 | AUBN-02 | [ASSUMED] — the project defines this as a learning system; real AUSTRAC thresholds and reporting obligations differ; this is a simulation only |

---

## Open Questions

1. **Spring State Machine / Spring Boot 4 compatibility — user must confirm FSM approach**
   - What we know: Spring State Machine 4.0.1 targets Spring Framework 6.2.x; no Spring Framework 7 release announced
   - What's unclear: Whether to document the hand-rolled FSM as overriding D-05, or to escalate to the user
   - Recommendation: Planner should note this conflict, use the hand-rolled FSM approach, and flag it for user awareness

2. **Java 21 and Maven installation on this machine**
   - What we know: Neither `java` nor `mvn` were found in the bash PATH during environment audit
   - What's unclear: Whether they are installed at Windows-native locations not exposed to the bash session
   - Recommendation: Wave 0 task must include a step to verify JDK 21 and Maven are available, or install them

3. **Testcontainers for integration tests (Debezium CDC)**
   - What we know: Testing the CDC pipeline end-to-end requires Postgres + Kafka + Debezium running in tests
   - What's unclear: Whether Testcontainers works correctly with Podman on WSL in this environment
   - Recommendation: Include a Testcontainers smoke test in Wave 0 to validate before writing full integration tests; use `TESTCONTAINERS_RYUK_DISABLED=true` for Podman compatibility

4. **Exact bitnami/kafka and debezium/connect image tags for Kafka 4.x and Debezium 3.4.x**
   - What we know: Both are available; exact patch tags need verification
   - Recommendation: Wave 0 task to run `podman pull bitnami/kafka:latest` and inspect the version, then pin the exact tag

---

## Sources

### Primary (HIGH confidence)
- [VERIFIED: github.com/spring-projects/spring-boot/releases] — Spring Boot 4.0.5 confirmed GA; current patch as of March 2026
- [VERIFIED: github.com/spring-projects/spring-statemachine/releases] — Spring State Machine 4.0.1 targets Spring Framework 6.2.x; no Spring Boot 4 support
- [VERIFIED: podman-desktop.io/docs/kind/configuring-podman-for-kind-on-windows] — kind requires rootful Podman on WSL Windows
- [VERIFIED: debezium.io blog/2025/12/16/debezium-3-4-final-released] — Debezium 3.4.0.Final is current stable
- [VERIFIED: github.com/flyway/flyway/issues/4165] — Flyway + Spring Boot 4 requires spring-boot-starter-flyway (not flyway-core)
- [VERIFIED: mvnrepository.com/artifact/io.github.resilience4j/resilience4j-spring-boot4/2.4.0] — resilience4j-spring-boot4:2.4.0 is the correct SB4 artifact

### Secondary (MEDIUM confidence)
- [CITED: docs.spring.io/spring-kafka/api — DeadLetterPublishingRecoverer] — DLT configuration pattern verified against API docs
- [CITED: debezium.io/documentation/reference/stable/transformations/outbox-event-router.html] — EventRouter SMT field names verified
- [CITED: softwaremill.com — Apache Kafka 4.0.0 released] — Kafka 4.0 confirmed KRaft-only, ZooKeeper removed March 2025
- [CITED: medium.com/@kinneko-de/kafka-4-kraft-docker-compose] — Kafka 4.0 bitnami/kafka compose.yml environment variables

### Tertiary (LOW confidence)
- [ASSUMED] BSB format NNN-NNN — training knowledge of Australian banking, not verified against current AusPayNet specification
- [ASSUMED] AUSTRAC $10,000 threshold — project-defined simulation value; not verified against current AUSTRAC reporting obligations

---

## Metadata

**Confidence breakdown:**

- Standard stack: HIGH — Spring Boot 4.0.5 GA verified; Kafka 4.0 verified; Debezium 3.4.x verified; Resilience4j SB4 artifact verified
- Spring State Machine incompatibility: HIGH — GitHub release page explicitly shows Spring Framework 6.2.x target; no SB4 release
- Flyway SB4 change: HIGH — GitHub issue #4165 confirmed and closed; starter required
- Architecture patterns: HIGH — well-established patterns (Outbox/CDC, Saga, SELECT FOR UPDATE) with HIGH confidence from prior research
- Podman + kind setup: HIGH — official Podman Desktop docs confirm rootful requirement for WSL
- Environment availability: MEDIUM — bash PATH may not reflect Windows-native tool installations

**Research date:** 2026-04-10
**Valid until:** 2026-05-10 (30 days — all Spring ecosystem versions; re-verify Spring State Machine SB4 support before implementation if >2 weeks pass)
