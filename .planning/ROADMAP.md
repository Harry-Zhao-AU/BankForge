# Roadmap: BankForge

**Created:** 2026-04-10
**Granularity:** Standard
**Coverage:** 34/34 v1 requirements mapped

---

## Phases

- [ ] **Phase 1: Service Scaffold + Core Banking** — Four Spring Boot services on Podman Compose with ACID money movement, BSB validation, transfer state machine (enum FSM), and Redis idempotency. Outbox table written but CDC not yet consumed.
- [ ] **Phase 1.1: CDC Pipeline + Compliance + Kind Spike** — Kafka KRaft + Debezium CDC consuming the outbox, DLT topics, AUSTRAC threshold audit logging, and a Podman + kind networking validation spike.
- [ ] **Phase 2: Observability** — Full observability stack (OTel Collector, Jaeger, Prometheus, Loki, Grafana) added to Compose, proving distributed traces and metrics before Kubernetes migration.
- [ ] **Phase 3: Service Mesh & Auth** — Full cut-over to a kind Kubernetes cluster with Istio mTLS STRICT, Kong API gateway, and Keycloak JWT issuance. Kiali live traffic graph operational.
- [ ] **Phase 4: Graph & RCA Foundation** — Neo4j service graph populated via Prometheus/Istio ETL every 30 seconds, with Cypher queries that identify bottleneck services.
- [ ] **Phase 5: AI Integration / MCP** — Python MCP server exposing banking operations and observability as tools to Claude Desktop, with autonomous end-to-end RCA demo.

---

## Phase Details

### Phase 1: Service Scaffold + Core Banking

**Goal**: Four Spring Boot services run correctly on Podman Compose — accounts can be created with BSB validation, funds transfer atomically (debit + credit + outbox row in one TX), the transfer state machine transitions through all states, and Redis idempotency blocks duplicate requests. No event streaming yet.

**Depends on**: Nothing (first phase)

**Requirements**: CORE-01, CORE-02, CORE-03, TXNS-01, TXNS-04, TXNS-05

**Critical constraints (must be correct from Day 1):**
- All monetary fields must use `BigDecimal` in Java and `DECIMAL(15,4)` in PostgreSQL — no `double` or `float`
- Balance queries must use `SELECT FOR UPDATE` (`PESSIMISTIC_WRITE`) to prevent concurrent overdraft
- Outbox table must be created via Flyway migration (Phase 1.1 Debezium will read it) — schema is set now, CDC wiring comes in Phase 1.1
- PostgreSQL `wal_level=logical` must be set in Compose now so Phase 1.1 Debezium needs no DB restart

**Success Criteria** (what must be TRUE):
  1. A POST to account-service creates an account with a valid BSB (NNN-NNN format) and rejects malformed BSBs — confirming validation runs on every account operation
  2. A transfer between two accounts commits debit + credit + outbox row in a single PostgreSQL transaction — confirming ACID atomicity (outbox row present in DB; CDC not yet wired)
  3. Submitting a duplicate transfer request with the same idempotency key returns the original cached response without executing a second debit — confirming Redis idempotency is active
  4. payment-service state machine transitions through PENDING -> PAYMENT_PROCESSING -> PAYMENT_DONE -> POSTING -> CONFIRMED for a successful transfer, and to COMPENSATING -> CANCELLED on a failed debit

**Plans:** 1/4 plans executed

Plans:
- [x] 01-01-PLAN.md — Project foundation: Maven multi-module scaffold, common module (state machine, BSB validation), Compose infrastructure
- [x] 01-02-PLAN.md — account-service: ACID banking core with Flyway migrations, PESSIMISTIC_WRITE transfers, outbox table, integration tests
- [ ] 01-03-PLAN.md — payment-service: Transfer orchestration with state machine, Redis idempotency, RestClient to account-service, tests
- [ ] 01-04-PLAN.md — Stub services (ledger + notification), Compose build and startup, end-to-end human verification

---

### Phase 1.1: CDC Pipeline + Compliance + Kind Spike (INSERTED)

**Goal**: Events from the Phase 1 outbox table flow reliably from PostgreSQL WAL through Debezium and Kafka to downstream consumers, AUSTRAC audit logging captures threshold transfers, and Podman + kind networking is validated as the substrate for Phase 3.

**Depends on**: Phase 1

**Requirements**: CORE-04, CORE-05, TXNS-02, TXNS-03, AUBN-01, AUBN-02

**Critical constraints:**
- Kafka must run in KRaft mode (no ZooKeeper) — `KAFKA_PROCESS_ROLES=broker,controller`
- Dead letter queues (DLT topics) must be configured from Day 1 — no silent event loss for financial events
- Debezium slot leak prevention: `slot.drop.on.stop=true` in connector config
- Podman + kind networking spike must pass before Phase 3 begins

**Success Criteria** (what must be TRUE):
  1. A transfer between two accounts produces a Debezium CDC event on the Kafka topic within 5 seconds of the PostgreSQL TX commit — confirming the full Outbox -> WAL -> Debezium -> Kafka pipeline
  2. A Kafka consumer (ledger-service or notification-service) receives and processes the CDC event, with failed messages routed to the DLT topic — confirming at-least-once delivery and DLQ are wired
  3. A transfer of AUD $10,000 or more produces a structured compliance event with timestamp, both account IDs, amount, and transfer ID in the AUSTRAC audit log — confirming threshold detection works
  4. A single-node kind cluster starts via `KIND_EXPERIMENTAL_PROVIDER=podman`, a test pod is reachable, and DNS resolves cluster-internal names from a pod — confirming the networking substrate for Phase 3 is viable

**Plans**: TBD

### Phase 2: Observability

**Goal**: Engineers can trace any transfer end-to-end across all services in Jaeger, query Prometheus metrics, and view structured logs in Grafana Loki — all still on Podman Compose

**Depends on**: Phase 1

**Requirements**: OBS-01, OBS-02, OBS-03, OBS-04, OBS-05

**Critical constraints:**
- Use only `application.yml` for OTel configuration — do not create manual `TracerProvider` beans (causes duplicate/dropped spans with Spring Boot 4 auto-config)
- Verify ECS field paths from a running service before writing Promtail pipeline config — field path mismatch fails silently in Loki label extraction

**Success Criteria** (what must be TRUE):
  1. Initiating a transfer produces a distributed trace in Jaeger that spans account-service, payment-service, ledger-service, and notification-service — with parent/child span relationships visible and full span detail queryable
  2. Prometheus scrapes metrics from all four services and they appear in the Grafana banking dashboard, showing transfer volume, p99 latency, and error rate panels with real data
  3. Structured ECS-format logs from all services are queryable in Grafana Loki by service name, transfer ID, and log level — a LogQL query for a specific transfer ID returns matching entries across services

**Plans**: TBD

---

### Phase 3: Service Mesh & Auth

**Goal**: All services run inside a kind Kubernetes cluster behind Istio mTLS and Kong JWT auth — no service handles auth code and no plaintext pod-to-pod traffic exists

**Depends on**: Phase 2

**Requirements**: MESH-01, MESH-02, MESH-03, MESH-04, MESH-05, MESH-06, MESH-07

**Critical constraints:**
- Namespace must carry `istio-injection: enabled` label before any pods are deployed — retroactive labelling requires pod restarts
- Start Istio in PERMISSIVE mTLS mode, verify all pods show 2/2 READY with sidecars, then switch to STRICT — never go straight to STRICT
- Kong JWT plugin must be configured with RS256 (not HS256) — Keycloak issues RS256 tokens
- Kong must strip incoming `X-User-Id` headers before injecting the verified header from JWT claims — prevents header forgery
- All pods must have CPU/memory requests and limits set — Istio sidecars + Spring Boot JVMs risk OOMKill without limits
- Maintain a `port-forward.sh` script for all services the MCP server will need (Phase 5 connectivity from Windows host)

**Success Criteria** (what must be TRUE):
  1. Every banking service pod shows 2/2 READY containers, confirming Istio sidecar injection is active on all pods
  2. A direct pod-to-pod HTTP request (bypassing Kong) without a valid Istio identity is rejected by the Envoy sidecar, confirming mTLS STRICT is enforced for internal traffic
  3. A request with a valid Keycloak JWT reaches the banking API; a request with a missing or invalid JWT is rejected at Kong with 401 — and services never see raw JWTs, only the injected `X-User-Id` header
  4. Sending more than 100 requests per minute from a single client IP results in 429 responses from Kong after the limit is reached — confirming rate limiting is active
  5. The Kiali dashboard displays a live service graph showing traffic between Kong, all four banking services, and Debezium — with health indicators and traffic rates updating in real time

**Plans**: TBD

---

### Phase 4: Graph & RCA Foundation

**Goal**: Neo4j holds a live service relationship graph derived from Prometheus/Istio metrics, and Cypher queries can identify which service has the highest latency or error rate

**Depends on**: Phase 3

**Requirements**: GRAPH-01, GRAPH-02, GRAPH-03

**Critical constraints:**
- Neo4j ETL must only start after Istio metrics exist in Prometheus (minimum 1 minute of live traffic data)
- ETL queries must use `rate(metric[5m])` windows — not instantaneous values — to avoid overwriting edge properties with unrepresentative spikes
- Verify actual Prometheus Istio metric label names against the running system before writing any ETL code (`/api/v1/label/__name__/values`)

**Success Criteria** (what must be TRUE):
  1. After generating traffic (running a set of transfers), the Neo4j graph contains `Service` nodes for each banking service and `OBSERVED_CALL` edges carrying avg latency, p99 latency, error count, and call count — populated by the ETL, not by manual entry
  2. The ETL's 30-second polling cycle is observable: each run issues a Prometheus query and executes a Cypher MERGE+SET, updating edge properties — confirmed by Neo4j edge timestamps advancing every 30 seconds
  3. A Cypher query (`MATCH (a)-[r:OBSERVED_CALL]->(b) RETURN a, r, b ORDER BY r.avg_latency_ms DESC LIMIT 5`) returns ranked results identifying the slowest service pair — confirming graph-traversal RCA queries work against real data

**Plans**: TBD

---

### Phase 5: AI Integration / MCP

**Goal**: Claude Desktop can query live banking state and observability data via MCP tools, and can autonomously diagnose a slow service using a composed RCA workflow

**Depends on**: Phase 4

**Requirements**: MCP-01, MCP-02, MCP-03, MCP-04, MCP-05, MCP-06, MCP-07, MCP-08

**Critical constraints:**
- All tool handlers must be `async def` with `httpx.AsyncClient` for HTTP and `AsyncGraphDatabase.driver()` for Neo4j — synchronous handlers block all tool calls
- Every tool must return a structured result: `{success: bool, data: ..., error: {code, message, retryable}}` — raw Python tracebacks prevent Claude from reasoning about failures
- `claude_desktop_config.json` must use absolute Windows paths — relative paths cause Claude Desktop to report MCP server as disconnected
- All write tools must default to `dry_run=True` — Claude must not be able to corrupt transfer state without an explicit override

**Success Criteria** (what must be TRUE):
  1. Claude Desktop connects to the MCP server (server appears as active in Claude Desktop settings) and `check_balance(account_id)` returns the current balance for a real account created in Phase 1
  2. `track_transfer(transfer_id)` returns the current state machine status and the full event history for a transfer — Claude can describe what happened at each state transition
  3. `find_slow_services(threshold_ms=200)` returns a list of services currently exceeding the threshold, sourced from a live Prometheus query — not hardcoded or mocked
  4. `query_service_graph("MATCH (a)-[r:OBSERVED_CALL]->(b) RETURN a.name, r.avg_latency_ms ORDER BY r.avg_latency_ms DESC LIMIT 3")` returns ranked results from the live Neo4j graph
  5. Claude Desktop runs an end-to-end RCA scenario — given a slow service name, Claude calls `root_cause_analysis(service_name)`, which composes find_slow_services + get_jaeger_trace + query_service_graph, and Claude produces a written explanation of the root cause with supporting trace and graph evidence

**Plans**: TBD

---

## Progress

| Phase | Plans Complete | Status | Completed |
|-------|----------------|--------|-----------|
| 1. Service Scaffold + Core Banking | 1/4 | In Progress|  |
| 1.1. CDC Pipeline + Compliance | 0/? | Not started | - |
| 2. Observability | 0/? | Not started | - |
| 3. Service Mesh & Auth | 0/? | Not started | - |
| 4. Graph & RCA Foundation | 0/? | Not started | - |
| 5. AI Integration / MCP | 0/? | Not started | - |

---

## Coverage Validation

| Requirement | Phase | Category |
|-------------|-------|----------|
| CORE-01 | Phase 1 | Core Services |
| CORE-02 | Phase 1 | Core Services |
| CORE-03 | Phase 1 | Core Services |
| CORE-04 | Phase 1.1 | Core Services |
| CORE-05 | Phase 1.1 | Core Services |
| TXNS-01 | Phase 1 | Transaction Patterns |
| TXNS-02 | Phase 1.1 | Transaction Patterns |
| TXNS-03 | Phase 1.1 | Transaction Patterns |
| TXNS-04 | Phase 1 | Transaction Patterns |
| TXNS-05 | Phase 1 | Transaction Patterns |
| AUBN-01 | Phase 1.1 | Australian Banking |
| AUBN-02 | Phase 1.1 | Australian Banking |
| OBS-01 | Phase 2 | Observability |
| OBS-02 | Phase 2 | Observability |
| OBS-03 | Phase 2 | Observability |
| OBS-04 | Phase 2 | Observability |
| OBS-05 | Phase 2 | Observability |
| MESH-01 | Phase 3 | Service Mesh & Auth |
| MESH-02 | Phase 3 | Service Mesh & Auth |
| MESH-03 | Phase 3 | Service Mesh & Auth |
| MESH-04 | Phase 3 | Service Mesh & Auth |
| MESH-05 | Phase 3 | Service Mesh & Auth |
| MESH-06 | Phase 3 | Service Mesh & Auth |
| MESH-07 | Phase 3 | Service Mesh & Auth |
| GRAPH-01 | Phase 4 | Graph & RCA |
| GRAPH-02 | Phase 4 | Graph & RCA |
| GRAPH-03 | Phase 4 | Graph & RCA |
| MCP-01 | Phase 5 | AI Integration / MCP |
| MCP-02 | Phase 5 | AI Integration / MCP |
| MCP-03 | Phase 5 | AI Integration / MCP |
| MCP-04 | Phase 5 | AI Integration / MCP |
| MCP-05 | Phase 5 | AI Integration / MCP |
| MCP-06 | Phase 5 | AI Integration / MCP |
| MCP-07 | Phase 5 | AI Integration / MCP |
| MCP-08 | Phase 5 | AI Integration / MCP |

**Mapped: 34/34 v1 requirements — no orphans**

---

*Roadmap created: 2026-04-10*
*Last updated: 2026-04-10 after Phase 1 planning*
