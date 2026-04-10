# State: BankForge

*Project memory — updated at every phase transition and plan completion*

---

## Project Reference

**Core Value:** A running, end-to-end system where every enterprise pattern (ACID, Saga, Outbox, mTLS, distributed tracing) is implemented and queryable via AI agent — proving the patterns work together, not just in theory.

**Current Focus:** Phase 1 — ACID Core + CDC Pipeline

**Total Phases:** 5

---

## Current Position

| Field | Value |
|-------|-------|
| Phase | 1 — ACID Core + CDC Pipeline |
| Plan | None started |
| Status | Not started |
| Phase progress | 0% |

```
Progress: Phase 1 [----------] 0%
Overall:  [----------] 0% (0/5 phases)
```

---

## Phase Status

| Phase | Name | Status | Plans | Completed |
|-------|------|--------|-------|-----------|
| 1 | ACID Core + CDC Pipeline | Not started | TBD | - |
| 2 | Observability | Not started | TBD | - |
| 3 | Service Mesh & Auth | Not started | TBD | - |
| 4 | Graph & RCA Foundation | Not started | TBD | - |
| 5 | AI Integration / MCP | Not started | TBD | - |

---

## Performance Metrics

| Metric | Value |
|--------|-------|
| Phases completed | 0/5 |
| Requirements delivered | 0/34 |
| Plans created | 0 |
| Plans completed | 0 |

---

## Accumulated Context

### Key Decisions Logged

| Decision | Rationale | Phase |
|----------|-----------|-------|
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

### Open Questions (Pre-Phase Blockers)

| Question | Blocks | Priority |
|----------|--------|----------|
| Is Spring Boot 4.0.x GA and stable? | Phase 1 start | CRITICAL — verify before writing any code; fall back to 3.4.x if needed |
| Does Podman rootless work with kind on this machine? | Phase 1 spike, Phase 3 | HIGH — validate in Phase 1 spike; switch to rootful if needed |
| Spring State Machine vs hand-rolled enum FSM? | Phase 1 design | MEDIUM — verify Spring Boot 4-compatible release before committing |
| CDC-only outbox vs polling outbox? | Phase 1 design | MEDIUM — decide once, document; CDC-only recommended |
| Exact OTel property key names for the Spring Boot version in use | Phase 2 | LOW — verify before wiring OTel Collector |
| Kong JWT plugin vs JWKS-based dynamic validation | Phase 3 | MEDIUM — built-in jwt plugin breaks on Keycloak key rotation |
| Istio version and Kubernetes compatibility matrix | Phase 3 | HIGH — check before installing Istio |
| Verify actual Prometheus Istio metric label names against running system | Phase 4 | HIGH — ETL must use real label names, not assumed ones |
| MCP Python SDK current version and transport API | Phase 5 | HIGH — SDK was evolving at research cutoff; pin in requirements.txt |

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

**Last session:** 2026-04-10 — Project initialized. ROADMAP.md and STATE.md created. 34 requirements mapped across 5 phases.

**Resume point:** Run `/gsd-plan-phase 1` to begin planning Phase 1 (ACID Core + CDC Pipeline).

**Context to carry forward:**
- Phase 1 has 12 requirements (CORE-01..05, TXNS-01..05, AUBN-01..02) — the heaviest phase
- Phase 1 includes a mandatory Podman + kind networking spike (success criterion 5) — this must pass before Phase 3 begins
- Phase 3 is highest-risk: Istio PERMISSIVE then STRICT, RS256 JWT, resource limits to prevent OOMKill
- Phase 4 ETL depends on Istio metrics being in Prometheus first — do not start ETL until traffic is flowing
- Phase 5 MCP tools should be implemented one at a time; `root_cause_analysis()` composite tool is built last

---

*State initialized: 2026-04-10*
*Last updated: 2026-04-10 after roadmap creation*
