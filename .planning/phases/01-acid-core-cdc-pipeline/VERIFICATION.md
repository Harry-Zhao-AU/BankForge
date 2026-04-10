---
phase: 01-acid-core-cdc-pipeline
verified: 2026-04-10T08:30:00Z
status: passed
score: 4/4
overrides_applied: 0
---

# Phase 1: Service Scaffold + Core Banking — Verification Report

**Phase Goal:** Four Spring Boot services run correctly on Podman Compose — accounts can be created with BSB validation, funds transfer atomically (debit + credit + outbox row in one TX), the transfer state machine transitions through all states, and Redis idempotency blocks duplicate requests. No event streaming yet.

**Verified:** 2026-04-10T08:30:00Z
**Status:** PASSED
**Re-verification:** No — initial verification

---

## Goal Achievement

### Observable Truths (from ROADMAP.md Success Criteria)

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | POST to account-service creates account with valid BSB (NNN-NNN) and rejects malformed BSBs | VERIFIED | `BsbValidator.java` enforces `^\d{3}-\d{3}$` regex; null rejection explicit. `AccountControllerIT` tests `012-345` → 201, `12-345` → 400, `abc-def` → 400 via Testcontainers. 17 `BsbValidatorTest` unit tests. |
| 2 | Transfer between two accounts commits debit + credit + outbox row in a single PostgreSQL transaction | VERIFIED | `TransferService.executeTransfer` is `@Transactional`; debit, credit, and `outboxEventRepository.save()` all within one method body, no intermediate commits. `TransferServiceIT.executeTransfer_success_*` verifies balances and `outboxAfter == outboxBefore + 1` via `JdbcTemplate`. Insufficient-funds path verifies rollback: zero outbox rows created, balances unchanged. |
| 3 | Duplicate transfer request with same idempotency key returns original cached response without executing a second debit | VERIFIED | `IdempotencyService` uses `StringRedisTemplate.setIfAbsent()` with 24h TTL. `PaymentService.initiateTransfer` checks `getCached()` before creating any DB record. `PaymentControllerIT.postTransfer_duplicateIdempotencyKey_returns200` asserts HTTP 200, same `transferId`, and `AccountServiceClient` mock called only once. 4 `IdempotencyServiceTest` integration tests cover setIfNew true/false and getCached present/empty. |
| 4 | payment-service state machine transitions PENDING → PAYMENT_PROCESSING → PAYMENT_DONE → POSTING → CONFIRMED for success, and to COMPENSATING → CANCELLED on failed debit | VERIFIED | `PaymentService.initiateTransfer` applies `INITIATE`, `PAYMENT_COMPLETE`, `POST`, `CONFIRM` events sequentially. Catch block applies `FAIL` then `COMPENSATE`. `TransferStateMachine` is a non-stub `@Component` with an immutable transition table covering all 7 states. 9 `TransferStateMachineTest` unit tests cover happy path, compensation from PAYMENT_PROCESSING/PAYMENT_DONE/POSTING, terminal state enforcement, and all 7 states present. `PaymentControllerIT.postTransfer_newTransfer_returns201` asserts final state is `"CONFIRMED"`. |

**Score:** 4/4 truths verified

---

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `common/src/main/java/au/com/bankforge/common/statemachine/TransferStateMachine.java` | Enum FSM with 7-state transition table | VERIFIED | 77 lines; immutable `Map<TransferState, Map<TransferEvent, TransferState>>` covering all transitions including PAYMENT_DONE + FAIL edge case. `@Component`. |
| `common/src/main/java/au/com/bankforge/common/validation/BsbValidator.java` | `ConstraintValidator` with `^\d{3}-\d{3}$` | VERIFIED | 27 lines; regex compiled to static final `Pattern`; null rejection explicit. |
| `account-service/src/main/java/au/com/bankforge/account/service/TransferService.java` | `@Transactional` debit+credit+outbox | VERIFIED | 111 lines; `@Transactional`, UUID lock ordering via `compareTo`, PESSIMISTIC_WRITE via `findByIdForUpdate`, balance check post-lock, outbox write in same transaction. |
| `account-service/src/main/java/au/com/bankforge/account/repository/AccountRepository.java` | `@Lock(PESSIMISTIC_WRITE)` `findByIdForUpdate` | VERIFIED | `@Lock(LockModeType.PESSIMISTIC_WRITE)` + `@Query` for explicit SELECT FOR UPDATE. |
| `account-service/src/main/resources/db/migration/V1__create_account_tables.sql` | accounts table with `DECIMAL(15,4)` balance | VERIFIED | `DECIMAL(15,4)` balance column; `UNIQUE` index on `(bsb, account_number)`. |
| `account-service/src/main/resources/db/migration/V2__create_outbox_table.sql` | Debezium-compatible outbox with JSONB payload | VERIFIED | Columns: `aggregatetype`, `aggregateid`, `type`, `payload JSONB`, `created_at`. Indexes on `aggregatetype` and `aggregateid` for Debezium EventRouter. |
| `payment-service/src/main/java/au/com/bankforge/payment/service/IdempotencyService.java` | `StringRedisTemplate.setIfAbsent()` with 24h TTL | VERIFIED | 53 lines; `setIfAbsent(PREFIX + key, responseJson, TTL)` where `TTL = Duration.ofHours(24)`; `getCached` uses `opsForValue().get()`. |
| `payment-service/src/main/java/au/com/bankforge/payment/service/PaymentService.java` | Full transfer lifecycle orchestration | VERIFIED | 176 lines; all 5 state transitions applied in sequence; compensation path in catch block; Redis cache written after successful or failed run. |
| `ledger-service/src/main/java/au/com/bankforge/ledger/controller/HealthController.java` | GET /api/health returning UP | VERIFIED | Returns `Map.of("service","ledger-service","status","UP","timestamp",...)`. |
| `notification-service/src/main/java/au/com/bankforge/notification/controller/HealthController.java` | GET /api/health returning UP | VERIFIED | Returns `Map.of("service","notification-service","status","UP","timestamp",...)`. |
| `ledger-service/src/main/resources/db/migration/V1__create_ledger_tables.sql` | `ledger_entries` table with `DECIMAL(15,4)` | VERIFIED | Double-entry schema with `entry_type` (DEBIT/CREDIT), `DECIMAL(15,4)` amount, two indexes. |
| `compose.yml` | 4x PostgreSQL 17 with `wal_level=logical` + Redis 7.2 | VERIFIED | All 4 PostgreSQL services pass `-c wal_level=logical -c max_replication_slots=5 -c max_wal_senders=5` as `command:` args. Redis 7.2-alpine. `service_healthy` depends_on for ordering. |

---

### Key Link Verification

| From | To | Via | Status | Details |
|------|----|-----|--------|---------|
| `AccountController` | `AccountService` | `@RequiredArgsConstructor` injection | VERIFIED | `createAccount`, `getAccount`, `getTransferHistory` delegated to service |
| `TransferService` | `AccountRepository.findByIdForUpdate` | `PESSIMISTIC_WRITE` | VERIFIED | Both lock acquisitions use `findByIdForUpdate`; balance check follows lock |
| `TransferService` | `OutboxEventRepository.save` | Same `@Transactional` | VERIFIED | `outboxEventRepository.save(outbox)` within the same method body as debit/credit; no intermediate flush |
| `PaymentService` | `TransferStateMachine.transition` | `@Autowired` via `@RequiredArgsConstructor` | VERIFIED | 5 transition calls in happy path; 2 in compensation path |
| `PaymentService` | `IdempotencyService` | `@RequiredArgsConstructor` | VERIFIED | `getCached()` called before DB record creation; `setIfNew()` called after response built |
| `PaymentService` | `AccountServiceClient.executeTransfer` | `RestClient` + `@Qualifier("accountRestClient")` | VERIFIED | Bean named `accountRestClient` avoids `BeanDefinitionOverrideException`; `@Qualifier` on constructor param |
| `compose.yml account-service` | `account-db` | `depends_on: service_healthy` | VERIFIED | `condition: service_healthy`; health check `pg_isready -U account -d accountdb` |
| `compose.yml payment-service` | `redis` | `depends_on: service_healthy` | VERIFIED | `condition: service_healthy`; `redis-cli ping` health check |

---

### Data-Flow Trace (Level 4)

| Artifact | Data Variable | Source | Produces Real Data | Status |
|----------|--------------|--------|-------------------|--------|
| `TransferService.executeTransfer` | `debit.balance`, `credit.balance` | `accountRepository.findByIdForUpdate()` — JPA SELECT FOR UPDATE against PostgreSQL | Yes — live DB query with pessimistic lock | FLOWING |
| `TransferService` outbox row | `outbox` | `outboxEventRepository.save()` — JPA INSERT into `outbox_event` | Yes — JSON payload serialized from `TransferCreatedEvent` record | FLOWING |
| `IdempotencyService` | `cached` | `StringRedisTemplate.opsForValue().get(PREFIX + key)` — live Redis GET | Yes — Redis returns stored JSON or null | FLOWING |
| `AccountService.getTransferHistory` | `history` | `OutboxEventRepository.findTransfersByAccountId` — native JSONB query on `outbox_event.payload` | Yes — JSONB operator query against real DB rows | FLOWING |

---

### Behavioral Spot-Checks

Step 7b: SKIPPED — services require running Podman Compose stack (already human-verified by developer in Plan 04 Task 3). No runnable entry points available for automated spot-checks in this environment.

Human verification gate in Plan 04 covered:
- Alice → Bob $250 transfer: state CONFIRMED, balances updated correctly
- Duplicate idempotency key: same `transferId` returned, no second debit
- Outbox row: `Transfer/TransferInitiated` row present in `outbox_event` after transfer
- BSB validation: valid BSB 201, invalid BSB 400
- `wal_level=logical` confirmed on account-db via `SHOW wal_level`
- All 4 service health endpoints UP
- 9 containers running (`podman ps`)

---

### Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
|-------------|------------|-------------|--------|----------|
| CORE-01 | 01-01, 01-02 | REST API for creating accounts, checking balances, listing transfer history | SATISFIED | `AccountController`: `POST /api/accounts`, `GET /{id}`, `GET /{id}/transfers`. `AccountControllerIT` 5 tests pass. |
| CORE-02 | 01-01, 01-02 | Atomic transfers: debit + credit + outbox in one PostgreSQL transaction | SATISFIED | `TransferService.executeTransfer` `@Transactional`; `TransferServiceIT` verifies ACID atomicity including rollback on insufficient funds. |
| CORE-03 | 01-01, 01-03 | payment-service REST API for initiating NPP-style payment flows | SATISFIED | `PaymentController`: `POST /api/payments/transfers`, `GET /api/payments/transfers/{id}`. `PaymentControllerIT` 4 tests pass. |
| TXNS-01 | 01-02 | Debit, credit, outbox in single local ACID PostgreSQL transaction | SATISFIED | `@Transactional` on `executeTransfer`; `JdbcTemplate` verification in `TransferServiceIT` confirms exactly 1 outbox row and correct balances, and 0 rows on rollback. |
| TXNS-04 | 01-01, 01-03 | State machine: PENDING → PAYMENT_PROCESSING → PAYMENT_DONE → CONFIRMED / COMPENSATING → CANCELLED | SATISFIED | `TransferStateMachine` with 7 states, 9 unit tests covering full lifecycle including all compensation paths. `PaymentService` applies all transitions. `PaymentControllerIT` asserts final state `"CONFIRMED"`. |
| TXNS-05 | 01-03 | Redis idempotency keys (24h TTL) — duplicate requests return cached response | SATISFIED | `IdempotencyService` uses `setIfAbsent` with `Duration.ofHours(24)`. `PaymentControllerIT.postTransfer_duplicateIdempotencyKey_returns200` asserts HTTP 200 and matching `transferId`. 4 `IdempotencyServiceTest` integration tests pass. |

All 6 Phase 1 requirements SATISFIED. No orphaned requirements.

---

### Anti-Patterns Found

No TODO/FIXME/PLACEHOLDER comments in main source files. No `return null`, `return []`, or `return {}` anti-patterns in service or controller code. No `System.out.println` or `e.printStackTrace()` in main sources.

**Intentional stubs documented in SUMMARY.md:**

| File | Pattern | Severity | Impact |
|------|---------|----------|--------|
| `PaymentService.java` lines 96-103 | Auto-confirms after POSTING without waiting for ledger event | INFO | Intentional for Phase 1. Phase 1.1 makes POSTING → CONFIRMED event-driven via Kafka. Does not affect Phase 1 success criteria. |
| `ledger-service` | `LedgerEntry` rows never populated in Phase 1 | INFO | Intentional stub. Table exists and Flyway runs. Phase 1.1 wires Kafka consumer. Health endpoint sufficient for Phase 1 SC. |
| `notification-service` | No DB, no Kafka consumer in Phase 1 | INFO | Intentional stub. Health endpoint sufficient for Phase 1 SC. |

None of these stubs block any Phase 1 success criterion. All three are explicitly deferred to Phase 1.1.

---

### Human Verification Required

All human verification was completed by the developer in Plan 04 Task 3. The developer confirmed all 9 verification checks:

1. Alice → Bob $250 transfer: `state = CONFIRMED`, balances updated correctly (Alice -$250, Bob +$250)
2. Duplicate idempotency key: same `transferId` returned, no second debit executed
3. Outbox row: `Transfer/TransferInitiated` row present in `outbox_event` after transfer
4. BSB validation: valid BSB `200` → 201, invalid BSB `abc` → 400
5. `wal_level=logical` confirmed on account-db
6. ledger-service GET /api/health → `{"status":"UP"}`
7. notification-service GET /api/health → `{"status":"UP"}`
8. All 9 containers running (`podman ps`)
9. `mvn verify` exits 0 — 46 tests pass across all modules

No additional human verification items identified by this automated analysis.

---

### Gaps Summary

No gaps. All 4 roadmap success criteria verified against the codebase. All 6 Phase 1 requirements (CORE-01, CORE-02, CORE-03, TXNS-01, TXNS-04, TXNS-05) have implementation evidence and passing test coverage.

The three intentional stubs (PaymentService auto-confirm, ledger Kafka consumer, notification Kafka consumer) are explicitly deferred to Phase 1.1 per ROADMAP.md and documented in all four SUMMARY.md files. They do not affect Phase 1 goal achievement.

**Phase 1 goal: ACHIEVED.**

---

## Critical Constraints Verification (from ROADMAP.md)

| Constraint | Status | Evidence |
|------------|--------|----------|
| All monetary fields use `BigDecimal` in Java and `DECIMAL(15,4)` in PostgreSQL | VERIFIED | `Account.balance: BigDecimal`, `Transfer.amount: BigDecimal`, `LedgerEntry.amount: BigDecimal`. V1 migrations use `DECIMAL(15,4)` for all monetary columns. |
| Balance queries use `SELECT FOR UPDATE` (`PESSIMISTIC_WRITE`) | VERIFIED | `AccountRepository.findByIdForUpdate` annotated `@Lock(LockModeType.PESSIMISTIC_WRITE)`. `ConcurrentTransferIT` proves 10 concurrent $100 transfers from $500 balance: exactly 5 succeed, 5 fail, final balance $0 — no overdraft. |
| Outbox table created via Flyway migration (Debezium-ready) | VERIFIED | `V2__create_outbox_table.sql` creates `outbox_event` with `aggregatetype`, `aggregateid`, `type`, `payload JSONB`. Column names match Debezium EventRouter expectations. |
| PostgreSQL `wal_level=logical` set in Compose | VERIFIED | All 4 PostgreSQL services in `compose.yml` pass `-c wal_level=logical` as `command:` args. Human verification confirmed `SHOW wal_level` returns `logical` on account-db. |

---

_Verified: 2026-04-10T08:30:00Z_
_Verifier: Claude (gsd-verifier)_
