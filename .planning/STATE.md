---
gsd_state_version: 1.0
milestone: v1.0
milestone_name: milestone
status: unknown
last_updated: "2026-04-16T12:22:08.397Z"
progress:
  total_phases: 6
  completed_phases: 3
  total_plans: 10
  completed_plans: 10
  percent: 100
---

# State: BankForge

*Project memory — updated at every phase transition and plan completion*

---

## Project Reference

**Core Value:** A running, end-to-end system where every enterprise pattern (ACID, Saga, Outbox, mTLS, distributed tracing) is implemented and queryable via AI agent — proving the patterns work together, not just in theory.

**Current Focus:** Phase 3 — Service Mesh & Auth (next up)

**Total Phases:** 6

---

## Current Position

Phase: 02
| Field | Value |
|-------|-------|
| Phase | 2 — Observability |
| Plan | 02-01 COMPLETE; 02-02 COMPLETE; 02-03 COMPLETE |
| Status | Phase 2 complete — ready for Phase 3 |
| Phase progress | 100% (3/3 plans) |

```
Progress: Phase 2 [██████████] 100%
Overall:  [████░░░░░░] ~35% (phases 1, 1.1, 2 complete)
```

---

## Phase Status

| Phase | Name | Status | Plans | Completed |
|-------|------|--------|-------|-----------|
| 1 | Service Scaffold + Core Banking | COMPLETE | 4/4 | 2026-04-10 |
| 1.1 | CDC Pipeline + Compliance + Kind Spike | COMPLETE | 3/3 | 2026-04-11 |
| 2 | Observability | COMPLETE | 3/3 | 2026-04-13 |
| 3 | Service Mesh & Auth | Not started | TBD | - |
| 4 | Graph & RCA Foundation | Not started | TBD | - |
| 5 | AI Integration / MCP | Not started | TBD | - |

---

## Performance Metrics

| Metric | Value |
|--------|-------|
| Phases completed | 3/6 (1, 1.1, 2) |
| Requirements delivered | 11/34 (CORE-01..05, TXNS-01..03, AUBN-01..02, OBS-01..05) |
| Plans created | 10 |
| Plans completed | 10 |

| Plan | Duration | Tasks | Files |
|------|----------|-------|-------|
| Phase 01-acid-core-cdc-pipeline P01 | 13 min | 3 tasks | 29 files |
| Phase 01-acid-core-cdc-pipeline P02 | 15 min | 3 tasks | 25 files |
| Phase 01-acid-core-cdc-pipeline P03 | 14 min | 3 tasks | 19 files |
| Phase 01-acid-core-cdc-pipeline P04 | human-verify | 3 tasks | 5 files |
| Phase 01.1 P01 | agent | 3 tasks | — |
| Phase 01.1 P02 | agent | 3 tasks | — |
| Phase 01.1 P03 | agent | 3 tasks | — |
| Phase 02-observability P01 | agent | 3 tasks | 7 files |
| Phase 02-observability P02 | agent | 4 tasks | 23 files |
| Phase 02-observability P03 | multi-session | 2 tasks | 2 files |

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
| kind + Podman rootful networking spike: PASSED | Validated single-node kind cluster (bankforge-spike), pod deployment, and DNS resolution (kubernetes.default.svc.cluster.local → 10.96.0.1) via KIND_EXPERIMENTAL_PROVIDER=podman. WSL2 workarounds required: kind network with --disable-dns (prevents aardvark-dns failure), root Podman log_driver=k8s-file (required for podman logs / systemd detection). Tool versions: kind v0.27.0, kubectl v1.35.3, Podman v5.8.1. Phase 3 prerequisite: SATISFIED. | Phase 1.1 P03 |
| @MockitoBean replaces @MockBean in Spring Boot 4 | @MockBean was removed from spring-boot-test 4.0.x; @MockitoBean from org.springframework.test.context.bean.override.mockito (spring-test 7.0.6) is the direct replacement | Phase 1 P03 |
| RestClient @Bean named "accountRestClient" (not "accountServiceClient") | @Bean method name matching @Component class name causes BeanDefinitionOverrideException; explicit name + @Qualifier injection required | Phase 1 P03 |
| isNewTransfer() called in controller before initiateTransfer() | Captures Redis idempotency state at request arrival for correct 201 vs 200 HTTP status; if checked inside service, both new and replay would see key already set | Phase 1 P03 |
| Grafana latency panel unit must be `ms` not `s` | transfer_duration_milliseconds_bucket le values are in milliseconds (Micrometer Timer base unit). Using `s` displays 44.7ms as "44.7 seconds". | Phase 2 P03 |
| Debezium connector must be re-registered after every stack restart | infra/debezium/outbox-connector.json is not auto-applied on `podman compose up`. Without it, ledger/notification receive no Kafka events → no traces or Loki logs. Register with: `curl -X POST http://localhost:8085/connectors -d @infra/debezium/outbox-connector.json` | Phase 2 P03 |
| Idle services absent from Loki until first log event | OTel log appender only exports on log events. Services appear in Loki after first request or Kafka message — not a configuration issue, just warmup behaviour. | Phase 2 P03 |
| CreateAccountRequest uses `initialBalance` field (not `balance`) | API field name is `initialBalance`; sending `balance` silently ignores the value and creates account with 0 balance. | Phase 2 P03 |
| POSTING→CONFIRMED is event-driven via banking.transfer.confirmed | ledger-service publishes confirmation after writing debit+credit pair; payment-service @KafkaListener (TransferConfirmationListener) calls TransferStateService.confirm() in REQUIRES_NEW tx. HTTP response returns POSTING; CONFIRMED arrives async. | Quick 260413-vd1 |

### Open Questions (Pre-Phase Blockers)

| Question | Blocks | Priority |
|----------|--------|----------|
| Is Spring Boot 4.0.x GA and stable? | Phase 1 start | CRITICAL — verify before writing any code; fall back to 3.4.x if needed |
| Does Podman rootless work with kind on this machine? | Phase 1 spike, Phase 3 | RESOLVED — rootful Podman works. WSL2 workarounds: kind network --disable-dns + k8s-file log driver. See Key Decisions table. |
| Spring State Machine vs hand-rolled enum FSM? | Phase 1 design | RESOLVED — Spring State Machine 4.0.1 targets Spring Framework 6.2 only; incompatible with Spring Boot 4. Using enum FSM in common module. |
| CDC-only outbox vs polling outbox? | Phase 1 design | MEDIUM — decide once, document; CDC-only recommended |
| Exact OTel property key names for the Spring Boot version in use | Phase 2 | RESOLVED — correct keys: management.opentelemetry.tracing.export.otlp.endpoint, management.opentelemetry.logging.export.otlp.endpoint, management.otlp.metrics.export.url |
| Kong JWT plugin vs JWKS-based dynamic validation | Phase 3 | MEDIUM — built-in jwt plugin breaks on Keycloak key rotation |
| Istio version and Kubernetes compatibility matrix | Phase 3 | HIGH — check before installing Istio |
| Verify actual Prometheus Istio metric label names against running system | Phase 4 | HIGH — ETL must use real label names, not assumed ones |
| MCP Python SDK current version and transport API | Phase 5 | HIGH — SDK was evolving at research cutoff; pin in requirements.txt |

### Roadmap Evolution

- Phase 1.1 inserted after Phase 1: CDC Pipeline + Compliance + Kind Spike (INSERTED) — Phase 1 was too broad for a single research+plan cycle; split at the application/infrastructure boundary. Phase 1 = service scaffold + core banking (CORE-01..03, TXNS-01, TXNS-04..05). Phase 1.1 = Kafka KRaft + Debezium CDC + DLT + AUSTRAC + kind spike (CORE-04..05, TXNS-02..03, AUBN-01..02).

### Todos

- [ ] Verify Spring Boot 4.0.x GA status before writing first line of code
- [x] Run Podman + kind networking spike in Phase 1.1 (validated — PASSED: kind v0.27.0, kubectl v1.35.3, Podman v5.8.1)
- [ ] Set up `make clean` target that drops Debezium replication slots
- [ ] Write `port-forward.sh` script in Phase 3 for MCP connectivity from Windows host
- [ ] Verify Prometheus Istio metric label names before writing ETL (Phase 4)
- [ ] Pin MCP Python SDK version in `requirements.txt` before writing any tools (Phase 5)

### Quick Tasks Completed

| # | Description | Date | Commit | Directory |
|---|-------------|------|--------|-----------|
| 260413-vd1 | Wire event-driven POSTING→CONFIRMED saga loop | 2026-04-13 | cbd84a5 | [260413-vd1](./quick/260413-vd1-wire-event-driven-posting-confirmed/) |
| 260415-w91 | Fix ledger-service dual-write risk: Kafka EOS + idempotency guard | 2026-04-15 | 24c9622 | [260415-w91](./quick/260415-w91-fix-ledger-service-dual-write-risk-add-k/) |
| 260415-x5g | Add @Transactional to LedgerEventListener for atomic double-entry + 3 ITs | 2026-04-15 | 9c3593d | [260415-x5g](./quick/260415-x5g-add-transactional-back-to-ledgereventlis/) |
| 260416-u6y | add the metric for all states, so I can see the transfer state in grafana without querying the db | 2026-04-16 | 7995b13 | [260416-u6y](./quick/260416-u6y-add-the-metric-for-all-states-so-i-can-s/) |

### Blockers

None currently.

---

## Session Continuity

**Last session:** 2026-04-15T00:00:00.000Z

**Resume point:** Phase 2 complete + event-driven saga loop wired + ledger @Transactional restored. Next: Phase 3 — Service Mesh & Auth. Run `/gsd-discuss-phase 3` or `/gsd-plan-phase 3`.

**Context to carry forward:**

- POSTING→CONFIRMED is now event-driven: HTTP response returns POSTING; ledger publishes banking.transfer.confirmed; TransferConfirmationListener confirms async. TransferStateService.complete() is gone — replaced by advanceToPosting() + confirm().
- Phase 3 is highest-risk: Istio PERMISSIVE then STRICT, RS256 JWT, resource limits to prevent OOMKill
- Phase 4 ETL depends on Istio metrics being in Prometheus first — do not start ETL until traffic is flowing
- Debezium connector must be re-registered after every `podman compose up` — it is NOT auto-registered
- @MockBean is GONE in Spring Boot 4 — always use @MockitoBean / @SpyBean
- RestClient @Bean names must not match any @Component class name in scan path — name explicitly
- CreateAccountRequest uses `initialBalance` (not `balance`) for opening balance
- OTel property keys verified: tracing=management.opentelemetry.tracing.export.otlp.endpoint, logging=management.opentelemetry.logging.export.otlp.endpoint, metrics=management.otlp.metrics.export.url

---

*State initialized: 2026-04-10*
*Last updated: 2026-04-16 — Quick 260416-u6y: add transfer_initiated_total metrics for all states (CONFIRMED, FAILED, CANCELLED paths)*
