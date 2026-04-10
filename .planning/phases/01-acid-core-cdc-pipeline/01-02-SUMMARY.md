---
phase: 01-acid-core-cdc-pipeline
plan: "02"
subsystem: database
tags: [spring-boot, jpa, hibernate7, flyway, mapstruct, testcontainers, postgresql, jsonb, pessimistic-locking, outbox, acid]

# Dependency graph
requires:
  - phase: 01-acid-core-cdc-pipeline
    plan: "01"
    provides: "common module (TransferCreatedEvent DTO, @ValidBsb), compose.yml (postgres:17 with wal_level=logical), account-service scaffold"
provides:
  - Flyway V1 + V2 migrations: accounts table (DECIMAL(15,4)), Debezium-compatible outbox_event (JSONB)
  - Account JPA entity with @PrePersist/@PreUpdate timestamps
  - OutboxEvent JPA entity with @JdbcTypeCode(SqlTypes.JSON) for Hibernate 7 JSONB binding
  - AccountRepository with @Lock(PESSIMISTIC_WRITE) findByIdForUpdate
  - OutboxEventRepository with native JSONB query findTransfersByAccountId
  - 6 DTOs (CreateAccountRequest with @ValidBsb, TransferRequest with @DecimalMin, TransferHistoryResponse)
  - AccountMapper (MapStruct compile-time, spring component model)
  - InsufficientFundsException, AccountNotFoundException, GlobalExceptionHandler (400/404)
  - AccountService (createAccount, getAccount, getTransferHistory via JSONB query)
  - TransferService — ACID executeTransfer (debit+credit+outbox in single @Transactional, UUID lock ordering)
  - AccountController (POST /api/accounts 201, GET /{id}, GET /{id}/transfers)
  - TransferController (POST /api/internal/transfers — internal endpoint)
  - 8 Testcontainers integration tests (AccountControllerIT 5, TransferServiceIT 2, ConcurrentTransferIT 1)
affects:
  - 01-03 (payment-service calls POST /api/internal/transfers; account-service client contract established)
  - 01-04 (Compose stack verification runs against full account-service implementation)
  - 01.1 (Debezium CDC will read outbox_event table; column names aggregatetype/aggregateid/type/payload locked)

# Tech tracking
tech-stack:
  added:
    - RestClient (Spring 6.1+ / Spring Boot 4) — replaces removed TestRestTemplate for integration tests
    - "@JdbcTypeCode(SqlTypes.JSON) from hibernate-core 7 — required for JSONB column binding"
    - "Testcontainers jdbc:tc:postgresql:17 URL — auto-starts PostgreSQL 17 for integration tests"
    - "Podman named pipe (npipe:////./pipe/docker_engine) — DOCKER_HOST for Testcontainers on Windows"
  patterns:
    - "Pattern: ACID transfer — debit + credit + outbox_event write in single @Transactional boundary"
    - "Pattern: Deadlock prevention — always acquire PESSIMISTIC_WRITE locks in ascending UUID order (compareTo)"
    - "Pattern: JSONB column binding — @JdbcTypeCode(SqlTypes.JSON) on String payload field in Hibernate 7"
    - "Pattern: Outbox transfer history — native JSONB query on payload->>'fromAccountId' / payload->>'toAccountId'"
    - "Pattern: Internal endpoint separation — /api/internal/transfers not externally routed (Kong blocks in Phase 3)"
    - "Pattern: RestClient in tests — RestClient.builder().baseUrl(...).build() per test class, @LocalServerPort injected"
    - "Pattern: Surefire IT inclusion — explicit <includes> for *IT.java alongside *Test.java"

key-files:
  created:
    - account-service/src/main/resources/db/migration/V1__create_account_tables.sql
    - account-service/src/main/resources/db/migration/V2__create_outbox_table.sql
    - account-service/src/main/java/au/com/bankforge/account/entity/Account.java
    - account-service/src/main/java/au/com/bankforge/account/entity/OutboxEvent.java
    - account-service/src/main/java/au/com/bankforge/account/repository/AccountRepository.java
    - account-service/src/main/java/au/com/bankforge/account/repository/OutboxEventRepository.java
    - account-service/src/main/java/au/com/bankforge/account/dto/CreateAccountRequest.java
    - account-service/src/main/java/au/com/bankforge/account/dto/CreateAccountResponse.java
    - account-service/src/main/java/au/com/bankforge/account/dto/AccountDto.java
    - account-service/src/main/java/au/com/bankforge/account/dto/TransferRequest.java
    - account-service/src/main/java/au/com/bankforge/account/dto/TransferResponse.java
    - account-service/src/main/java/au/com/bankforge/account/dto/TransferHistoryResponse.java
    - account-service/src/main/java/au/com/bankforge/account/mapper/AccountMapper.java
    - account-service/src/main/java/au/com/bankforge/account/exception/InsufficientFundsException.java
    - account-service/src/main/java/au/com/bankforge/account/exception/AccountNotFoundException.java
    - account-service/src/main/java/au/com/bankforge/account/exception/GlobalExceptionHandler.java
    - account-service/src/main/java/au/com/bankforge/account/service/AccountService.java
    - account-service/src/main/java/au/com/bankforge/account/service/TransferService.java
    - account-service/src/main/java/au/com/bankforge/account/controller/AccountController.java
    - account-service/src/main/java/au/com/bankforge/account/controller/TransferController.java
    - account-service/src/test/java/au/com/bankforge/account/controller/AccountControllerIT.java
    - account-service/src/test/java/au/com/bankforge/account/service/TransferServiceIT.java
    - account-service/src/test/java/au/com/bankforge/account/service/ConcurrentTransferIT.java
    - account-service/src/test/resources/application-test.yml
  modified:
    - account-service/pom.xml (Surefire: DOCKER_HOST, RYUK disabled, *IT.java includes)

key-decisions:
  - "RestClient replaces TestRestTemplate in integration tests — TestRestTemplate was removed in Spring Boot 4"
  - "@JdbcTypeCode(SqlTypes.JSON) required for Hibernate 7 JSONB binding — columnDefinition alone is insufficient"
  - "Podman named pipe (npipe:////./pipe/docker_engine) configured in Surefire env vars for CI reproducibility"
  - "Lock ordering via UUID.compareTo() prevents deadlocks — both threads acquire locks in identical ascending order"
  - "Balance check AFTER lock acquisition (not before) — prevents TOCTOU race under Read Committed isolation"
  - "Outbox aggregateid stores transferId (not accountId) — JSONB payload query used for per-account transfer history"

patterns-established:
  - "Pattern: ACID Transfer — TransferService.executeTransfer wraps debit+credit+outbox in @Transactional; UUID lock ordering via compareTo prevents deadlocks"
  - "Pattern: JSONB entity field — String field annotated @JdbcTypeCode(SqlTypes.JSON) + @Column(columnDefinition='jsonb') for Hibernate 7"
  - "Pattern: Integration test HTTP client — RestClient.builder().baseUrl('http://localhost:' + port).build() in @BeforeEach"
  - "Pattern: Podman + Testcontainers — DOCKER_HOST=npipe:////./pipe/docker_engine + TESTCONTAINERS_RYUK_DISABLED=true in Surefire env"

requirements-completed: [CORE-01, CORE-02, TXNS-01]

# Metrics
duration: 15min
completed: "2026-04-10"
---

# Phase 01 Plan 02: Account Service — ACID Transfers + Transfer History Summary

**Full account-service with BSB-validated account CRUD, ACID fund transfers (debit+credit+outbox in one PostgreSQL transaction), concurrent overdraft prevention via PESSIMISTIC_WRITE + UUID lock ordering, and 8 passing Testcontainers integration tests**

## Performance

- **Duration:** ~15 min
- **Started:** 2026-04-10T06:19:17Z
- **Completed:** 2026-04-10T06:34:45Z
- **Tasks:** 3
- **Files created:** 24, modified: 1

## Accomplishments

- `POST /api/accounts` creates account with BSB validation (201 valid / 400 invalid BSB)
- `POST /api/internal/transfers` atomically debits + credits + writes outbox_event in one transaction
- `GET /api/accounts/{id}/transfers` returns transfer history via native JSONB query on outbox_event payload
- 10 concurrent $100 transfers from $500 balance: exactly 5 succeed, 5 fail — no overdraft (PESSIMISTIC_WRITE verified)
- All 8 integration tests pass via Testcontainers PostgreSQL 17 with Podman named pipe

## Task Commits

Each task was committed atomically:

1. **Task 1: Flyway migrations, JPA entities, repositories, DTOs, mapper, exceptions** - `6f37a61` (feat)
2. **Task 2: AccountService, TransferService (ACID), REST controllers** - `5a04f9e` (feat)
3. **Task 3: Integration tests with Testcontainers** - `6a4eb99` (feat)

## Files Created/Modified

- `V1__create_account_tables.sql` — accounts table with DECIMAL(15,4) balance (D-09)
- `V2__create_outbox_table.sql` — Debezium-compatible outbox_event with JSONB payload, lowercase column names
- `Account.java` — JPA entity, @PrePersist/@PreUpdate timestamps, BigDecimal balance with precision=15,scale=4
- `OutboxEvent.java` — JPA entity with @JdbcTypeCode(SqlTypes.JSON) for JSONB binding in Hibernate 7
- `AccountRepository.java` — findByIdForUpdate with @Lock(PESSIMISTIC_WRITE) per D-10
- `OutboxEventRepository.java` — findByAggregateidOrderByCreatedAtDesc + native JSONB findTransfersByAccountId
- `CreateAccountRequest.java` — @ValidBsb on bsb field (from common module)
- `TransferRequest.java` — @DecimalMin("0.01") on BigDecimal amount
- `TransferHistoryResponse.java` — record with eventId, type, payload, createdAt
- `AccountMapper.java` — MapStruct compile-time mapper (spring component model)
- `InsufficientFundsException.java`, `AccountNotFoundException.java`, `GlobalExceptionHandler.java` — 400/404 handlers
- `AccountService.java` — createAccount, getAccount, getTransferHistory (JSONB outbox query)
- `TransferService.java` — ACID executeTransfer: UUID lock ordering, PESSIMISTIC_WRITE, outbox write in same TX, Jackson 3
- `AccountController.java` — POST /api/accounts (201), GET /{id} (200), GET /{id}/transfers (200)
- `TransferController.java` — POST /api/internal/transfers (internal endpoint)
- `AccountControllerIT.java` — 5 tests: BSB validation, account GET, transfer history
- `TransferServiceIT.java` — 2 tests: ACID debit+credit+outbox, insufficient funds rollback with JdbcTemplate verification
- `ConcurrentTransferIT.java` — 1 test: 10 concurrent threads, exactly 5 succeed, balance = $0
- `application-test.yml` — jdbc:tc:postgresql:17 Testcontainers JDBC URL
- `account-service/pom.xml` — Surefire: DOCKER_HOST=npipe, RYUK disabled, *IT.java includes

## Decisions Made

- **RestClient over TestRestTemplate:** TestRestTemplate was removed in Spring Boot 4. RestClient (Spring 6.1+) is the standard replacement. Each test class creates a RestClient with `@LocalServerPort`-injected base URL in `@BeforeEach`.
- **@JdbcTypeCode(SqlTypes.JSON) for JSONB:** Hibernate 7 requires explicit JDBC type mapping for JSONB columns on String fields. `@Column(columnDefinition = "jsonb")` alone is insufficient — Hibernate still binds as VARCHAR, causing a PostgreSQL type error. `@JdbcTypeCode(SqlTypes.JSON)` tells Hibernate to use JSON binding.
- **Podman DOCKER_HOST in Surefire:** Rather than requiring developers to manually export `DOCKER_HOST`, the Surefire plugin configuration sets `DOCKER_HOST=npipe:////./pipe/docker_engine` as an environment variable. This makes `mvn test` work out of the box once the Podman machine is running.
- **Outbox aggregateid = transferId:** The outbox_event stores the transferId as aggregateid (not accountId). This is correct for Debezium routing (each event = one transfer). Transfer history per account uses a native JSONB query on `payload->>'fromAccountId'` and `payload->>'toAccountId'`.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] TestRestTemplate removed in Spring Boot 4**
- **Found during:** Task 3 (integration test compilation)
- **Issue:** Plan specified `TestRestTemplate` but it was removed in Spring Boot 4. All 3 test classes failed to compile with `package org.springframework.boot.test.web.client does not exist`.
- **Fix:** Rewrote all integration tests to use `RestClient` (Spring 6.1+) with `@LocalServerPort` for the base URL. `RestClientResponseException` used to assert 4xx status codes.
- **Files modified:** AccountControllerIT.java, TransferServiceIT.java, ConcurrentTransferIT.java
- **Verification:** `mvn test-compile -pl account-service` exits 0; all 8 tests pass
- **Committed in:** `6a4eb99` (Task 3 commit)

**2. [Rule 1 - Bug] Hibernate 7 JSONB column binding requires @JdbcTypeCode(SqlTypes.JSON)**
- **Found during:** Task 3 (first test run)
- **Issue:** `ERROR: column "payload" is of type jsonb but expression is of type character varying`. Hibernate 7 bound the `String payload` field as VARCHAR despite `@Column(columnDefinition = "jsonb")`.
- **Fix:** Added `@JdbcTypeCode(SqlTypes.JSON)` and `import org.hibernate.annotations.JdbcTypeCode; import org.hibernate.type.SqlTypes;` to OutboxEvent.java.
- **Files modified:** `account-service/src/main/java/au/com/bankforge/account/entity/OutboxEvent.java`
- **Verification:** Transfer test completes without SQL type error; outbox_event row created correctly
- **Committed in:** `6a4eb99` (Task 3 commit)

**3. [Rule 3 - Blocking] Podman named pipe required for Testcontainers on Windows**
- **Found during:** Task 3 (first test run)
- **Issue:** `Could not find a valid Docker environment` — Testcontainers couldn't detect the container runtime. The Podman machine was not running initially; once started, the named pipe `npipe:////./pipe/docker_engine` needed to be passed as `DOCKER_HOST`.
- **Fix:** Added `DOCKER_HOST=npipe:////./pipe/docker_engine` and `TESTCONTAINERS_RYUK_DISABLED=true` to Surefire `<environmentVariables>`. Also added `*IT.java` to Surefire `<includes>` (default includes only `*Test.java`).
- **Files modified:** `account-service/pom.xml`
- **Verification:** `mvn test -pl account-service` exits 0 without manual `export DOCKER_HOST`
- **Committed in:** `6a4eb99` (Task 3 commit)

---

**Total deviations:** 3 auto-fixed (2 bugs, 1 blocking)
**Impact on plan:** All auto-fixes were necessary for correctness and Spring Boot 4 compatibility. No scope creep.

## Issues Encountered

- **Podman machine not started:** The Podman machine (`podman-machine-default`) was not running at test start. Required `podman machine start` before tests could run. The Surefire env var fix means subsequent runs work automatically, but the machine must be running.

## Known Stubs

None. All plan functionality is fully implemented and tested.

## Threat Surface Scan

No new security-relevant surface beyond what was in the plan's threat model. All T-1-0x mitigations are implemented:
- T-1-01: PESSIMISTIC_WRITE + lock ordering implemented in TransferService
- T-1-02: Jakarta @Valid on all request bodies, @ValidBsb, @DecimalMin
- T-1-03: Outbox write in same transaction as balance changes
- T-1-05: GlobalExceptionHandler returns generic messages only

## User Setup Required

The Podman machine must be running before executing integration tests:
```
podman machine start
mvn test -pl account-service
```
The Surefire configuration handles `DOCKER_HOST` and `TESTCONTAINERS_RYUK_DISABLED` automatically.

## Next Phase Readiness

**Ready for Plan 03 (payment-service implementation):**
- `POST /api/internal/transfers` endpoint available at `http://account-service:8080/api/internal/transfers`
- `TransferRequest` / `TransferResponse` DTOs defined and tested
- account-service compiles and all integration tests pass
- Outbox table schema locked — Debezium EventRouter will read aggregatetype, aggregateid, type, payload columns

**Concerns:**
- payment-service needs to call account-service over HTTP; in Compose, this is `http://account-service:8080`. In Phase 3 (K8s), Istio service mesh will handle routing. No service discovery mechanism exists yet.

---
*Phase: 01-acid-core-cdc-pipeline*
*Completed: 2026-04-10*
