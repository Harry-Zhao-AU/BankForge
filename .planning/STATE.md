---
gsd_state_version: 1.0
milestone: v1.0
milestone_name: milestone
status: unknown
last_updated: "2026-04-10T09:00:00.000Z"
progress:
  total_phases: 6
  completed_phases: 1
  total_plans: 7
  completed_plans: 4
  percent: 57
---

# State: BankForge

*Project memory — updated at every phase transition and plan completion*

---

## Project Reference

**Core Value:** A running, end-to-end system where every enterprise pattern (ACID, Saga, Outbox, mTLS, distributed tracing) is implemented and queryable via AI agent — proving the patterns work together, not just in theory.

**Current Focus:** Phase 1.1 — CDC Pipeline + Compliance + Kind Spike

**Total Phases:** 6

---

## Current Position

Phase: 01 (acid-core-cdc-pipeline) — COMPLETE
Plan: 4 of 4 — ALL PLANS COMPLETE
| Field | Value |
|-------|-------|
| Phase | 1 — ACID Core + CDC Pipeline |
| Plan | 01-01 COMPLETE; 01-02 COMPLETE; 01-03 COMPLETE; 01-04 COMPLETE |
| Status | Phase 1 complete — ready for Phase 1.1 |
| Phase progress | 100% (4/4 plans) |

```
Progress: Phase 1 [██████████] 100%
Overall:  [██░░░░░░░░] ~17% (1/6 phases completed)
```

---

## Phase Status

| Phase | Name | Status | Plans | Completed |
|-------|------|--------|-------|-----------|
| 1 | Service Scaffold + Core Banking | COMPLETE | 4/4 | 2026-04-10 |
| 1.1 | CDC Pipeline + Compliance + Kind Spike | Ready to execute | 3/3 planned | - |
| 2 | Observability | Not started | TBD | - |
| 3 | Service Mesh & Auth | Not started | TBD | - |
| 4 | Graph & RCA Foundation | Not started | TBD | - |
| 5 | AI Integration / MCP | Not started | TBD | - |

---

## Performance Metrics

| Metric | Value |
|--------|-------|
| Phases completed | 1/6 |
| Requirements delivered | 6/34 (CORE-01, CORE-02, CORE-03, TXNS-01, TXNS-04, TXNS-05) |
| Plans created | 7 |
| Plans completed | 4 |

| Plan | Duration | Tasks | Files |
|------|----------|-------|-------|
| Phase 01-acid-core-cdc-pipeline P01 | 13 min | 3 tasks | 29 files |
| Phase 01-acid-core-cdc-pipeline P02 | 15 | 3 tasks | 25 files |
| Phase 01-acid-core-cdc-pipeline P03 | 14 | 3 tasks | 19 files |
| Phase 01-acid-core-cdc-pipeline P04 | human-verify | 3 tasks | 5 files |

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
| @MockitoBean replaces @MockBean in Spring Boot 4 | @MockBean was removed from spring-boot-test 4.0.x; @MockitoBean from org.springframework.test.context.bean.override.mockito (spring-test 7.0.6) is the direct replacement | Phase 1 P03 |
| RestClient @Bean named "accountRestClient" (not "accountServiceClient") | @Bean method name matching @Component class name causes BeanDefinitionOverrideException; explicit name + @Qualifier injection required | Phase 1 P03 |
| isNewTransfer() called in controller before initiateTransfer() | Captures Redis idempotency state at request arrival for correct 201 vs 200 HTTP status; if checked inside service, both new and replay would see key already set | Phase 1 P03 |

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

**Last session:** 2026-04-10T09:00:00.000Z

**Resume point:** Phase 1.1 planned (3 plans, verified). Run `/gsd-execute-phase 1.1` to begin execution.

**Context to carry forward:**

- Phase 1.1 has 3 plans: 01.1-01 (Kafka+Debezium), 01.1-02 (AUSTRAC), 01.1-03 (kind spike)
- Plans 01 and 03 are Wave 1 (can run in parallel); Plan 02 depends on Plan 01
- AUBN-01 (BSB validation) is already done — Phase 1.1 only adds regression check, no re-implementation
- Debezium uses `quay.io/debezium/connect:3.1` (Docker Hub gone since 2.7)
- Kafka uses `apache/kafka:3.9.2` with native `KAFKA_*` env vars (not bitnami)
- Connector uses `topic.prefix` (not `database.server.name`); `table.include.list` (not whitelist)
- `aggregatetype` must be lowercase `"transfer"` (not `"Transfer"`) — EventRouter case-sensitive topic routing
- kind spike (Plan 03): kind+kubectl must be installed in WSL2; Podman machine is already rootful
- Phase 3 is highest-risk: Istio PERMISSIVE then STRICT, RS256 JWT, resource limits to prevent OOMKill
- Phase 4 ETL depends on Istio metrics being in Prometheus first — do not start ETL until traffic is flowing
- @MockBean is GONE in Spring Boot 4 — always use @MockitoBean / @SpyBean
- RestClient @Bean names must not match any @Component class name in scan path — name explicitly

---

*State initialized: 2026-04-10*
*Last updated: 2026-04-10 after Phase 1.1 planning — 3 plans ready to execute*
