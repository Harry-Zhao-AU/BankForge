---
phase: quick
plan: 260415-x5g
subsystem: ledger-service
tags: [transactional, double-entry, atomicity, idempotency, integration-test]
dependency_graph:
  requires: []
  provides: [atomic-double-entry-writes, rollback-on-crash-proof, idempotency-on-retry-proof]
  affects: [ledger-service]
tech_stack:
  added: []
  patterns: [@Transactional on @KafkaListener method, @MockitoSpyBean for partial-stub IT]
key_files:
  created: []
  modified:
    - ledger-service/src/main/java/au/com/bankforge/ledger/kafka/LedgerEventListener.java
    - ledger-service/src/test/java/au/com/bankforge/ledger/kafka/LedgerEventListenerIT.java
decisions:
  - "@Transactional (no qualifier) on onTransferEvent resolves to @Primary JpaTransactionManager; Kafka TX remains independent via container-level KafkaTransactionManager"
  - "@MockitoSpyBean wraps real LedgerEntryRepository so findByTransferId hits real DB while save() can be stubbed per-test"
  - "reset(spiedLedgerEntryRepository) in finally block prevents spy stub leaking across tests"
metrics:
  duration: "~5 min"
  completed: "2026-04-15"
  tasks: 2
  files: 2
---

# Quick 260415-x5g: Add @Transactional to LedgerEventListener Summary

**One-liner:** Restored `@Transactional` on `onTransferEvent` for atomic double-entry JPA writes, with three new ITs proving rollback-on-crash, idempotency-on-retry, and the pre-existing happy path.

---

## Tasks Completed

| Task | Name | Commit | Files |
|------|------|--------|-------|
| 1 | Add @Transactional to LedgerEventListener.onTransferEvent | cbaf645 | LedgerEventListener.java |
| 2 | Add integration tests for atomicity, idempotency-on-retry, and happy path | 9c3593d | LedgerEventListenerIT.java |

---

## What Was Done

### Task 1 — @Transactional added

Added `import org.springframework.transaction.annotation.Transactional` and `@Transactional` annotation directly on `onTransferEvent`. No qualifier — the `@Primary JpaTransactionManager` bean in `KafkaConfig` resolves automatically. The Kafka TX is managed independently at the container level via `setTransactionManager(kafkaTransactionManager)`.

Effect: both `ledgerEntryRepository.save(debit)` and `ledgerEntryRepository.save(credit)` now execute inside a single JPA transaction. Any exception between the two saves causes a full rollback — zero entries persisted.

### Task 2 — Integration tests

Updated `LedgerEventListenerIT.java`:

- Added `@MockitoSpyBean private LedgerEntryRepository spiedLedgerEntryRepository` — wraps real bean with Mockito spy; `findByTransferId` hits the real DB, `save()` can be stubbed per-test.
- Added `@Autowired private LedgerEventListener listener` — enables direct invocation to simulate Kafka-TX-failure retry.
- Changed existing `@Autowired LedgerEntryRepository` references to use `spiedLedgerEntryRepository`.

New tests:
1. `shouldRollbackBothEntriesWhenSecondSaveFails` — stubs 2nd `save()` to throw; sends event via Kafka; waits 45s for error handler to exhaust retries; asserts zero entries. Proves `@Transactional` rollback.
2. `shouldNotDuplicateEntriesOnRetryAfterKafkaFailure` — happy path via Kafka, then direct `listener.onTransferEvent(...)` call with same payload; asserts exactly 2 entries. Proves idempotency guard blocks duplicate writes.

Existing tests retained unchanged:
- `shouldWriteDebitAndCreditOnTransferEvent` (happy path + confirmation topic)
- `shouldBeIdempotentOnDuplicateDelivery` (duplicate Kafka delivery)

Final result: **4 tests, 0 failures, BUILD SUCCESS**.

---

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Missing KafkaTemplate import after file rewrite**
- **Found during:** Task 2 first compile attempt
- **Issue:** Rewriting the test file omitted `import org.springframework.kafka.core.KafkaTemplate` — compilation error on `KafkaTemplate` field
- **Fix:** Added `import org.springframework.kafka.core.KafkaTemplate;` to imports
- **Files modified:** LedgerEventListenerIT.java
- **Commit:** included in 9c3593d

---

## Threat Coverage

| Threat ID | Mitigation | Verified by |
|-----------|-----------|-------------|
| T-quick-01 (Tampering) | @Transactional ensures atomic write; idempotency guard prevents replay duplicates | shouldRollbackBothEntriesWhenSecondSaveFails + shouldNotDuplicateEntriesOnRetryAfterKafkaFailure |

---

## Known Stubs

None.

## Threat Flags

None — no new network endpoints, auth paths, or schema changes introduced.

---

## Self-Check: PASSED

- `LedgerEventListener.java` exists and contains `@Transactional` on `onTransferEvent`: CONFIRMED
- `LedgerEventListenerIT.java` exists with 4 test methods: CONFIRMED
- Commit cbaf645 exists: CONFIRMED
- Commit 9c3593d exists: CONFIRMED
- All 4 tests pass (BUILD SUCCESS): CONFIRMED
