---
phase: 01-acid-core-cdc-pipeline
plan: "04"
subsystem: infra
tags: [spring-boot, podman-compose, e2e-verification, ledger-service, notification-service, flyway, jpa, health-endpoint, acid, idempotency, bsb-validation, outbox]

# Dependency graph
requires:
  - phase: 01-acid-core-cdc-pipeline
    plan: "01"
    provides: "compose.yml (4x postgres:17 wal_level=logical + Redis 7.2), service stubs"
  - phase: 01-acid-core-cdc-pipeline
    plan: "02"
    provides: "account-service: POST /api/accounts, POST /api/internal/transfers, GET /api/accounts/{id}"
  - phase: 01-acid-core-cdc-pipeline
    plan: "03"
    provides: "payment-service: POST /api/payments/transfers with state machine + Redis idempotency"
provides:
  - ledger-service stub: Flyway V1 migration (ledger_entries DECIMAL(15,4)), LedgerEntry JPA entity, LedgerEntryRepository, GET /api/health
  - notification-service stub: GET /api/health (no DB, no Kafka — ready for Phase 1.1 Kafka consumer wiring)
  - Human-verified Phase 1 end-to-end: all 4 services running on Podman Compose, transfer flow confirmed, idempotency confirmed, outbox confirmed
  - 46 tests passing across all modules (mvn verify exits 0)
  - PostgreSQL wal_level=logical confirmed ready for Phase 1.1 Debezium
affects:
  - 01.1 (ledger-service and notification-service stubs are the consumers wired up with Kafka in Phase 1.1)
  - 01.1 (wal_level=logical and outbox table confirmed in place; Debezium connector can be added without DB restart)

# Tech tracking
tech-stack:
  added:
    - "ledger-service: Flyway 11.14.1 migration for ledger_entries table"
    - "LedgerEntry JPA entity with BigDecimal amount (DECIMAL(15,4))"
  patterns:
    - "Pattern: Stub-first service scaffolding — health endpoint + Flyway schema added before Kafka consumer wiring (Phase 1.1)"
    - "Pattern: DECIMAL(15,4) for ledger amounts — consistent with account and transfer monetary fields"
    - "Pattern: Double-entry bookkeeping schema — entry_type DEBIT/CREDIT, one debit + one credit per transfer"

key-files:
  created:
    - ledger-service/src/main/java/au/com/bankforge/ledger/controller/HealthController.java
    - ledger-service/src/main/resources/db/migration/V1__create_ledger_tables.sql
    - ledger-service/src/main/java/au/com/bankforge/ledger/entity/LedgerEntry.java
    - ledger-service/src/main/java/au/com/bankforge/ledger/repository/LedgerEntryRepository.java
    - notification-service/src/main/java/au/com/bankforge/notification/controller/HealthController.java
  modified: []

key-decisions:
  - "ledger-service stub-first: schema and entity created now, Kafka consumer wired in Phase 1.1 — avoids blocking Phase 1 completion on event streaming"
  - "notification-service has no DB in Phase 1: only a health endpoint; no schema migration needed since it stores nothing in Phase 1"
  - "Human verification gate confirmed all 9 Phase 1 success criteria including wal_level=logical, idempotency, outbox row, and both stub health checks"

patterns-established:
  - "Pattern: Phase verification checkpoint — human verifies BSB validation, ACID transfer, idempotency, outbox, stub services before moving to next phase"
  - "Pattern: Double-entry bookkeeping table — ledger_entries with DEBIT/CREDIT entry_type, transfer_id and account_id FKs, two indexes"

requirements-completed: [CORE-01, CORE-02, CORE-03, TXNS-01, TXNS-04, TXNS-05]

# Metrics
duration: human-verify checkpoint
completed: "2026-04-10"
---

# Phase 01 Plan 04: Compose Stack Verification + Stub Services Summary

**Ledger-service and notification-service stubs added (health endpoints, ledger schema with DECIMAL(15,4), LedgerEntry JPA entity); full Podman Compose stack human-verified end-to-end — 9 containers running, 46 tests passing, ACID transfer confirmed, Redis idempotency confirmed, outbox row confirmed, wal_level=logical confirmed**

## Performance

- **Duration:** Human-verify checkpoint (Tasks 1-2 automated, Task 3 human gate)
- **Completed:** 2026-04-10T07:35:00Z
- **Tasks:** 3 (2 automated + 1 human-verify checkpoint)
- **Files created:** 5, modified: 0

## Accomplishments

- ledger-service now compiles and starts with: Flyway V1 schema (`ledger_entries` with `DECIMAL(15,4)` amount), `LedgerEntry` JPA entity, `LedgerEntryRepository`, and GET `/api/health` returning `{"service":"ledger-service","status":"UP"}`
- notification-service now compiles and starts with GET `/api/health` returning `{"service":"notification-service","status":"UP"}` — no DB dependency (Phase 1.1 adds Kafka consumer)
- Full Podman Compose stack validated: all 9 containers running (4x PostgreSQL 17 + Redis 7.2 + 4 services)
- `mvn verify` exits 0 — 46 tests pass across all modules
- End-to-end transfer flow verified: Alice → Bob $250, transfer state CONFIRMED, balances updated correctly
- Redis idempotency verified: duplicate idempotency key returns same transferId without re-executing
- Outbox row verified: `Transfer/TransferInitiated` row present in `outbox_event` table after transfer
- BSB validation verified: valid BSB returns 201, invalid BSB returns 400
- PostgreSQL `wal_level=logical` confirmed on account-db — Debezium-ready for Phase 1.1

## Task Commits

Each task was committed atomically:

1. **Task 1: Ledger-service and notification-service stubs with health endpoints and ledger schema** - `d8b3b4f` (feat)
2. **Task 2: Build all service JARs, start Compose stack (9 containers running)** - `5965fcb` (feat)
3. **Task 3: End-to-end Phase 1 verification checkpoint** — human-verify gate; no commit (verification only)

## Files Created/Modified

- `ledger-service/src/main/java/au/com/bankforge/ledger/controller/HealthController.java` — GET /api/health returning service name, status UP, timestamp
- `ledger-service/src/main/resources/db/migration/V1__create_ledger_tables.sql` — `ledger_entries` table: UUID PK, transfer_id, account_id, entry_type (DEBIT/CREDIT), `DECIMAL(15,4)` amount, currency (AUD default), description, created_at; two indexes
- `ledger-service/src/main/java/au/com/bankforge/ledger/entity/LedgerEntry.java` — JPA entity with `BigDecimal` amount (precision=15, scale=4); Lombok @Getter/@Setter/@Builder/@NoArgsConstructor/@AllArgsConstructor
- `ledger-service/src/main/java/au/com/bankforge/ledger/repository/LedgerEntryRepository.java` — JpaRepository<LedgerEntry, UUID> with findByTransferId and findByAccountId
- `notification-service/src/main/java/au/com/bankforge/notification/controller/HealthController.java` — GET /api/health returning service name, status UP, timestamp

## Decisions Made

- **ledger-service stub-first approach:** The ledger schema and JPA entity are created now (in Phase 1) so the table exists and Flyway can run at startup. The actual Kafka consumer that populates rows will be wired in Phase 1.1. This ensures the Compose stack starts cleanly without empty-migration failures.
- **notification-service has no DB in Phase 1:** It stores nothing in Phase 1, so no Flyway migration is needed. A health endpoint is sufficient to satisfy Phase 1 success criteria. Phase 1.1 will add the Kafka consumer configuration.
- **Human verification gate approved:** The user confirmed all 9 verification checks pass including the full E2E transfer flow, idempotency, outbox, BSB validation, stub health endpoints, and wal_level=logical.

## Deviations from Plan

None — plan executed exactly as written. Tasks 1 and 2 were automated; Task 3 was a human-verify checkpoint that was approved by the user.

## Known Stubs

- `ledger-service`: `LedgerEntry` rows are never populated in Phase 1. The table exists and Flyway runs, but no Kafka consumer writes to it. Phase 1.1 wires the Kafka consumer. This is intentional — the plan explicitly states "stub ready for Phase 1.1 event consumption."
- `notification-service`: No DB, no Kafka consumer in Phase 1. Health endpoint only. Phase 1.1 wires the Kafka consumer. This is intentional per the plan.
- `PaymentService.java` (from Plan 03): Auto-confirms after POSTING (POSTING → CONFIRMED without waiting for ledger event). Intentional for Phase 1; Phase 1.1 makes this event-driven.

These stubs do not block Phase 1's goal (all four services run, ACID transfer works, idempotency works). Phase 1.1 resolves all three.

## Threat Surface Scan

No new security-relevant surface beyond what was in the plan's threat model. All T-1-11 through T-1-14 mitigations accepted as per plan:
- T-1-11: Plain-text DB passwords in compose.yml — accepted for local dev; Phase 3 moves to Kubernetes Secrets
- T-1-12: Service ports exposed on localhost — accepted for local dev; Phase 3 Kong gateway removes direct exposure
- T-1-13: No rate limiting — accepted; Phase 3 adds Kong rate limiting
- T-1-14: No authentication — accepted for Phase 1; Phase 3 adds Keycloak JWT + Kong validation

## Phase 1 Completion

All Phase 1 success criteria are now met:

| Criterion | Status |
|-----------|--------|
| BSB validation: valid accepted, invalid rejected | CONFIRMED |
| ACID transfer: debit + credit + outbox in single TX | CONFIRMED |
| Duplicate idempotency key returns cached response | CONFIRMED |
| State machine: PENDING → PAYMENT_PROCESSING → PAYMENT_DONE → POSTING → CONFIRMED | CONFIRMED |
| wal_level=logical on account-db | CONFIRMED |
| ledger-service health check UP | CONFIRMED |
| notification-service health check UP | CONFIRMED |
| Outbox row present after transfer | CONFIRMED |
| All 9 containers running | CONFIRMED |
| 46 tests passing (mvn verify exits 0) | CONFIRMED |

**Requirements delivered in Phase 1:** CORE-01, CORE-02, CORE-03, TXNS-01, TXNS-04, TXNS-05

## Next Phase Readiness

**Ready for Phase 1.1 (CDC Pipeline + Compliance + Kind Spike):**
- `wal_level=logical` set from Day 1; no DB restart needed to add Debezium
- `outbox_event` table schema locked in account-service V2 migration; Debezium EventRouter will read `aggregatetype`, `aggregateid`, `type`, `payload` columns
- ledger-service stub ready to receive Kafka consumer wiring (ledger_entries table exists)
- notification-service stub ready to receive Kafka consumer wiring (no schema changes needed)
- All 4 services compile and run cleanly in Compose
- Kafka KRaft + Debezium is Phase 1.1's first task — can start immediately

**Key constraints for Phase 1.1:**
- Kafka must be KRaft mode (`KAFKA_PROCESS_ROLES=broker,controller`) — no ZooKeeper
- Debezium slot leak prevention: `slot.drop.on.stop=true` in connector config
- DLT topics must be configured from Day 1 — no silent event loss for financial events
- kind + Podman networking spike must pass before Phase 3 begins

## Self-Check: PASSED

- `d8b3b4f` — verified in git log (Task 1 commit)
- `5965fcb` — verified in git log (Task 2 commit)
- `ledger-service/src/main/resources/db/migration/V1__create_ledger_tables.sql` — created in Task 1
- `ledger-service/src/main/java/au/com/bankforge/ledger/entity/LedgerEntry.java` — created in Task 1
- `notification-service/src/main/java/au/com/bankforge/notification/controller/HealthController.java` — created in Task 1

---
*Phase: 01-acid-core-cdc-pipeline*
*Completed: 2026-04-10*
