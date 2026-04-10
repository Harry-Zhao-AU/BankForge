---
phase: 1
slug: acid-core-cdc-pipeline
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-04-10
---

# Phase 1 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 + Testcontainers (PostgreSQL, Redis) |
| **Config file** | `pom.xml` (Maven Surefire plugin) |
| **Quick run command** | `mvn test -pl account-service,payment-service -q` |
| **Full suite command** | `mvn verify -q` |
| **Estimated runtime** | ~60 seconds (Testcontainers spin-up) |

---

## Sampling Rate

- **After every task commit:** Run `mvn test -pl account-service,payment-service -q`
- **After every plan wave:** Run `mvn verify -q`
- **Before `/gsd-verify-work`:** Full suite must be green
- **Max feedback latency:** 90 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| 1-01-01 | 01 | 1 | CORE-01 | — | BSB regex rejects malformed input | unit | `mvn test -pl account-service -Dtest=BsbValidatorTest -q` | ❌ W0 | ⬜ pending |
| 1-01-02 | 01 | 1 | CORE-02 | T-1-01 | Debit+credit+outbox in single TX; rollback on failure | integration | `mvn test -pl account-service -Dtest=TransferRepositoryIT -q` | ❌ W0 | ⬜ pending |
| 1-01-03 | 01 | 2 | TXNS-04 | — | State transitions correct; invalid transitions throw | unit | `mvn test -pl common -Dtest=TransferStateMachineTest -q` | ❌ W0 | ⬜ pending |
| 1-01-04 | 01 | 2 | TXNS-05 | T-1-02 | Duplicate idempotency key returns cached response; no second debit | integration | `mvn test -pl payment-service -Dtest=IdempotencyIT -q` | ❌ W0 | ⬜ pending |
| 1-01-05 | 01 | 3 | CORE-03 | — | payment-service transfer endpoint returns 200 with transfer ID | integration | `mvn test -pl payment-service -Dtest=TransferControllerIT -q` | ❌ W0 | ⬜ pending |
| 1-01-06 | 01 | 3 | TXNS-01 | T-1-01 | Concurrent transfers cannot overdraft account | integration | `mvn test -pl account-service -Dtest=ConcurrentTransferIT -q` | ❌ W0 | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] `account-service/src/test/java/.../BsbValidatorTest.java` — unit tests for BSB regex (NNN-NNN)
- [ ] `account-service/src/test/java/.../TransferRepositoryIT.java` — Testcontainers PostgreSQL, verifies atomic TX
- [ ] `account-service/src/test/java/.../ConcurrentTransferIT.java` — concurrent overdraft prevention
- [ ] `common/src/test/java/.../TransferStateMachineTest.java` — enum FSM transition table
- [ ] `payment-service/src/test/java/.../IdempotencyIT.java` — Testcontainers Redis, duplicate key test
- [ ] `payment-service/src/test/java/.../TransferControllerIT.java` — @SpringBootTest slice, happy path
- [ ] `*/src/test/resources/application-test.yml` — Testcontainers datasource overrides per service

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Podman Compose stack starts cleanly (`podman-compose up`) | CORE-01 | Container orchestration hard to automate in CI | Run `podman-compose up -d`, wait 30s, run `podman ps` — all 6 containers must show healthy |
| Outbox row visible in PostgreSQL after transfer | CORE-02 | DB inspection | `psql -c "SELECT * FROM outbox ORDER BY created_at DESC LIMIT 1"` after POST transfer |

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 90s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
