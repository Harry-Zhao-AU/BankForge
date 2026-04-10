---
phase: 01-acid-core-cdc-pipeline
plan: "03"
subsystem: payment
tags: [spring-boot, redis, idempotency, state-machine, rest-client, testcontainers, mockitobean, jackson3]

# Dependency graph
requires:
  - phase: 01-acid-core-cdc-pipeline
    plan: "01"
    provides: "common module (TransferStateMachine, TransferState/Event enums), compose.yml (Redis 7.2)"
  - phase: 01-acid-core-cdc-pipeline
    plan: "02"
    provides: "account-service POST /api/internal/transfers contract (TransferRequest/Response)"
provides:
  - Flyway V1 migration: transfers table (DECIMAL(15,4) amount, VARCHAR(32) state, unique idempotency_key)
  - Transfer JPA entity with @Enumerated(EnumType.STRING) state column
  - TransferRepository with findByIdempotencyKey
  - IdempotencyService: StringRedisTemplate.setIfAbsent() with 24h TTL (TXNS-05)
  - AccountServiceClient: RestClient wrapper for POST /api/internal/transfers (@Qualifier injection)
  - RestClientConfig: @Bean("accountRestClient") reading services.account.base-url
  - PaymentService: full transfer lifecycle orchestration (PENDING → CONFIRMED, compensation → CANCELLED)
  - PaymentController: POST /api/payments/transfers (201 new / 200 replay per TXNS-05), GET /api/payments/transfers/{id}
  - GlobalExceptionHandler: 409 duplicate, 400 transfer failed, 500 invalid transition, 400 validation
  - 12 passing tests: IdempotencyServiceTest (4), PaymentServiceTest (4), PaymentControllerIT (4)
affects:
  - 01-04 (Compose stack verification runs against full payment-service implementation)
  - 01.1 (payment-service CDC outbox wired in Phase 1.1)

# Tech tracking
tech-stack:
  added:
    - "@MockitoBean (org.springframework.test.context.bean.override.mockito) — Spring Framework 7 replacement for removed @MockBean"
    - "@Qualifier on constructor parameter — required when RestClient @Bean name clashes with @Component class name"
  patterns:
    - "Pattern: @MockitoBean over @MockBean — @MockBean removed in Spring Boot 4; @MockitoBean from spring-test 7.0.6"
    - "Pattern: RestClient @Bean naming — bean name must differ from the @Component class that injects it (BeanDefinitionOverrideException otherwise)"
    - "Pattern: @DynamicPropertySource overrides application-test.yml — use plain driver-class-name (org.postgresql.Driver), not ContainerDatabaseDriver"
    - "Pattern: isNewTransfer() before initiateTransfer() — controller captures idempotency state before service mutates Redis"

key-files:
  created:
    - payment-service/src/main/resources/db/migration/V1__create_transfer_tables.sql
    - payment-service/src/main/java/au/com/bankforge/payment/entity/Transfer.java
    - payment-service/src/main/java/au/com/bankforge/payment/repository/TransferRepository.java
    - payment-service/src/main/java/au/com/bankforge/payment/dto/InitiateTransferRequest.java
    - payment-service/src/main/java/au/com/bankforge/payment/dto/InitiateTransferResponse.java
    - payment-service/src/main/java/au/com/bankforge/payment/dto/TransferStatusResponse.java
    - payment-service/src/main/java/au/com/bankforge/payment/service/IdempotencyService.java
    - payment-service/src/main/java/au/com/bankforge/payment/client/AccountServiceClient.java
    - payment-service/src/main/java/au/com/bankforge/payment/config/RestClientConfig.java
    - payment-service/src/main/java/au/com/bankforge/payment/service/PaymentService.java
    - payment-service/src/main/java/au/com/bankforge/payment/controller/PaymentController.java
    - payment-service/src/main/java/au/com/bankforge/payment/exception/DuplicateRequestException.java
    - payment-service/src/main/java/au/com/bankforge/payment/exception/TransferFailedException.java
    - payment-service/src/main/java/au/com/bankforge/payment/exception/GlobalExceptionHandler.java
    - payment-service/src/test/java/au/com/bankforge/payment/service/IdempotencyServiceTest.java
    - payment-service/src/test/java/au/com/bankforge/payment/service/PaymentServiceTest.java
    - payment-service/src/test/java/au/com/bankforge/payment/controller/PaymentControllerIT.java
    - payment-service/src/test/resources/application-test.yml
  modified:
    - payment-service/pom.xml (added testcontainers core artifact + Surefire: DOCKER_HOST, RYUK_DISABLED, *IT.java includes)

key-decisions:
  - "@MockitoBean replaces @MockBean: Spring Boot 4 removed @MockBean from spring-boot-test; @MockitoBean from org.springframework.test.context.bean.override.mockito is the Spring Framework 7 replacement"
  - "RestClient bean named 'accountRestClient' (not 'accountServiceClient'): avoids BeanDefinitionOverrideException with @Component AccountServiceClient sharing the same default bean name"
  - "isNewTransfer() called in controller before initiateTransfer(): captures idempotency state (Redis check) at request arrival time for correct 201/200 HTTP status"

patterns-established:
  - "Pattern: @MockitoBean — org.springframework.test.context.bean.override.mockito.MockitoBean for @SpringBootTest mock injection in Spring Boot 4"
  - "Pattern: RestClient @Bean name isolation — name @Bean explicitly when the declaring class name would collide with an @Component"
  - "Pattern: @DynamicPropertySource datasource — override url/username/password in tests, use plain PostgreSQL driver (not Testcontainers JDBC URL driver)"

requirements-completed: [CORE-03, TXNS-04, TXNS-05]

# Metrics
duration: 14min
completed: "2026-04-10"
---

# Phase 01 Plan 03: Payment Service — Idempotency + State Machine + Tests Summary

**Full payment-service with Redis idempotency (setIfAbsent, 24h TTL), 7-state transfer FSM orchestration (PENDING → CONFIRMED, compensation → CANCELLED), RestClient to account-service, and 12 passing Testcontainers integration tests**

## Performance

- **Duration:** ~14 min
- **Started:** 2026-04-10T06:39:37Z
- **Completed:** 2026-04-10T06:53:49Z
- **Tasks:** 3
- **Files created:** 18, modified: 1

## Accomplishments

- `POST /api/payments/transfers` creates transfer, runs full state machine to CONFIRMED: PENDING → PAYMENT_PROCESSING → PAYMENT_DONE → POSTING → CONFIRMED
- Redis idempotency with `setIfAbsent`: first call returns 201 CREATED, duplicate key returns 200 OK with cached response (TXNS-05)
- Failure path: exception from account-service triggers FAIL → COMPENSATING → CANCELLED with error message stored
- `GET /api/payments/transfers/{id}` returns current transfer state
- All monetary operations use BigDecimal (no float/double per D-09)
- State transitions enforced by `TransferStateMachine.transition()` — no direct enum assignment
- Jackson 3 (`tools.jackson.databind.ObjectMapper`) used for Redis serialization
- 12 tests pass: 4 idempotency unit tests, 4 PaymentService integration tests, 4 PaymentControllerIT integration tests

## Task Commits

Each task was committed atomically:

1. **Task 1: Transfer entity, Flyway migration, IdempotencyService, AccountServiceClient, DTOs, exceptions** - `cecfe50` (feat)
2. **Task 2: PaymentService orchestration and PaymentController** - `8068af5` (feat)
3. **Task 3: Payment-service unit and integration tests (12 passing)** - `75a9f8c` (feat)

## Files Created/Modified

- `V1__create_transfer_tables.sql` — transfers table with DECIMAL(15,4), VARCHAR(32) state, unique idempotency_key index
- `Transfer.java` — JPA entity with @Enumerated(EnumType.STRING) state, @Builder.Default PENDING, @PrePersist/@PreUpdate timestamps
- `TransferRepository.java` — JpaRepository + findByIdempotencyKey
- `InitiateTransferRequest.java` — record with @NotNull UUIDs, @DecimalMin("0.01") BigDecimal amount, @NotBlank idempotencyKey
- `InitiateTransferResponse.java`, `TransferStatusResponse.java` — response records with BigDecimal amounts
- `IdempotencyService.java` — StringRedisTemplate.setIfAbsent() with 24h TTL, "idempotency:" key prefix
- `AccountServiceClient.java` — RestClient wrapper for POST /api/internal/transfers, @Qualifier("accountRestClient")
- `RestClientConfig.java` — @Bean("accountRestClient") reading services.account.base-url
- `PaymentService.java` — full transfer lifecycle: idempotency → PENDING → PAYMENT_PROCESSING → account-service call → PAYMENT_DONE → POSTING → CONFIRMED; catch → FAIL → COMPENSATING → CANCELLED
- `PaymentController.java` — POST /api/payments/transfers (isNewTransfer() → 201/200), GET /api/payments/transfers/{id}
- `DuplicateRequestException.java`, `TransferFailedException.java`, `GlobalExceptionHandler.java` — 409/400/500/400 mappings
- `IdempotencyServiceTest.java` — 4 tests: setIfNew true/false, getCached present/empty
- `PaymentServiceTest.java` — 4 tests: happy path CONFIRMED, idempotency replay no double-call, failure CANCELLED, getTransferStatus
- `PaymentControllerIT.java` — 4 tests: POST 201, POST 200 replay, GET 200, POST 400 missing key
- `application-test.yml` — plain PostgreSQL driver, URL/credentials overridden by @DynamicPropertySource
- `payment-service/pom.xml` — testcontainers core + Surefire (DOCKER_HOST, RYUK_DISABLED, *IT.java includes)

## Decisions Made

- **@MockitoBean replaces @MockBean:** Spring Boot 4 removed `@MockBean` from the `spring-boot-test` JAR. `@MockitoBean` from `org.springframework.test.context.bean.override.mockito` (Spring Framework 7, in `spring-test` JAR) is the direct replacement. Confirmed by inspecting the `spring-boot-test-4.0.5.jar` contents — no `MockBean.class` present.
- **RestClient bean named "accountRestClient":** Naming the `@Bean` method `accountServiceClient` caused a `BeanDefinitionOverrideException` at startup because `@Component AccountServiceClient` is also registered as `accountServiceClient`. Renamed bean to `accountRestClient`; `AccountServiceClient` injects it via `@Qualifier("accountRestClient")` in its constructor (Lombok `@RequiredArgsConstructor` cannot add `@Qualifier` so switched to explicit constructor).
- **isNewTransfer() in controller, not service:** The controller must check `isNewTransfer()` before calling `initiateTransfer()`. If checked inside the service, the service would set the idempotency key in Redis and both new and replay calls would see it as "already set" by the time the status is determined for the HTTP response.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] @MockBean removed in Spring Boot 4**
- **Found during:** Task 3 (test-compile)
- **Issue:** `cannot find symbol: class MockBean` — `@MockBean` was removed from `spring-boot-test` in Spring Boot 4. Confirmed by inspecting the JAR: no `MockBean.class` in `spring-boot-test-4.0.5.jar`.
- **Fix:** Replaced `import org.springframework.boot.test.mock.mockito.MockBean` with `import org.springframework.test.context.bean.override.mockito.MockitoBean` and `@MockBean` → `@MockitoBean` in PaymentServiceTest.java and PaymentControllerIT.java.
- **Files modified:** PaymentServiceTest.java, PaymentControllerIT.java
- **Committed in:** `75a9f8c` (Task 3 commit)

**2. [Rule 1 - Bug] BeanDefinitionOverrideException: @Bean "accountServiceClient" clashes with @Component AccountServiceClient**
- **Found during:** Task 3 (first test run)
- **Issue:** `BeanDefinitionOverrideException: Cannot register bean definition for bean 'accountServiceClient' since there is already [Generic bean: class=AccountServiceClient] bound.` Spring registered the `@Bean RestClient` and `@Component AccountServiceClient` under the same name.
- **Fix:** Renamed `@Bean` method to `accountRestClient()` with explicit `@Bean("accountRestClient")`. Updated `AccountServiceClient` to use explicit constructor with `@Qualifier("accountRestClient")` instead of Lombok `@RequiredArgsConstructor`.
- **Files modified:** RestClientConfig.java, AccountServiceClient.java
- **Committed in:** `75a9f8c` (Task 3 commit)

**3. [Rule 1 - Bug] ContainerDatabaseDriver rejected real JDBC URL from @DynamicPropertySource**
- **Found during:** Task 3 (second test run after fix 2)
- **Issue:** `Driver org.testcontainers.jdbc.ContainerDatabaseDriver claims to not accept jdbcUrl: jdbc:postgresql://localhost:46197/test`. The `application-test.yml` declared `driver-class-name: org.testcontainers.jdbc.ContainerDatabaseDriver` for the `jdbc:tc:` auto-start pattern, but `@DynamicPropertySource` was overriding `spring.datasource.url` with the real mapped-port URL (`jdbc:postgresql://...`). The `ContainerDatabaseDriver` rejects non-`jdbc:tc:` URLs.
- **Fix:** Changed `application-test.yml` to use `org.postgresql.Driver` with placeholder values; `@DynamicPropertySource` in each test class overrides all three datasource properties (url, username, password).
- **Files modified:** application-test.yml
- **Committed in:** `75a9f8c` (Task 3 commit)

**4. [Rule 1 - Bug] Test assertion used mock response amount (50.00) instead of request amount (100.00)**
- **Found during:** Task 3 (third test run after fix 3)
- **Issue:** `PaymentServiceTest.getTransferStatus_returnsCurrentState` asserted `isEqualByComparingTo(50.00)` but the transfer stores the request amount (100.00). The mock's `AccountTransferResponse` uses `50.00` as the account-service response (not used by PaymentService for storage).
- **Fix:** Changed assertion to `isEqualByComparingTo(new BigDecimal("100.00"))`.
- **Files modified:** PaymentServiceTest.java
- **Committed in:** `75a9f8c` (Task 3 commit)

---

**Total deviations:** 4 auto-fixed (4 bugs)
**Impact on plan:** All are Spring Boot 4 compatibility fixes and a test logic error. No scope creep.

## Known Stubs

- `PaymentService.java` auto-confirms after POSTING (POSTING → CONFIRMED without waiting for ledger event). This is intentional for Phase 1. Phase 1.1 will make this event-driven via Kafka — ledger-service confirmation event triggers the CONFIRM transition.

## Threat Surface Scan

All T-1-06 through T-1-10 mitigations from the plan's threat model are implemented:
- T-1-06 (Spoofing - idempotency key): UNIQUE index on transfers.idempotency_key provides DB-level safety net if Redis is unavailable
- T-1-07 (Tampering - transfer state): TransferStateMachine.transition() enforces valid transitions only; state stored as @Enumerated(EnumType.STRING)
- T-1-08 (Input validation): @Valid on all controller inputs; @NotNull, @DecimalMin, @NotBlank on InitiateTransferRequest
- T-1-09 (DoS - Redis): Accepted; rate limiting deferred to Phase 3 (Kong)
- T-1-10 (Info disclosure): GlobalExceptionHandler returns generic messages; error details logged server-side only

## User Setup Required

The Podman machine must be running before executing integration tests:
```
podman machine start
mvn test -pl payment-service
```
The Surefire configuration handles `DOCKER_HOST` and `TESTCONTAINERS_RYUK_DISABLED` automatically.

## Next Phase Readiness

**Ready for Plan 04 (Compose stack verification):**
- payment-service fully implemented: POST /api/payments/transfers + GET /api/payments/transfers/{id}
- payment-service depends on account-service at `http://account-service:8080` (Compose service name)
- Redis idempotency configured for `redis:6379` (Compose service name)
- All 12 tests pass via Testcontainers

**Ready for Phase 1.1 (CDC Pipeline):**
- payment-service state machine lifecycle is complete through CONFIRMED
- POSTING → CONFIRMED is currently auto-confirmed (stub); Phase 1.1 makes it event-driven
- Outbox pattern not yet added to payment-service (account-service handles outbox in Phase 1)

---
*Phase: 01-acid-core-cdc-pipeline*
*Completed: 2026-04-10*
