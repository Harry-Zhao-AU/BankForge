# Phase 1: Service Scaffold + Core Banking — Context

**Gathered:** 2026-04-10
**Status:** Ready for planning
**Note:** Phase split 2026-04-10 — CDC pipeline, AUSTRAC compliance, and kind spike moved to Phase 1.1.

<domain>
## Phase Boundary

Four Spring Boot / Java 21 services running on Podman Compose: account-service, payment-service, ledger-service, notification-service. Delivers ACID money movement, BSB validation, transfer state machine, and Redis idempotency. The outbox table is created (for Phase 1.1 Debezium) but CDC is NOT wired in this phase.

This phase is Compose-only. No Kubernetes, no Istio, no Kong, no Keycloak. No Kafka, no Debezium in this phase.

</domain>

<decisions>
## Implementation Decisions

### Spring Boot Version
- **D-01:** Target Spring Boot 4.0.x. Researcher must verify the exact current GA patch version before writing any pom.xml — do NOT hardcode 4.0.5. If 4.0.x is not yet GA or has known critical bugs, fall back to 3.4.x. All plan features (virtual threads, built-in OTel, structured ECS logging) are available on both versions.

### Transfer Flow & Service Responsibilities
- **D-02:** External clients call **payment-service** as the entry point for all fund transfers. payment-service owns idempotency key checking (Redis) and the transfer state machine.
- **D-03:** payment-service calls **account-service** internally (service-to-service REST) to execute the ACID transaction (debit + credit + outbox write in one PostgreSQL TX). account-service is internal-only — not exposed to external clients for transfer operations.
- **D-04:** payment-service owns CORE-03 (NPP-style payment flow API) and TXNS-04 (state machine) and TXNS-05 (idempotency). account-service owns CORE-01 (account CRUD, balance, history), CORE-02 (atomic transfer execution), and TXNS-01 (ACID TX guarantee).

### Transfer State Machine
- **D-05:** Implement as a **hand-rolled enum FSM** — `TransferState` enum in the `common` module with a `TransferStateMachine` service using a `switch` expression for transitions. Spring State Machine library (org.springframework.statemachine) is incompatible with Spring Framework 7 / Spring Boot 4 and must NOT be used.
- **D-06:** State names use real-world banking terminology:
  ```
  PENDING → PAYMENT_PROCESSING → PAYMENT_DONE → POSTING → CONFIRMED
                                                      │
                                               COMPENSATING → CANCELLED
  ```
  `POSTING` = ledger-service is recording the double-entry bookkeeping entries (debit entry + credit entry). This replaces the e-commerce `STOCK_RESERVING` state from plan.md.

### Build Organisation
- **D-07:** Maven multi-module project. One parent `pom.xml` at the repo root with 5 child modules: `common`, `account-service`, `payment-service`, `ledger-service`, `notification-service`.
- **D-08:** `common` module holds: Kafka event DTOs, `TransferState` enum, shared validation utilities (BSB format). All 4 services declare a compile dependency on `common`. At deploy time each service produces its own fat JAR and runs as an independent container — multi-module is build-time convenience only.

### Locked Decisions (from STATE.md — do not re-ask)
- **D-09:** All monetary fields: `BigDecimal` in Java, `DECIMAL(15,4)` in PostgreSQL. No `double` or `float` anywhere.
- **D-10:** Balance queries use `SELECT FOR UPDATE` (`LockModeType.PESSIMISTIC_WRITE`) to prevent concurrent overdraft under Read Committed isolation.
- **D-11 (forward-compat):** PostgreSQL containers must start with `wal_level=logical` and `max_replication_slots=5` — set now so Phase 1.1 Debezium needs no DB restart. No CDC consumer is wired in this phase.
- **D-12 (deferred to Phase 1.1):** Kafka KRaft, Debezium CDC, DLT topics, and AUSTRAC logging are Phase 1.1 scope. Do not implement in Phase 1 plans.

### Claude's Discretion
- Exact Kafka topic naming convention (e.g. `banking.transfers.outbox` vs `transfer-events`) — follow a consistent pattern.
- Compose service startup ordering (`depends_on` + health checks) — implement correctly for PostgreSQL → Kafka → Debezium ordering.
- Exact Spring State Machine configuration style (annotation-based vs builder DSL) — use whichever is idiomatic for the Spring Boot version chosen.

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Project Definition
- `plan.md` — Original project plan. Treat as a draft; architecture decisions above override where they conflict. Transfer flow diagram (p.1), service table, state machine, project structure, and compose.yml hints are all useful starting points.
- `.planning/REQUIREMENTS.md` — Full requirement list for Phase 1: CORE-01..05, TXNS-01..05, AUBN-01..02. Success criteria are the acceptance tests.
- `.planning/ROADMAP.md §Phase 1` — Phase goal, critical constraints, and 5 success criteria that must all pass.

### Critical Constraints
- `CLAUDE.md §CRITICAL FLAG` — Spring Boot 4.0.5 version risk. Researcher must verify GA status before pinning version.
- `CLAUDE.md §FLAG 2` — Podman rootless + kind compatibility. Spike must run in Phase 1 (success criterion 5).
- `CLAUDE.md §FLAG 3` — Kafka KRaft mode (no ZooKeeper). Kafka 3.8+.
- `CLAUDE.md §FLAG 9` — PostgreSQL version: use 16 or 17, not 15. Debezium logical replication improvements.

### Technology Versions (verify before pinning)
- `CLAUDE.md §Recommended Stack` — Version table for all libraries. Cross-reference before writing any pom.xml or compose.yml.

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- None — greenfield project. No existing code.

### Established Patterns
- None yet — this phase establishes the patterns all subsequent phases follow.

### Integration Points
- `compose.yml` (to be created at repo root) — Phase 1 starts here. Phase 2 adds observability services to this same file.
- `plan.md` project structure — use as the directory layout reference for `services/`, `infrastructure/`, `k8s/`, `observability/`.

</code_context>

<specifics>
## Specific Ideas

- State machine state names were explicitly chosen to match real Australian banking terminology. `POSTING` = the act of recording double-entry ledger entries. Do not revert to `STOCK_RESERVING` or other e-commerce terminology.
- Maven multi-module is build-time convenience only. Each service must produce an independent fat JAR and run as an isolated container. The `common` module is embedded in each fat JAR — it is not a runtime service.
- payment-service is the external API surface for transfers. account-service is internal. This affects which service gets Kong routes in Phase 3.

</specifics>

<deferred>
## Deferred Ideas

None — discussion stayed within Phase 1 scope.

</deferred>

---

*Phase: 01-acid-core-cdc-pipeline*
*Context gathered: 2026-04-10*
