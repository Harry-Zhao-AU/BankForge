# Phase 1: Service Scaffold + Core Banking — Research

**Researched:** 2026-04-10
**Domain:** Java 21 + Spring Boot 4.0.5, PostgreSQL 17, Redis 7.2, Spring State Machine, Maven multi-module, Podman Compose, Australian banking validation
**Confidence:** HIGH (core stack verified via official sources); MEDIUM (Spring State Machine gap — see CRITICAL FLAG below)

---

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

- **D-01:** Target Spring Boot 4.0.x. Researcher must verify the exact current GA patch version before writing any pom.xml — do NOT hardcode 4.0.5. If 4.0.x is not yet GA or has known critical bugs, fall back to 3.4.x. All plan features (virtual threads, built-in OTel, structured ECS logging) are available on both versions.
- **D-02:** External clients call payment-service as the entry point for all fund transfers. payment-service owns idempotency key checking (Redis) and the transfer state machine.
- **D-03:** payment-service calls account-service internally (service-to-service REST) to execute the ACID transaction (debit + credit + outbox write in one PostgreSQL TX). account-service is internal-only — not exposed to external clients for transfer operations.
- **D-04:** payment-service owns CORE-03 (NPP-style payment flow API) and TXNS-04 (state machine) and TXNS-05 (idempotency). account-service owns CORE-01 (account CRUD, balance, history), CORE-02 (atomic transfer execution), and TXNS-01 (ACID TX guarantee).
- **D-05:** Implement using Spring State Machine library (org.springframework.statemachine). Do not hand-roll a custom FSM. **SEE CRITICAL FLAG — library is not compatible with Spring Boot 4. Research recommends a hand-rolled enum FSM with the same state names.**
- **D-06:** State names: PENDING → PAYMENT_PROCESSING → PAYMENT_DONE → POSTING → CONFIRMED; POSTING → COMPENSATING → CANCELLED on debit failure. POSTING = ledger-service recording double-entry entries.
- **D-07:** Maven multi-module project. One parent pom.xml at repo root with 5 child modules: common, account-service, payment-service, ledger-service, notification-service.
- **D-08:** common module holds: Kafka event DTOs, TransferState enum, shared validation utilities (BSB format). All 4 services declare a compile dependency on common.
- **D-09:** All monetary fields: BigDecimal in Java, DECIMAL(15,4) in PostgreSQL. No double or float anywhere.
- **D-10:** Balance queries use SELECT FOR UPDATE (LockModeType.PESSIMISTIC_WRITE) to prevent concurrent overdraft under Read Committed isolation.
- **D-11 (forward-compat):** PostgreSQL containers must start with wal_level=logical and max_replication_slots=5 — set now so Phase 1.1 Debezium needs no DB restart. No CDC consumer is wired in this phase.
- **D-12 (deferred to Phase 1.1):** Kafka KRaft, Debezium CDC, DLT topics, and AUSTRAC logging are Phase 1.1 scope. Do not implement in Phase 1 plans.

### Claude's Discretion

- Exact Kafka topic naming convention (e.g., `banking.transfers.outbox` vs `transfer-events`) — follow a consistent pattern.
- Compose service startup ordering (depends_on + health checks) — implement correctly for PostgreSQL + Redis ordering.
- Exact Spring State Machine configuration style — moot given the compatibility gap; use enum FSM pattern instead.

### Deferred Ideas (OUT OF SCOPE)

None — discussion stayed within Phase 1 scope. Kafka, Debezium, CDC, AUSTRAC are Phase 1.1.
</user_constraints>

---

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| CORE-01 | account-service REST API: create accounts, check balances, list transfer history | Spring Data JPA + Spring MVC REST — HIGH confidence verified patterns |
| CORE-02 | Atomic debit + credit + outbox write in single PostgreSQL transaction | SELECT FOR UPDATE + @Transactional + Flyway-migrated outbox table INSERT — verified pattern |
| CORE-03 | payment-service REST API for NPP-style payment flows | Standard Spring MVC REST; NPP flow is custom domain logic wrapping account-service calls |
| TXNS-01 | Debit + credit + outbox row committed in one local ACID transaction — no partial states | Single @Transactional boundary with LockModeType.PESSIMISTIC_WRITE on account rows |
| TXNS-04 | Transfer lifecycle through state machine transitions: PENDING → ... → CONFIRMED or CANCELLED | Enum-based FSM recommended (Spring State Machine incompatible with Spring Boot 4 — see Critical Flag) |
| TXNS-05 | Idempotency keys stored in Redis (TTL 24h) — duplicate requests return cached response | Spring Data Redis + Lettuce (bundled), setIfAbsent + getExpire pattern |
</phase_requirements>

---

## Summary

Spring Boot **4.0.5** is the current GA release (released 2026-03-26), built on Spring Framework 7.0.6. It is stable enough for a greenfield project — five patch versions have been released since the 4.0.0 GA in November 2025. All four Phase 1 services can be built on 4.0.5 with confidence.

The most significant finding is that **Spring State Machine (D-05) is incompatible with Spring Boot 4**. The library is in maintenance mode, the latest release (4.0.1, June 2024) targets Spring Boot 3.5.x / Spring Framework 6.2.x, and there is no roadmap for Spring Boot 4 support. The recommended resolution is a hand-rolled enum FSM with a JPA-persisted state column — a well-established pattern that requires fewer lines than Spring State Machine configuration and has zero dependency risk.

A second important finding is that Spring Boot 4.0.5 bundles **Flyway 11.14.1** (not 10.x as assumed in CLAUDE.md) and **Jackson 3** (not Jackson 2). Both have breaking changes from their prior major versions that affect how the project is configured. Specifically, Flyway 11 with Spring Boot 4 requires `spring-boot-starter-flyway` (not just `flyway-core`) plus `flyway-database-postgresql` at runtime. Jackson 3 renames core packages from `com.fasterxml.jackson` to `tools.jackson` but Spring Boot 4 can still use Jackson 2 via a config property if needed.

Podman 5.8.1 is already installed on this machine. The `podman-compose` `service_healthy` condition bug was resolved in May 2025, so health-check-based startup ordering is reliable.

**Primary recommendation:** Use Spring Boot 4.0.5 with an enum-based FSM, Flyway 11.14.1 (via starter), Jackson 3 natively, Resilience4j 2.4.0 (`resilience4j-spring-boot4`), and RestClient for service-to-service HTTP calls.

---

## CRITICAL FLAG: Spring State Machine Incompatibility

**Status:** Spring State Machine 4.0.1 (latest) targets Spring Boot 3.5.x / Spring Framework 6.2.x. It is in maintenance mode with no Spring Boot 4 support on the roadmap. [VERIFIED: github.com/spring-projects/spring-statemachine]

**Impact on D-05:** Decision D-05 ("use Spring State Machine") cannot be honoured with Spring Boot 4. The planner must resolve this before writing tasks.

**Recommended resolution (Claude's discretion):** Implement the FSM as a hand-rolled enum FSM with:
1. A `TransferState` enum in the `common` module with all 7 states.
2. A `TransferStateMachine` Spring `@Component` with an `onEvent(TransferEvent, Transfer)` method containing an explicit transition table.
3. Transfer state persisted as a `VARCHAR` column in `payment_service.transfers` — Flyway creates the column.
4. No external dependency. All transitions are compile-time verified (enum switch).

This pattern is described on DZone ("A Simple State Machine for Spring Boot Projects") and requires ~80 lines vs Spring State Machine's 200+ lines of configuration. It is not a simplification; it is the idiomatic choice when the FSM library does not support the framework version.

**Alternative (if D-05 is a hard requirement):** Downgrade to Spring Boot 3.4.x. Spring State Machine 4.0.1 is compatible with Spring Boot 3.5.x, which is one minor below 3.4.x — verify actual compatibility before committing.

---

## Standard Stack

### Core — Verified Versions

| Library | Version | Purpose | Source |
|---------|---------|---------|--------|
| Java | 21 LTS | Runtime, virtual threads | [VERIFIED: CLAUDE.md] |
| Spring Boot | **4.0.5** | Application framework | [VERIFIED: eosl.date/spring-boot, 2026-03-26 release] |
| Spring Framework | 7.0.6 | Core framework (bundled in SB 4.0.5) | [VERIFIED: docs.spring.io/spring-boot BOM] |
| Spring Data JPA | bundled | Repository layer, @Lock | [VERIFIED: docs.spring.io BOM] |
| Hibernate ORM | 7.2.7.Final | JPA provider (bundled in SB 4.0.5) | [VERIFIED: docs.spring.io BOM] |
| Spring Data Redis | bundled | Redis client abstraction | [VERIFIED: docs.spring.io BOM] |
| Lettuce | 6.8.2.RELEASE | Redis driver (bundled, default) | [VERIFIED: docs.spring.io BOM] |
| Jackson | 3.x (bundled) | JSON — new package `tools.jackson` | [VERIFIED: spring.io/blog/2025/10/07/introducing-jackson-3-support-in-spring] |
| Flyway | 11.14.1 (bundled in BOM) | Schema migrations | [VERIFIED: docs.spring.io/spring-boot BOM] |
| flyway-database-postgresql | runtime (matches BOM) | PostgreSQL dialect for Flyway 10+ | [VERIFIED: github.com/flyway/flyway/issues/4165, resolved 2026-01-09] |
| MapStruct | **1.6.3** | DTO mapping (NOT in BOM — pin explicitly) | [VERIFIED: mapstruct.org/documentation/stable] |
| Lombok | 1.18.44 (in BOM) | Boilerplate reduction | [VERIFIED: docs.spring.io BOM] |
| Resilience4j | **2.4.0** | Circuit breaker for service-to-service | [VERIFIED: mvnrepository.com/artifact/io.github.resilience4j/resilience4j-spring-boot4/2.4.0] |
| PostgreSQL JDBC | bundled | DB driver | [VERIFIED: docs.spring.io BOM] |

### Infrastructure — Compose Images

| Image | Tag | Purpose |
|-------|-----|---------|
| `postgres` | `17-alpine` | Accounts DB, Payments DB, Ledger DB, Notifications DB |
| `redis` | `7.2-alpine` | Idempotency key store |

**Podman available on this machine:** 5.8.1 [VERIFIED: bash probe]
**podman-compose:** Not installed — must be installed (`pip install podman-compose`) [VERIFIED: bash probe]

### Alternatives Considered

| Instead of | Could Use | Why Not |
|------------|-----------|---------|
| Spring State Machine | Hand-rolled enum FSM | Library incompatible with Spring Boot 4 (maintains Spring Boot 3.5.x only) |
| Spring State Machine | Downgrade to SB 3.4.x | Loses Spring Framework 7 features; adds technical debt |
| RestClient | RestTemplate | RestTemplate enters deprecation in Spring Framework 7.1; RestClient is the SB4 standard |
| Flyway 11 (BOM) | Override to Flyway 10 | No reason to downgrade; Flyway 11 works correctly with Spring Boot 4 starter |
| Jackson 3 | Jackson 2 | SB4 defaults to Jackson 3; override via `spring.http.converters.preferred-json-mapper=jackson2` only if serialisation breaks |

### Installation

```bash
# Parent pom.xml — Spring Boot as parent
# <parent>
#   <groupId>org.springframework.boot</groupId>
#   <artifactId>spring-boot-starter-parent</artifactId>
#   <version>4.0.5</version>
# </parent>

# Core deps for each service module
mvn dependency:resolve   # verify after pom.xml creation

# podman-compose (Windows — run in WSL or via pip in PATH)
pip install podman-compose
```

---

## Architecture Patterns

### Maven Multi-Module Structure

```
bankforge/                         # git root
├── pom.xml                        # parent — packaging=pom, manages versions
├── common/
│   ├── pom.xml                    # packaging=jar, NO spring-boot-maven-plugin
│   └── src/main/java/
│       └── au/com/bankforge/common/
│           ├── dto/               # TransferCreatedEvent, AccountCreatedEvent (Kafka DTOs for Phase 1.1)
│           ├── enums/             # TransferState, TransferEvent
│           └── validation/        # BsbValidator, @ValidBsb annotation
├── account-service/
│   ├── pom.xml                    # spring-boot-maven-plugin present — fat JAR
│   └── src/
├── payment-service/
│   ├── pom.xml                    # spring-boot-maven-plugin present — fat JAR
│   └── src/
├── ledger-service/
│   ├── pom.xml                    # spring-boot-maven-plugin present — fat JAR
│   └── src/
├── notification-service/
│   ├── pom.xml                    # spring-boot-maven-plugin present — fat JAR
│   └── src/
├── compose.yml                    # Podman Compose — all services + infra
└── infrastructure/
    └── postgres/
        └── init/                  # Shared init scripts (schema separation via Spring schemas)
```

**Key rule:** The `common` module MUST NOT have `spring-boot-maven-plugin`. If it does, Maven will try to create an executable jar with a `Main-Class` manifest entry — it has none. The plugin is only on the 4 service modules that have a `@SpringBootApplication` entry point. [ASSUMED — standard Maven multi-module pattern, widely documented]

### Pattern 1: ACID Transfer Transaction

**What:** account-service executes debit + credit + outbox INSERT inside a single `@Transactional` method. Both account rows are locked with `LockModeType.PESSIMISTIC_WRITE` before any balance check.

**When to use:** All money movements. No exceptions.

**Lock ordering to prevent deadlocks:** Always lock the lower account ID first, then the higher. This prevents two concurrent transfers between the same pair deadlocking each other.

```java
// Source: verified JPA pessimistic locking pattern (Baeldung + JPA spec)
@Repository
public interface AccountRepository extends JpaRepository<Account, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Account a WHERE a.id = :id")
    Optional<Account> findByIdForUpdate(@Param("id") UUID id);
}

// Service layer — always lock lower UUID first
@Transactional
public void executeTransfer(UUID fromId, UUID toId, BigDecimal amount) {
    UUID first  = fromId.compareTo(toId) < 0 ? fromId : toId;
    UUID second = fromId.compareTo(toId) < 0 ? toId   : fromId;
    Account lockFirst  = accountRepo.findByIdForUpdate(first).orElseThrow();
    Account lockSecond = accountRepo.findByIdForUpdate(second).orElseThrow();
    Account debit  = lockFirst.getId().equals(fromId) ? lockFirst : lockSecond;
    Account credit = lockFirst.getId().equals(toId)   ? lockFirst : lockSecond;
    if (debit.getBalance().compareTo(amount) < 0) throw new InsufficientFundsException();
    debit.setBalance(debit.getBalance().subtract(amount));
    credit.setBalance(credit.getBalance().add(amount));
    outboxRepo.save(buildOutboxEntry(fromId, toId, amount));  // same TX
}
```

### Pattern 2: Enum-Based Transfer State Machine

**What:** `TransferState` enum in `common` module. `TransferStateMachine` component in `payment-service` holds an explicit transition table and throws `InvalidStateTransitionException` for illegal transitions.

**Why:** Spring State Machine is incompatible with Spring Boot 4. The enum pattern achieves identical semantics with no external dependency and full compile-time state exhaustiveness checking.

```java
// Source: standard enum FSM pattern — verified against DZone "A Simple State Machine for Spring Boot Projects"
// In common module:
public enum TransferState {
    PENDING, PAYMENT_PROCESSING, PAYMENT_DONE, POSTING, CONFIRMED, COMPENSATING, CANCELLED
}

public enum TransferEvent {
    INITIATE, PROCESS, PAYMENT_COMPLETE, POST, CONFIRM, FAIL, COMPENSATE
}

// In payment-service:
@Component
public class TransferStateMachine {
    private static final Map<TransferState, Map<TransferEvent, TransferState>> TRANSITIONS =
        Map.of(
            PENDING,             Map.of(INITIATE, PAYMENT_PROCESSING),
            PAYMENT_PROCESSING,  Map.of(PAYMENT_COMPLETE, PAYMENT_DONE, FAIL, COMPENSATING),
            PAYMENT_DONE,        Map.of(POST, POSTING),
            POSTING,             Map.of(CONFIRM, CONFIRMED, FAIL, COMPENSATING),
            COMPENSATING,        Map.of(COMPENSATE, CANCELLED)
        );

    public TransferState transition(TransferState current, TransferEvent event) {
        TransferState next = TRANSITIONS.getOrDefault(current, Map.of()).get(event);
        if (next == null) throw new InvalidStateTransitionException(current, event);
        return next;
    }
}
```

State is persisted in `payment_service.transfers.state VARCHAR(32)` column. Every call to `transition()` is followed by a `transferRepo.save(transfer)` within a `@Transactional` boundary.

### Pattern 3: Redis Idempotency Key

**What:** payment-service checks a Redis key `idempotency:{idempotency-key}` before processing. On first request: set key with 24h TTL and process. On duplicate: return cached response without processing.

```java
// Source: verified Spring Data Redis + Lettuce pattern
@Service
public class IdempotencyService {
    private final StringRedisTemplate redisTemplate;
    private static final Duration TTL = Duration.ofHours(24);

    public Optional<String> getCached(String key) {
        return Optional.ofNullable(
            redisTemplate.opsForValue().get("idempotency:" + key));
    }

    // Returns true if key was newly set (first request), false if already exists
    public boolean setIfNew(String key, String responseJson) {
        return Boolean.TRUE.equals(
            redisTemplate.opsForValue().setIfAbsent(
                "idempotency:" + key, responseJson, TTL));
    }
}
```

`StringRedisTemplate` is auto-configured by Spring Boot when `spring-boot-starter-data-redis` is on the classpath. Lettuce is the default driver — do not add Jedis dependency. [VERIFIED: Spring Boot docs, Lettuce bundled in SB4 BOM]

### Pattern 4: BSB Validation Custom Constraint

**What:** Jakarta Bean Validation custom `@ValidBsb` annotation in `common` module that validates `NNN-NNN` format (exactly 3 digits, hyphen, 3 digits).

```java
// Source: verified Jakarta Bean Validation custom constraint pattern
// In common/src/main/java/au/com/bankforge/common/validation/

@Constraint(validatedBy = BsbValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidBsb {
    String message() default "Invalid BSB format. Must be NNN-NNN";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}

public class BsbValidator implements ConstraintValidator<ValidBsb, String> {
    private static final Pattern BSB_PATTERN = Pattern.compile("^\\d{3}-\\d{3}$");

    @Override
    public boolean isValid(String value, ConstraintValidatorContext ctx) {
        return value != null && BSB_PATTERN.matcher(value).matches();
    }
}
```

Apply with `@ValidBsb` on `CreateAccountRequest.bsb` and `TransferRequest.fromBsb` / `TransferRequest.toBsb`.

### Pattern 5: Outbox Table Schema (Debezium-Compatible)

**What:** Flyway migration creates the outbox table with the exact columns Debezium's `EventRouter` SMT expects. Phase 1 only writes to this table; Phase 1.1 wires Debezium.

```sql
-- V1__create_outbox_table.sql  (account-service schema)
-- Source: Debezium official EventRouter documentation (debezium.io/documentation/reference/stable/transformations/outbox-event-router.html)
CREATE TABLE outbox_event (
    id            UUID        NOT NULL DEFAULT gen_random_uuid(),
    aggregatetype VARCHAR(255) NOT NULL,   -- e.g., 'Transfer'
    aggregateid   VARCHAR(255) NOT NULL,   -- e.g., transfer UUID (becomes Kafka message key)
    type          VARCHAR(255) NOT NULL,   -- e.g., 'TransferInitiated'
    payload       JSONB       NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (id)
);
-- No index needed in Phase 1; Debezium reads WAL, not the table directly.
-- Do NOT add a DELETE trigger — Debezium consumes from WAL before rows are cleaned.
```

**Important:** Debezium's EventRouter routes based on `aggregatetype` → Kafka topic name. `aggregateid` becomes the Kafka message key (guarantees ordering per transfer). [VERIFIED: debezium.io documentation]

### Pattern 6: PostgreSQL WAL Configuration in Compose

**What:** Each PostgreSQL container must start with `wal_level=logical` and `max_replication_slots=5`. These are server-start parameters — they cannot be changed at runtime.

```yaml
# Source: standard PostgreSQL Docker/Podman Compose pattern
account-db:
  image: postgres:17-alpine
  environment:
    POSTGRES_DB: accountdb
    POSTGRES_USER: account
    POSTGRES_PASSWORD: secret
  command:
    - "postgres"
    - "-c"
    - "wal_level=logical"
    - "-c"
    - "max_replication_slots=5"
    - "-c"
    - "max_wal_senders=5"
  healthcheck:
    test: ["CMD-SHELL", "pg_isready -U account -d accountdb"]
    interval: 5s
    timeout: 5s
    retries: 10
```

### Pattern 7: service-to-service HTTP — RestClient

**What:** payment-service calls account-service using Spring Framework 7's `RestClient`. Do NOT use `RestTemplate` — it is deprecated in Spring Framework 7.1 and removed in 8.0.

```java
// Source: docs.spring.io/spring-framework/reference/integration/rest-clients.html
@Configuration
public class RestClientConfig {
    @Bean
    public RestClient accountServiceClient(
            @Value("${services.account.base-url}") String baseUrl) {
        return RestClient.builder()
            .baseUrl(baseUrl)
            .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .build();
    }
}
```

### Pattern 8: MapStruct + Lombok Annotation Processor Ordering

**Critical:** When both MapStruct and Lombok are in `annotationProcessorPaths`, Lombok MUST come first. Add `lombok-mapstruct-binding` to ensure ordering is enforced by the Maven compiler plugin. [VERIFIED: mapstruct.org/faq, bootify.io]

```xml
<!-- In parent pom.xml maven-compiler-plugin configuration -->
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-compiler-plugin</artifactId>
    <configuration>
        <source>21</source>
        <target>21</target>
        <annotationProcessorPaths>
            <path>
                <groupId>org.projectlombok</groupId>
                <artifactId>lombok</artifactId>
                <version>${lombok.version}</version>
            </path>
            <path>
                <groupId>org.projectlombok</groupId>
                <artifactId>lombok-mapstruct-binding</artifactId>
                <version>0.2.0</version>
            </path>
            <path>
                <groupId>org.mapstruct</groupId>
                <artifactId>mapstruct-processor</artifactId>
                <version>${mapstruct.version}</version>
            </path>
        </annotationProcessorPaths>
    </configuration>
</plugin>
```

### Anti-Patterns to Avoid

- **Using `double` or `float` for monetary values:** IEEE 754 floating-point cannot represent AUD cents precisely. `BigDecimal` only. PostgreSQL `DECIMAL(15,4)` only.
- **Using `RestTemplate` for new code:** Deprecated in SB4 lineage. Use `RestClient`.
- **Adding `spring-boot-maven-plugin` to `common` module:** Breaks fat JAR build — the plugin looks for a `Main-Class` that doesn't exist.
- **Locking accounts in arbitrary order:** Always lock lower UUID first to prevent deadlock between concurrent transfers involving the same account pair.
- **Using `flyway-core` alone (without starter):** Flyway will silently NOT run migrations in Spring Boot 4. Must use `spring-boot-starter-flyway`.
- **Relying on `POSTGRES_INITDB_ARGS` for WAL configuration:** `wal_level` cannot be set via init args — must be via `command:` args passed to the postgres process.
- **Spring State Machine on Spring Boot 4:** Will fail at startup — incompatible Spring Framework version. Use enum FSM.

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| DB schema versioning | Custom SQL runner | Flyway 11 (via `spring-boot-starter-flyway`) | Checksum validation, repeatable migrations, version tracking |
| DTO ↔ Entity conversion | Manual setters | MapStruct 1.6.3 | Compile-time generated, no reflection, null-safe |
| JSON serialisation | Custom serialiser | Jackson 3 (bundled) | Battle-tested, Spring auto-configuration |
| Redis TTL management | Custom expiry loop | Lettuce `setIfAbsent(key, value, ttl)` | Atomic SET NX EX in single Redis command |
| Circuit breaker | Try/catch retry loop | Resilience4j 2.4.0 (`resilience4j-spring-boot4`) | Configurable thresholds, metrics integration, @CircuitBreaker annotation |
| BSB regex validation | Inline string checks | Jakarta Bean Validation `@ValidBsb` in `common` | Reusable across all services, integrates with Spring MVC error responses |

**Key insight:** Jackson 3 breaking changes (package rename `com.fasterxml.jackson` → `tools.jackson`) affect any code importing Jackson classes directly. Prefer Spring's auto-configured `ObjectMapper` bean — it handles this transparently.

---

## Common Pitfalls

### Pitfall 1: Flyway Not Running in Spring Boot 4

**What goes wrong:** Application starts, schema is never created, `account` table does not exist. No error is logged.
**Why it happens:** Spring Boot 4 no longer auto-configures Flyway from `flyway-core` alone.
**How to avoid:** Use `spring-boot-starter-flyway` plus `flyway-database-postgresql` (runtime scope).
**Warning signs:** Application starts but any DB query throws `relation "account" does not exist`.

### Pitfall 2: Spring State Machine Startup Failure

**What goes wrong:** Application fails to start with `NoSuchBeanDefinitionException` or class incompatibility errors from Spring State Machine trying to use Spring Framework 6.x APIs on a 7.x container.
**Why it happens:** Spring State Machine 4.0.1 was compiled against Spring Framework 6.2.x. Spring Boot 4 ships Framework 7.x.
**How to avoid:** Do not include `spring-statemachine-core` or `spring-statemachine-starter` in any pom.xml. Use the enum FSM pattern.
**Warning signs:** Any `spring-statemachine` artifact on the classpath.

### Pitfall 3: Concurrent Overdraft (Missing PESSIMISTIC_WRITE)

**What goes wrong:** Two concurrent transfers from the same account both read the balance before either debits — both see sufficient funds, both commit, final balance goes negative.
**Why it happens:** PostgreSQL Read Committed isolation does not prevent this — a second transaction can read the pre-debit balance until the first transaction commits.
**How to avoid:** `@Lock(LockModeType.PESSIMISTIC_WRITE)` on all balance-reading queries inside `@Transactional` methods.
**Warning signs:** Negative balances in `account` table after concurrent load testing.

### Pitfall 4: Deadlock on Concurrent Cross-Transfers

**What goes wrong:** Transfer A→B and Transfer B→A run concurrently. A locks account A then waits for B; B locks account B then waits for A. PostgreSQL detects deadlock and kills one transaction.
**Why it happens:** Arbitrary lock ordering creates circular wait.
**How to avoid:** Always lock accounts in ascending UUID order. Lock `min(fromId, toId)` first, then the other.
**Warning signs:** `PSQLException: ERROR: deadlock detected` in logs.

### Pitfall 5: wal_level Not Set — Debezium Fails in Phase 1.1

**What goes wrong:** Phase 1.1 Debezium connector fails to create replication slot. Error: `replication slot requires wal_level >= logical`.
**Why it happens:** Default PostgreSQL `wal_level=replica` — not sufficient for logical decoding.
**How to avoid:** Set `wal_level=logical` in compose.yml `command:` for every PostgreSQL service. Requires container restart if changed after first start.
**Warning signs:** Debezium logs `ERROR: logical replication not enabled` in Phase 1.1.

### Pitfall 6: Jackson 3 Package Breaks Custom Serialisers

**What goes wrong:** Any class that directly imports `com.fasterxml.jackson.*` fails to compile or throws `ClassNotFoundException` at runtime.
**Why it happens:** Jackson 3 changed its group ID and package from `com.fasterxml.jackson` to `tools.jackson`.
**How to avoid:** Do not import Jackson classes directly in application code — use Spring's `ObjectMapper` bean. If direct import is needed, check that dependencies are `tools.jackson:*` not `com.fasterxml.jackson:*`.
**Warning signs:** `com.fasterxml.jackson.databind.ObjectMapper cannot be cast to tools.jackson.databind.ObjectMapper`.

### Pitfall 7: podman-compose Not Installed

**What goes wrong:** `podman-compose up` fails with `command not found`.
**Why it happens:** `podman-compose` is a separate Python package from Podman itself. Podman 5.8.1 is installed but compose is not.
**How to avoid:** `pip install podman-compose` before attempting to start compose stack.
**Warning signs:** `bash: podman-compose: command not found` when running `podman-compose up -d`.

### Pitfall 8: Hibernate 7 JPA Strict Mode

**What goes wrong:** JPQL queries that worked in Hibernate 6 fail in Hibernate 7 with compliance errors, especially queries with non-fully-qualified entity names or constructor expressions.
**Why it happens:** Hibernate 7 enables stricter JPA spec compliance by default.
**How to avoid:** If issues arise, configure `spring.jpa.properties.hibernate.jpa.compliance=false` in `application.yml` as a temporary measure while fixing queries to be spec-compliant.
**Warning signs:** `org.hibernate.query.SemanticException` or `QueryException` at startup or query time.

---

## Code Examples

### Parent pom.xml skeleton

```xml
<!-- Source: official Spring Boot multi-module guide + verified SB4 migration guide -->
<project>
    <modelVersion>4.0.0</modelVersion>
    <groupId>au.com.bankforge</groupId>
    <artifactId>bankforge-parent</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <packaging>pom</packaging>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>4.0.5</version>
        <relativePath/>
    </parent>

    <modules>
        <module>common</module>
        <module>account-service</module>
        <module>payment-service</module>
        <module>ledger-service</module>
        <module>notification-service</module>
    </modules>

    <properties>
        <java.version>21</java.version>
        <mapstruct.version>1.6.3</mapstruct.version>
        <!-- lombok.version is in Spring Boot BOM (1.18.44) -->
    </properties>

    <dependencyManagement>
        <dependencies>
            <!-- Internal modules -->
            <dependency>
                <groupId>au.com.bankforge</groupId>
                <artifactId>common</artifactId>
                <version>${project.version}</version>
            </dependency>
            <!-- MapStruct (not in SB BOM — pin here) -->
            <dependency>
                <groupId>org.mapstruct</groupId>
                <artifactId>mapstruct</artifactId>
                <version>${mapstruct.version}</version>
            </dependency>
            <!-- Resilience4j for Spring Boot 4 (not in SB BOM) -->
            <dependency>
                <groupId>io.github.resilience4j</groupId>
                <artifactId>resilience4j-spring-boot4</artifactId>
                <version>2.4.0</version>
            </dependency>
        </dependencies>
    </dependencyManagement>

    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <configuration>
                    <source>21</source>
                    <target>21</target>
                    <annotationProcessorPaths>
                        <path>
                            <groupId>org.projectlombok</groupId>
                            <artifactId>lombok</artifactId>
                            <version>${lombok.version}</version>
                        </path>
                        <path>
                            <groupId>org.projectlombok</groupId>
                            <artifactId>lombok-mapstruct-binding</artifactId>
                            <version>0.2.0</version>
                        </path>
                        <path>
                            <groupId>org.mapstruct</groupId>
                            <artifactId>mapstruct-processor</artifactId>
                            <version>${mapstruct.version}</version>
                        </path>
                    </annotationProcessorPaths>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

### Service module pom.xml (account-service example)

```xml
<!-- Source: [ASSUMED] standard SB4 multi-module pattern -->
<project>
    <parent>
        <groupId>au.com.bankforge</groupId>
        <artifactId>bankforge-parent</artifactId>
        <version>0.1.0-SNAPSHOT</version>
    </parent>
    <artifactId>account-service</artifactId>

    <dependencies>
        <dependency>
            <groupId>au.com.bankforge</groupId>
            <artifactId>common</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-jpa</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-flyway</artifactId>
        </dependency>
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-database-postgresql</artifactId>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>org.postgresql</groupId>
            <artifactId>postgresql</artifactId>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>org.mapstruct</groupId>
            <artifactId>mapstruct</artifactId>
        </dependency>
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <optional>true</optional>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
                <configuration>
                    <excludes>
                        <exclude>
                            <groupId>org.projectlombok</groupId>
                            <artifactId>lombok</artifactId>
                        </exclude>
                    </excludes>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

### application.yml (account-service)

```yaml
# Source: [VERIFIED] Spring Boot 4 configuration properties + virtual threads (bell-sw.com)
spring:
  application:
    name: account-service
  threads:
    virtual:
      enabled: true         # Java 21 virtual threads — replaces Tomcat thread pool
  datasource:
    url: jdbc:postgresql://account-db:5432/accountdb
    username: account
    password: secret
    driver-class-name: org.postgresql.Driver
  jpa:
    hibernate:
      ddl-auto: validate    # Flyway owns schema — Hibernate only validates
    show-sql: false
    properties:
      hibernate:
        dialect: org.hibernate.dialect.PostgreSQLDialect
  flyway:
    enabled: true
    locations: classpath:db/migration
    baseline-on-migrate: false
```

### Compose health check pattern

```yaml
# Source: [VERIFIED] resolved podman-compose service_healthy (github issue #1183, May 2025)
services:
  account-db:
    image: postgres:17-alpine
    environment:
      POSTGRES_DB: accountdb
      POSTGRES_USER: account
      POSTGRES_PASSWORD: secret
    command:
      - "postgres"
      - "-c"
      - "wal_level=logical"
      - "-c"
      - "max_replication_slots=5"
      - "-c"
      - "max_wal_senders=5"
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U account -d accountdb"]
      interval: 5s
      timeout: 5s
      retries: 10

  redis:
    image: redis:7.2-alpine
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 5s
      timeout: 3s
      retries: 5

  account-service:
    build:
      context: ./account-service
    depends_on:
      account-db:
        condition: service_healthy
    environment:
      SPRING_DATASOURCE_URL: jdbc:postgresql://account-db:5432/accountdb

  payment-service:
    build:
      context: ./payment-service
    depends_on:
      payment-db:
        condition: service_healthy
      redis:
        condition: service_healthy
```

---

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| RestTemplate | RestClient (synchronous) or WebClient (reactive) | SB4 / SF7 | RestTemplate deprecated in SF7.1, removed SF8. New code must use RestClient. |
| Jackson 2 (`com.fasterxml.jackson`) | Jackson 3 (`tools.jackson`) | SB4.0.0 (Nov 2025) | Package renames and class renames require migration. SB4 can still run Jackson 2 via config property. |
| Flyway auto-config from `flyway-core` | Requires `spring-boot-starter-flyway` | SB4.0.0 | Silent failure if wrong dependency used. |
| Spring State Machine (Spring project) | Enum FSM (hand-rolled) or wait for compatibility | SSM 4.0.1 only supports SB 3.5.x | SSM is in maintenance mode — no SB4 support. |
| Hibernate 6 / JPA 3.1 | Hibernate 7 / Jakarta Persistence 3.2 | SB4.0.0 | Stricter JPA compliance — some JPQL queries need updating. |
| ZooKeeper-based Kafka | KRaft-mode Kafka | Kafka 3.3+ (production), removed 3.8+ | Eliminates ZooKeeper dependency. Phase 1.1 concern. |
| `javax.*` packages | `jakarta.*` packages | Completed in SB3 era | Completed. SB4 is fully `jakarta.*`. |

**Deprecated/outdated in this phase:**
- `spring-statemachine-core`: Maintenance mode, incompatible with Spring Boot 4.
- `RestTemplate`: Deprecated path starts with SB4 / SF7.
- `flyway-core` without starter: Silent migration failure in SB4.

---

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | `common` module without `spring-boot-maven-plugin` builds as a plain JAR that can be embedded in service fat JARs | Architecture Patterns | Build fails or common classes missing at runtime — verified by running `mvn package` on first sprint |
| A2 | `podman-compose` `service_healthy` condition works reliably with Podman 5.8.1 | Pattern 6 | Services start before DB is ready, causing startup failures — mitigation: add retry logic in Spring datasource config |
| A3 | Hibernate 7 strict mode does not break standard `@Lock(PESSIMISTIC_WRITE)` repository queries | Pattern 1 / Pitfall 8 | Lock queries fail at startup — fix: add `hibernate.jpa.compliance=false` property |
| A4 | `lombok-mapstruct-binding:0.2.0` is the current stable version compatible with Lombok 1.18.44 | Pattern 8 | Annotation processing fails silently or generates incomplete mappers — verify with `mvn compile -X` |
| A5 | All 4 services can share a single `compose.yml` with isolated PostgreSQL containers (one per service) without port conflicts if each DB is on a different host port | Architecture Patterns | Port conflict prevents startup — assign distinct host ports (5432, 5433, 5434, 5435) for each DB container |

**If this table is empty:** All claims in this research were verified or cited — no user confirmation needed.

---

## Open Questions

1. **D-05 resolution: Spring State Machine vs enum FSM**
   - What we know: Spring State Machine is incompatible with Spring Boot 4. D-05 says to use Spring State Machine.
   - What's unclear: Whether the user prefers to downgrade to Spring Boot 3.4.x to enable Spring State Machine, or accept the enum FSM alternative.
   - Recommendation: Proceed with enum FSM on Spring Boot 4.0.5. Raise with user in wave 0 task description. The enum FSM is not a simplification — it is the appropriate tool for a 7-state machine with no Spring State Machine support.

2. **Jackson 3 migration scope**
   - What we know: Spring Boot 4 defaults to Jackson 3. The project has no existing code, so no migration is needed. Any newly written code that imports Jackson directly should use `tools.jackson.*`.
   - What's unclear: Whether any third-party dependencies (Resilience4j, Spring Kafka) bring Jackson 2 transitively and cause classpath conflicts.
   - Recommendation: Start with Jackson 3 (the SB4 default). If classpath issues arise, configure `spring.http.converters.preferred-json-mapper=jackson2` as a fallback and flag for Phase 1.1 resolution.

3. **podman-compose availability**
   - What we know: `podman-compose` is not installed on the machine. Podman 5.8.1 is.
   - What's unclear: Whether `pip install podman-compose` is in scope for Wave 0 or assumed pre-installed.
   - Recommendation: Wave 0 task must include `pip install podman-compose` (or `pipx install podman-compose` for isolated install). Document in project README.

---

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| Podman | compose.yml execution | Yes | 5.8.1 | — |
| podman-compose | `podman-compose up` | No | — | Install: `pip install podman-compose` |
| Java 21 | Service compilation | No (not in PATH) | — | Install JDK 21 LTS (Temurin/Eclipse) |
| Maven | Multi-module build | No (not in PATH) | — | Install Maven 3.9.x |
| Git | Version control | Unknown | — | Install if needed |

**Missing dependencies with no fallback:**
- Java 21 JDK — required before any `mvn` command. Install from adoptium.net.
- Maven 3.9.x — required for multi-module build. Install from maven.apache.org.

**Missing dependencies with install path:**
- `podman-compose` — `pip install podman-compose` or `pipx install podman-compose`. Required for `compose.yml` testing.

---

## Validation Architecture

The phase has four success criteria. Each maps to a specific test mechanism:

### Test Framework

| Property | Value |
|----------|-------|
| Framework | Spring Boot Test + JUnit 5 + Testcontainers |
| Config file | `src/test/resources/application-test.yml` per service |
| Quick run command | `mvn test -pl account-service -Dtest=AccountControllerTest` |
| Full suite command | `mvn verify` (runs all modules) |

**Note:** Testcontainers requires a running container runtime (Podman). Configure `TESTCONTAINERS_RYUK_DISABLED=true` and `DOCKER_HOST=unix:///run/user/1000/podman/podman.sock` (or equivalent Windows named pipe) for Podman compatibility.

### Success Criterion → Test Map

| Criterion | Behaviour | Test Type | Automated Command |
|-----------|-----------|-----------|-------------------|
| SC-1 | POST to account-service creates account with valid BSB, rejects invalid BSB | Integration (Testcontainers) | `mvn test -pl account-service -Dtest=AccountCreationIT` |
| SC-2 | Transfer commits debit + credit + outbox row in single TX | Integration (Testcontainers) | `mvn test -pl account-service -Dtest=AtomicTransferIT` |
| SC-3 | Duplicate idempotency key returns cached response, no second debit | Integration (Testcontainers + Redis) | `mvn test -pl payment-service -Dtest=IdempotencyIT` |
| SC-4 | State machine transitions: PENDING→...→CONFIRMED and →CANCELLED | Unit test (no container) | `mvn test -pl payment-service -Dtest=TransferStateMachineTest` |

### Sampling Rate

- **Per task commit:** Run the unit test for the component just implemented (`mvn test -Dtest=TargetClassTest`)
- **Per wave merge:** Run full integration test for the affected service module
- **Phase gate:** `mvn verify` green across all 5 modules before marking Phase 1 complete

### Wave 0 Gaps

- [ ] `account-service/src/test/java/AccountCreationIT.java` — covers SC-1 (BSB validation)
- [ ] `account-service/src/test/java/AtomicTransferIT.java` — covers SC-2 (ACID atomicity, verifies outbox row in DB)
- [ ] `payment-service/src/test/java/IdempotencyIT.java` — covers SC-3 (Redis idempotency)
- [ ] `payment-service/src/test/java/TransferStateMachineTest.java` — covers SC-4 (state transitions, no container needed)
- [ ] `**/src/test/resources/application-test.yml` — Testcontainers datasource override per service
- [ ] `testcontainers-bom` version entry in parent `dependencyManagement` (current: 1.20.x) [ASSUMED — verify latest]

### Validation for SC-2 (ACID Atomicity)

ACID atomicity is not directly testable by the API response alone. The integration test must verify the outbox row via a direct JDBC query within the same test:

```java
// Source: [ASSUMED] Testcontainers + Spring JDBC integration test pattern
@Test
void transferInsertsOutboxRowInSameTx() {
    // 1. Create accounts via API
    // 2. POST transfer via account-service internal endpoint
    // 3. Verify debit balance via API
    // 4. Verify credit balance via API
    // 5. Verify outbox row via direct JDBC query:
    int outboxCount = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM outbox_event WHERE aggregateid = ?",
        Integer.class, transferId.toString());
    assertThat(outboxCount).isEqualTo(1);
}
```

---

## Security Domain

Phase 1 is Podman Compose local dev only — no external network exposure, no auth layer in this phase. Security enforcement is noted for completeness; full ASVS compliance is Phase 3.

### Applicable ASVS Categories for Phase 1

| ASVS Category | Applies in Phase 1 | Standard Control |
|---------------|--------------------|-----------------|
| V2 Authentication | No — no external API in Phase 1 | Phase 3 (Keycloak + Kong JWT) |
| V3 Session Management | No | Phase 3 |
| V4 Access Control | Partial — account-service must be internal-only | `server.port` isolation, no external expose in compose |
| V5 Input Validation | YES | Jakarta Bean Validation `@Valid`, `@ValidBsb` |
| V6 Cryptography | No — no secrets transmitted in Phase 1 | Phase 3 |

### Phase 1 Security Baseline (Minimum Required)

- All monetary input must pass `@Validated` before any repository call — prevents invalid `BigDecimal` injection.
- account-service must NOT be exposed on an external host port in `compose.yml` (only payment-service and potentially ledger-service expose ports for testing).
- PostgreSQL passwords in `compose.yml` are acceptable for local dev only — do NOT commit production credentials.

---

## Project Constraints (from CLAUDE.md)

These directives apply to all tasks in this phase:

1. **Java 21 LTS only.** No other Java version.
2. **Spring Boot — verify 4.0.x GA before pinning.** 4.0.5 is confirmed GA as of 2026-03-26.
3. **Podman (rootless, daemonless). NOT Docker.** All compose commands use `podman-compose`.
4. **Local only.** No cloud provider, no AWS/GCP/Azure resources.
5. **Database isolation.** Each service owns its own PostgreSQL container and schema. No shared DB.
6. **BigDecimal + DECIMAL(15,4).** No `double` or `float` for any monetary field, anywhere.
7. **SELECT FOR UPDATE (PESSIMISTIC_WRITE).** Balance queries must lock rows before read.
8. **Flyway manages schema.** `spring.jpa.hibernate.ddl-auto=validate` on all services.
9. **Spring State Machine is incompatible with Spring Boot 4.** Enum FSM recommended. Raise with user before implementing.
10. **PostgreSQL 16 or 17.** Version 15 is not acceptable. Use `postgres:17-alpine`.
11. **Redis 7.2.x.** Use `redis:7.2-alpine`.
12. **Maven multi-module.** `spring-boot-maven-plugin` only on service modules, not `common`.
13. **GSD workflow enforcement.** All code changes go through `/gsd-execute-phase`, not direct edits.

---

## Sources

### Primary (HIGH confidence)

- Spring Boot BOM (docs.spring.io) — confirmed SB 4.0.5 bundles: Spring Framework 7.0.6, Flyway 11.14.1, Hibernate 7.2.7.Final, Lettuce 6.8.2, Lombok 1.18.44, Jackson 3.x
- eosl.date/spring-boot — confirmed SB 4.0.5 GA release date 2026-03-26
- spring.io/blog/2025/11/20/spring-boot-4-0-0-available-now — SB 4.0.0 GA announcement
- github.com/spring-projects/spring-statemachine — Spring State Machine in maintenance mode, latest 4.0.1 targets SB 3.5.x / SF 6.2.x
- github.com/flyway/flyway/issues/4165 — Flyway + Spring Boot 4 starter requirement, resolved 2026-01-09
- github.com/containers/podman-compose/issues/1183 — `service_healthy` condition resolved May 2025
- mvnrepository.com/artifact/io.github.resilience4j/resilience4j-spring-boot4/2.4.0 — Resilience4j SB4 module, version 2.4.0 (~March 2026)
- debezium.io documentation — Outbox EventRouter required columns (aggregateid, aggregatetype, type, payload)

### Secondary (MEDIUM confidence)

- pranavkhodanpur.medium.com — Flyway Spring Boot 4 breaking changes (starter requirement)
- spring.io/blog/2025/10/07/introducing-jackson-3-support-in-spring — Jackson 3 in Spring
- github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide — official SB4 migration guide
- mapstruct.org/documentation/stable — MapStruct 1.6.3 current stable, Maven annotationProcessorPaths config

### Tertiary (LOW confidence / ASSUMED)

- DZone "A Simple State Machine for Spring Boot Projects" — enum FSM pattern (training knowledge, not fetched)
- Testcontainers Podman integration — known to work with env var overrides (training knowledge)
- `lombok-mapstruct-binding:0.2.0` version — assumed current; verify on mvnrepository.com

---

## Metadata

**Confidence breakdown:**
- Spring Boot 4.0.5 GA status: HIGH — multiple sources confirmed
- Spring State Machine incompatibility: HIGH — verified via official GitHub
- Flyway 11 starter requirement: HIGH — verified via GitHub issue + official article
- Jackson 3 breaking changes: HIGH — verified via spring.io blog post
- Enum FSM pattern: MEDIUM — pattern is well-known but specific implementation is ASSUMED
- Resilience4j 2.4.0 compatibility: MEDIUM — artifact exists on Maven Central, GitHub module `resilience4j-spring-boot4` exists, full test matrix ASSUMED
- Testcontainers Podman integration: MEDIUM — documented in community, exact config flags ASSUMED

**Research date:** 2026-04-10
**Valid until:** 2026-05-10 (30 days for stable ecosystem; Spring State Machine status is unlikely to change)
