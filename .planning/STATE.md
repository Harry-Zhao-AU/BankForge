---
gsd_state_version: 1.0
milestone: v1.0
milestone_name: milestone
status: unknown
last_updated: "2026-04-10T06:36:46.994Z"
progress:
  total_phases: 6
  completed_phases: 0
  total_plans: 4
  completed_plans: 2
  percent: 50
---

# State: BankForge

*Project memory — updated at every phase transition and plan completion*

---

## Project Reference

**Core Value:** A running, end-to-end system where every enterprise pattern (ACID, Saga, Outbox, mTLS, distributed tracing) is implemented and queryable via AI agent — proving the patterns work together, not just in theory.

**Current Focus:** Phase 01 — acid-core-cdc-pipeline

**Total Phases:** 5

---

## Current Position

Phase: 01 (acid-core-cdc-pipeline) — EXECUTING
Plan: 3 of 4
| Field | Value |
|-------|-------|
| Phase | 1 — ACID Core + CDC Pipeline |
| Plan | 01-01 COMPLETE; 01-02 COMPLETE; 01-03 next |
| Status | In progress |
| Phase progress | 50% (2/4 plans) |

```
Progress: Phase 1 [█████░░░░░] 50%
Overall:  [██░░░░░░░░] 20% (0/5 phases completed)
```

---

## Phase Status

| Phase | Name | Status | Plans | Completed |
|-------|------|--------|-------|-----------|
| 1 | Service Scaffold + Core Banking | Not started | TBD | - |
| 1.1 | CDC Pipeline + Compliance + Kind Spike | Not started | TBD | - |
| 2 | Observability | Not started | TBD | - |
| 3 | Service Mesh & Auth | Not started | TBD | - |
| 4 | Graph & RCA Foundation | Not started | TBD | - |
| 5 | AI Integration / MCP | Not started | TBD | - |

---

## Performance Metrics

| Metric | Value |
|--------|-------|
| Phases completed | 0/5 |
| Requirements delivered | 4/34 (CORE-01, CORE-02, CORE-03, TXNS-04) |
| Plans created | 4 |
| Plans completed | 1 |

| Plan | Duration | Tasks | Files |
|------|----------|-------|-------|
| Phase 01-acid-core-cdc-pipeline P01 | 13 min | 3 tasks | 29 files |
| Phase 01-acid-core-cdc-pipeline P02 | 15 | 3 tasks | 25 files |

## Accumulated Context

### Key Decisions Logged

| Decision | Rationale | Phase |
|----------|-----------|-------|
| Enum FSM used instead of Spring State Machine | Spring State Machine 4.0.1 targets Spring Framework 6.2.x only — incompatible with Spring Boot 4 / Spring Framework 7. Enum FSM is idiomatic substitute | Phase 1 P01 |
| PAYMENT_DONE + FAIL transition added | Covers catch-block compensation path: exception thrown after PAYMENT_COMPLETE fires but before POST is applied | Phase 1 P01 |
| Java/Maven from IntelliJ bundled tools | JBR 21.0.8 + Maven 3.9.9 found in IntelliJ Community Edition 2025.2.4 — no separate JDK/Maven installation required | Phase 1 P01 |
| Local ACID TX for transfers (not distributed saga) | Money movement must be atomic; partial debit/credit is unacceptable | Phase 1 |
| Outbox + Debezium CDC instead of direct Kafka publish | Eliminates dual-write problem; guaranteed at-least-once delivery | Phase 1 |
| Kafka in KRaft mode (no ZooKeeper) | ZooKeeper support removed in Kafka 3.8+; KRaft is production-ready since 3.3 | Phase 1 |
| `wal_level=logical` must be set on Day 1 | Debezium reads PostgreSQL WAL; default `wal_level=replica` produces zero events | Phase 1 |
| BigDecimal + DECIMAL(15,4) everywhere | IEEE 754 rounding produces cent errors; banking correctness requires exact arithmetic | Phase 1 |
| SELECT FOR UPDATE on balance queries | Prevents concurrent overdraft under Read Committed isolation | Phase 1 |
| DLQ (DLT topics) from Day 1 | Silent event loss is unacceptable for financial events; exponential backoff + DLQ required | Phase 1 |
| Istio: start PERMISSIVE, switch to STRICT | STRICT before sidecars are confirmed causes all traffic to drop; staged approach is safe | Phase 3 |
| Kong RS256 JWT validation (not HS256) | Keycloak issues RS256 tokens; HS256 config causes all API calls to return 401 | Phase 3 |
| Kong strips X-User-Id before injecting from JWT | Prevents header forgery by external clients | Phase 3 |
| Neo4j for service graph (not just Prometheus) | Cypher traversal enables RCA that PromQL cannot express | Phase 4 |
| ETL uses rate(metric[5m]) windows | Instantaneous values overwrite edges with unrepresentative spikes | Phase 4 |
| MCP server in Python (not Java) | Lighter footprint; Python MCP SDK is mature; AI tooling ecosystem | Phase 5 |
| All MCP write tools default dry_run=True | Claude must not corrupt transfer state without explicit override | Phase 5 |
| All MCP handlers async + httpx.AsyncClient | Synchronous handlers block all tool calls when one backend is slow | Phase 5 |
| RestClient replaces TestRestTemplate in integration tests | TestRestTemplate was removed in Spring Boot 4; RestClient (Spring 6.1+) is the standard replacement | Phase 1 P02 |
| @JdbcTypeCode(SqlTypes.JSON) required for Hibernate 7 JSONB binding | columnDefinition="jsonb" alone is insufficient in Hibernate 7 — explicit JDBC type annotation needed | Phase 1 P02 |
| Podman named pipe as DOCKER_HOST in Surefire env vars | npipe:////./pipe/docker_engine configured in pom.xml Surefire so mvn test works without manual export | Phase 1 P02 |

### Open Questions (Pre-Phase Blockers)

| Question | Blocks | Priority |
|----------|--------|----------|
| Is Spring Boot 4.0.x GA and stable? | Phase 1 start | CRITICAL — verify before writing any code; fall back to 3.4.x if needed |
| Does Podman rootless work with kind on this machine? | Phase 1 spike, Phase 3 | HIGH — validate in Phase 1 spike; switch to rootful if needed |
| Spring State Machine vs hand-rolled enum FSM? | Phase 1 design | RESOLVED — Spring State Machine 4.0.1 targets Spring Framework 6.2 only; incompatible with Spring Boot 4. Using enum FSM in common module. |
| CDC-only outbox vs polling outbox? | Phase 1 design | MEDIUM — decide once, document; CDC-only recommended |
| Exact OTel property key names for the Spring Boot version in use | Phase 2 | LOW — verify before wiring OTel Collector |
| Kong JWT plugin vs JWKS-based dynamic validation | Phase 3 | MEDIUM — built-in jwt plugin breaks on Keycloak key rotation |
| Istio version and Kubernetes compatibility matrix | Phase 3 | HIGH — check before installing Istio |
| Verify actual Prometheus Istio metric label names against running system | Phase 4 | HIGH — ETL must use real label names, not assumed ones |
| MCP Python SDK current version and transport API | Phase 5 | HIGH — SDK was evolving at research cutoff; pin in requirements.txt |

### Roadmap Evolution

- Phase 1.1 inserted after Phase 1: CDC Pipeline + Compliance + Kind Spike (INSERTED) — Phase 1 was too broad for a single research+plan cycle; split at the application/infrastructure boundary. Phase 1 = service scaffold + core banking (CORE-01..03, TXNS-01, TXNS-04..05). Phase 1.1 = Kafka KRaft + Debezium CDC + DLT + AUSTRAC + kind spike (CORE-04..05, TXNS-02..03, AUBN-01..02).

### Todos

- [ ] Verify Spring Boot 4.0.x GA status before writing first line of code
- [ ] Run Podman + kind networking spike in Phase 1 (validate before Phase 3)
- [ ] Set up `make clean` target that drops Debezium replication slots
- [ ] Write `port-forward.sh` script in Phase 3 for MCP connectivity from Windows host
- [ ] Verify Prometheus Istio metric label names before writing ETL (Phase 4)
- [ ] Pin MCP Python SDK version in `requirements.txt` before writing any tools (Phase 5)

### Blockers

None currently.

---

## Session Continuity

**Last session:** 2026-04-10T06:36:46.989Z

**Resume point:** Run `/gsd-execute-phase` for Plan 01-03 (payment-service implementation).

**Context to carry forward:**

- Phase 1 has 12 requirements (CORE-01..05, TXNS-01..05, AUBN-01..02) — the heaviest phase
- Phase 1 includes a mandatory Podman + kind networking spike (success criterion 5) — this must pass before Phase 3 begins
- Phase 3 is highest-risk: Istio PERMISSIVE then STRICT, RS256 JWT, resource limits to prevent OOMKill
- Phase 4 ETL depends on Istio metrics being in Prometheus first — do not start ETL until traffic is flowing
- Phase 5 MCP tools should be implemented one at a time; `root_cause_analysis()` composite tool is built last

---

*State initialized: 2026-04-10*
*Last updated: 2026-04-10 after plan 01-01 execution*
