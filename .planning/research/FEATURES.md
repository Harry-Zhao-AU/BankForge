# Feature Landscape: BankForge (Australian Core Banking Microservices Platform)

**Domain:** Australian banking demonstration platform (enterprise microservices patterns)
**Researched:** 2026-04-10
**Confidence:** HIGH for pattern knowledge and Australian banking specifics; MEDIUM for MCP/AI agent capabilities (fast-moving space)

---

## Framing: What "Feature" Means Here

This is a demonstration platform, not a production bank. "Features" fall into two categories:

1. **Pattern demonstrations** — the reason the project exists. Each feature must make a specific enterprise pattern observable and verifiable.
2. **Domain authenticity** — Australian banking specifics that make patterns feel real rather than toy.

A feature that doesn't demonstrate a pattern AND isn't needed for authenticity is an anti-feature.

---

## Table Stakes

Features without which the demo fails to prove its core thesis ("enterprise patterns work together, not just in theory").

### 1. Local ACID Money Movement

| Attribute | Detail |
|-----------|--------|
| What | Debit + credit + outbox row in a single database transaction |
| Pattern demonstrated | ACID correctness; why you don't use Saga for money |
| Complexity | Low — single service, single DB |
| Observable via | Postgres transaction log; intentional failure injection showing rollback |
| Minimum viable | Two accounts, one transfer endpoint, one balance query endpoint |

**Why table stakes:** Without a correctly implemented local ACID transfer, every downstream pattern (Saga, outbox, ledger) loses credibility. This is the bedrock.

**Implementation note:** The `accounts` table, `outbox` table, and transfer in a single `@Transactional` block. The outbox row is the proof — if the transfer committed, the event exists; if it rolled back, the event does not.

---

### 2. Outbox Pattern + Debezium CDC

| Attribute | Detail |
|-----------|--------|
| What | Outbox table written in same TX as domain change; Debezium streams rows to Kafka |
| Pattern demonstrated | Reliable event delivery without dual-write; at-least-once delivery guarantee |
| Complexity | Medium — Debezium connector config, Postgres WAL must be enabled (wal_level=logical) |
| Observable via | Jaeger trace showing DB write → Kafka lag → consumer receipt; Debezium connector status UI |
| Minimum viable | One outbox table per service, one Debezium connector, Kafka topic receiving events |

**Why table stakes:** This is the correct solution to the dual-write problem. Without it, the project is just "services that call each other" — not a credible enterprise pattern demo.

**Implementation note:** `wal_level = logical` in PostgreSQL config is a hard requirement. Debezium's `pgoutput` plugin (built into Postgres 10+) avoids the `decoderbufs` dependency. The outbox table schema needs `aggregate_type`, `aggregate_id`, `event_type`, `payload` (JSONB), `published` flag.

---

### 3. Saga Pattern for Async Downstream

| Attribute | Detail |
|-----------|--------|
| What | Kafka consumers in ledger-service and notification-service react to transfer events |
| Pattern demonstrated | Choreography-based Saga; eventual consistency for non-critical paths |
| Complexity | Medium — Kafka consumer groups, at-least-once processing, idempotent consumers |
| Observable via | Jaeger trace showing async span propagation; Kafka consumer lag metrics in Grafana |
| Minimum viable | Transfer event → ledger entry created; transfer event → notification sent |

**Why table stakes:** Without Saga, there's no contrast with local ACID. The demo needs BOTH to show *when to use which pattern*.

**Implementation note:** Saga participants must be idempotent — a retry of the same Kafka message must not create duplicate ledger entries. Use the `transfer_id` as an idempotency key in downstream consumers.

---

### 4. Transfer State Machine

| Attribute | Detail |
|-----------|--------|
| What | Explicit state transitions: PENDING → PROCESSING → COMPLETED / FAILED / COMPENSATING → CANCELLED |
| Pattern demonstrated | State machine for distributed workflow lifecycle management |
| Complexity | Low-Medium — Spring State Machine or manual enum-based FSM |
| Observable via | Transfer status endpoint; state transition events in Kafka; Jaeger traces per transition |
| Minimum viable | 5 states, happy path + compensation path both reachable |

**Why table stakes:** Saga without state tracking is a black box. The state machine makes distributed workflow state explicit and auditable — a non-negotiable requirement in banking.

---

### 5. Idempotency Keys on Payment API

| Attribute | Detail |
|-----------|--------|
| What | Client-supplied `Idempotency-Key` header; Redis stores key → result for 24h |
| Pattern demonstrated | Safe retry without double-charge; idempotent API design |
| Complexity | Low — Redis TTL key, check-before-process logic |
| Observable via | Retry the same request twice, observe same response, one DB record |
| Minimum viable | Redis key `idempotency:{key}` stores serialised response; 409 on duplicate in-flight |

**Why table stakes:** This is the production pattern every payment API uses. Without it, the demo doesn't teach safe retry — and payment retries without idempotency keys cause double-charges.

---

### 6. Double-Entry Ledger

| Attribute | Detail |
|-----------|--------|
| What | Every money movement creates two ledger entries (debit + credit) summing to zero |
| Pattern demonstrated | Accounting correctness; audit trail; balance verification |
| Complexity | Low — ledger_entries table with `account_id`, `amount` (positive/negative), `transaction_id` |
| Observable via | `SELECT SUM(amount) FROM ledger_entries` always = 0; trace from transfer → ledger entries |
| Minimum viable | Ledger service consumes transfer events, creates paired entries, exposes balance endpoint |

**Why table stakes:** Every Australian bank uses double-entry bookkeeping. Without it, the "banking" part of the demo is cosmetic.

---

### 7. Distributed Tracing (End-to-End)

| Attribute | Detail |
|-----------|--------|
| What | W3C TraceContext propagated from Kong → account-service → Kafka → ledger/notification → Jaeger |
| Pattern demonstrated | Observability; debugging distributed transactions; latency attribution |
| Complexity | Medium — trace context propagation across Kafka requires explicit header passing |
| Observable via | Single Jaeger trace showing full transfer lifecycle across 4 services |
| Minimum viable | One complete trace spanning HTTP + Kafka; waterfall view in Jaeger |

**Why table stakes:** The MCP AI agent's RCA capability depends on traces. Without them, `get_jaeger_trace(trace_id)` returns nothing meaningful.

**Implementation note:** Spring Boot 4 with `spring-boot-starter-actuator` and `management.otlp.tracing.endpoint` handles HTTP propagation automatically. Kafka header propagation requires `TextMapPropagator` on producer/consumer — this is the one non-trivial tracing step.

---

### 8. Prometheus Metrics + Grafana Dashboard

| Attribute | Detail |
|-----------|--------|
| What | Service-level metrics (latency, error rate, throughput) + Kafka consumer lag |
| Pattern demonstrated | Metrics-based observability; SLO monitoring |
| Complexity | Low — Spring Boot Actuator exposes `/actuator/prometheus` automatically |
| Observable via | Grafana dashboard with latency p50/p95/p99, error rate, Kafka lag |
| Minimum viable | 3 panels: latency histogram, error rate, Kafka consumer lag per service |

---

### 9. Structured Audit Logging (AUSTRAC-style)

| Attribute | Detail |
|-----------|--------|
| What | JSON-structured logs with `transaction_id`, `account_id`, `amount`, `event_type`, `timestamp`, `user_id` on every financial event |
| Pattern demonstrated | Regulatory audit trail; log-based compliance |
| Complexity | Low — ECS format via `logging.structured.format.console: ecs` in Spring Boot 4 |
| Observable via | Loki query for `{service="account-service"} | json | event_type="TRANSFER_COMPLETED"` |
| Minimum viable | Every financial event logs mandatory fields; Loki retains searchably |

**Why table stakes:** AUSTRAC requires financial institutions to retain records of all transactions for 7 years and report suspicious activity. The demo can't claim Australian banking authenticity without structured audit logs.

---

### 10. Kubernetes Deployment + Istio mTLS

| Attribute | Detail |
|-----------|--------|
| What | All services deployed to kind K8s cluster with Istio sidecar injection; all inter-service traffic mTLS |
| Pattern demonstrated | Service mesh; zero-trust networking; certificate lifecycle automation |
| Complexity | High — kind cluster setup, Istio install, sidecar injection, mTLS verification |
| Observable via | Kiali showing mTLS on all edges; `istioctl authn tls-check` confirming STRICT mode |
| Minimum viable | All 4 services + Kafka + PostgreSQL deployed; mTLS verified via Kiali |

---

### 11. Kong API Gateway with JWT Auth

| Attribute | Detail |
|-----------|--------|
| What | Kong as external ingress; JWT validation against Keycloak JWKS; rate limiting per consumer |
| Pattern demonstrated | API gateway pattern; external vs internal auth separation; rate limiting |
| Complexity | Medium — Kong plugin configuration, Keycloak realm + client setup, JWKS endpoint |
| Observable via | Rejected request with expired JWT; rate-limited response after 100 req/min |
| Minimum viable | One Kong route → account-service; JWT plugin; rate-limiting plugin |

---

### 12. MCP Server with Core Banking Tools

| Attribute | Detail |
|-----------|--------|
| What | Python MCP server exposing `check_balance`, `track_transfer`, `get_service_health`, `find_slow_services`, `get_jaeger_trace`, `query_metrics`, `root_cause_analysis` |
| Pattern demonstrated | AI-system integration; tool-based agent interaction with live infrastructure |
| Complexity | Medium — MCP SDK is mature; complexity is in making tool outputs useful for RCA |
| Observable via | Claude Desktop session showing autonomous multi-step RCA |
| Minimum viable | 5 tools working end-to-end: balance check, transfer trace, metrics query, graph query, health check |

---

## Differentiators

Features that elevate the demo from "standard microservices tutorial" to "impressive portfolio piece."

### 1. Neo4j Service Graph with Live Prometheus ETL

| Attribute | Detail |
|-----------|--------|
| What | ETL service queries Prometheus every 30s; writes `OBSERVED_CALL` relationships to Neo4j with latency/error metrics on edges |
| Value | Enables graph-traversal RCA that PromQL cannot express ("which service is the root cause of cascading latency?") |
| Complexity | Medium — Prometheus HTTP API → Spring scheduled job → Neo4j Bolt driver → Cypher MERGE |
| Demo moment | Cypher query: `MATCH path = (s:Service)-[:OBSERVED_CALL*1..3]->(slow:Service {name:'ledger-service'}) RETURN path` |

**Why differentiating:** Graph-based RCA is what actual SRE platforms (Dynatrace, ServiceNow) use. Demonstrating it with Neo4j shows architectural thinking beyond "install Grafana."

---

### 2. Autonomous Root Cause Analysis via MCP

| Attribute | Detail |
|-----------|--------|
| What | Claude uses `find_slow_services` → `get_jaeger_trace` → `query_service_graph` → `query_metrics` autonomously to diagnose a latency issue |
| Value | Shows AI-assisted ops — not just "Claude can query the DB" but "Claude can reason across multiple observability signals" |
| Complexity | Medium-High — tool design matters; outputs must be structured for LLM consumption |
| Demo moment | Ask: "Why is payment-service slow right now?" — Claude autonomously traces through 3-4 tool calls and returns root cause with evidence |

**Why differentiating:** MCP integration with live observability systems is genuinely novel. Most "AI + banking" demos use mocked data. This uses live traces and metrics.

**Confidence note:** MEDIUM — MCP SDK is actively evolving (spec updates in 2024-2025). The core tool-call pattern is stable, but streaming/sampling features may shift.

---

### 3. BSB Validation + NPP PayID Simulation

| Attribute | Detail |
|-----------|--------|
| What | BSB format validation (6-digit, `XXX-XXX`); NPP PayID aliases (email/phone → account resolution); realistic Australian account number generation |
| Value | Makes the demo feel like a real Australian bank, not a generic "bank A sends to bank B" tutorial |
| Complexity | Low — BSB is a static lookup (APCA publishes BSB directory); PayID is domain logic only |
| Demo moment | `POST /payments/npp` with `payid: "user@example.com"` resolves to BSB+account, executes transfer |

**Australian specifics worth including:**
- BSB format: 6 digits, commonly written `XXX-XXX` (e.g., `062-000` for CBA)
- NPP characteristics: real-time (< 15 seconds), 24/7, description field up to 280 chars, amount up to $1M per transaction
- PayID types: email, phone number (E.164), ABN, organisational ID

---

### 4. BPAY Payment Pattern Simulation

| Attribute | Detail |
|-----------|--------|
| What | BPAY biller code + reference number validation; batch settlement simulation; 3-day clearing cycle state transitions |
| Value | BPAY is ubiquitous in Australian consumer banking (utilities, council rates, insurance) — demonstrates scheduled/batch payment patterns distinct from NPP real-time |
| Complexity | Medium — BPAY has distinct lifecycle from NPP (batch vs real-time, T+3 settlement) |
| Demo moment | BPAY payment enters PENDING, transitions through BATCH_QUEUED → SUBMITTED → CLEARED lifecycle |

**Australian specifics worth including:**
- BPAY biller codes: 4-6 digits; reference numbers are Luhn-validated (biller-specific)
- BPAY settles at 9am AEST on the next business day (T+1 for consumers, T+3 for some billers)
- BPAY Error Code 01 (invalid biller) and 02 (invalid reference) are well-known failure modes

---

### 5. Chaos Engineering / Fault Injection Demo Scenarios

| Attribute | Detail |
|-----------|--------|
| What | Pre-built failure scenarios: network partition, DB slow query, Kafka consumer lag spike, service crash |
| Value | Makes the observability stack meaningful — you can't show "monitoring works" without something to monitor |
| Complexity | Medium — Istio fault injection (`HTTPFault` in VirtualService) covers network faults; `pg_sleep()` covers DB |
| Demo moment | Inject 2s latency on ledger-service → Grafana shows spike → Claude identifies root cause via MCP |

**Implementation note:** Istio `VirtualService` supports `fault.delay` and `fault.abort` without changing application code. This is cleaner than application-level chaos tools for a demo.

---

### 6. APRA-Style Compliance Event Trail

| Attribute | Detail |
|-----------|--------|
| What | Structured compliance events for high-value transactions (>$10,000 AUD threshold), unusual patterns, failed auth attempts |
| Value | Demonstrates that compliance requirements shape architecture — not just a logging afterthought |
| Complexity | Low — Kafka topic `compliance.events`, filter on transaction amount/pattern in payment-service |
| Demo moment | Transfer of $50,000 → compliance event emitted → separate Loki stream → Grafana compliance dashboard |

**Australian specifics:**
- AUSTRAC Threshold Transaction Reports (TTRs): required for cash transactions >= $10,000 AUD
- Suspicious Matter Reports (SMRs): required when staff have reasonable grounds to suspect
- For a demo: simulate TTR trigger on any transfer >= $10,000, log to dedicated audit stream

---

### 7. Circuit Breaker with Fallback

| Attribute | Detail |
|-----------|--------|
| What | Istio `DestinationRule` with outlier detection + Resilience4j in-process circuit breaker on payment-service → ledger-service calls |
| Value | Demonstrates resilience patterns — service degradation without cascading failure |
| Complexity | Low (Istio) to Medium (Resilience4j) |
| Demo moment | Kill ledger-service → payment-service returns cached/degraded response → circuit opens → Kiali shows red edge |

---

## Anti-Features

Things to deliberately NOT build. Building these is a complexity trap that diverts effort from pattern demonstration.

### 1. Frontend UI

**Why avoid:** A React/Angular UI adds weeks of work with zero additional enterprise pattern demonstration. The API endpoints + Swagger UI + Claude Desktop are sufficient for all demo scenarios.

**What instead:** Swagger UI via `springdoc-openapi` for manual API exploration; Claude Desktop as the "UI" for AI scenarios.

---

### 2. Real NPP/BPAY Rail Integration

**Why avoid:** NPP access requires RITS (RBA's settlement system) membership or sponsorship through a Connected Institution. BPAY access requires a BPAY scheme membership agreement. Neither is achievable for a demo project.

**What instead:** Simulate the payment lifecycle state machine. The pattern (request validation → amount hold → settlement → notification) is what matters, not the wire protocol.

---

### 3. Multi-Tenancy / Customer Onboarding

**Why avoid:** Multi-tenancy adds row-level security, tenant isolation, schema-per-tenant decisions, and Keycloak realm complexity that drowns the core patterns.

**What instead:** Single Keycloak realm, seeded test accounts. Document that multi-tenancy would add these layers.

---

### 4. Production-Grade Security Hardening

**Why avoid:** Secret rotation, HSM integration, mTLS certificate management, DAST scanning, and SAST pipelines are real concerns — but implementing them properly takes longer than building the core platform. A demo with half-done security is worse than a demo that clearly documents what production security would add.

**What instead:** Keycloak + Istio mTLS covers the patterns. Document production gaps explicitly in README.

---

### 5. Event Sourcing / CQRS

**Why avoid:** The project already has three distinct data patterns (relational, outbox/CDC, graph). Adding event sourcing (append-only event store, projection rebuilding, snapshot management) adds a fourth conceptual model that competes for attention without adding proportionate value.

**What instead:** The outbox table is append-only by convention — close enough to demonstrate the pattern. CQRS can be mentioned as an architectural note without implementing it.

---

### 6. gRPC Inter-Service Communication

**Why avoid:** REST over HTTP/1.1 with OTel trace propagation is well-understood and simpler to demo. gRPC adds protobuf compilation, streaming complexity, and reflection tooling that distracts from the mesh and transaction patterns.

**What instead:** HTTP REST with explicit `W3C-TraceContext` headers. Document gRPC as an upgrade path.

---

### 7. Distributed Saga Orchestrator (Temporal/Conductor)

**Why avoid:** The project correctly chose choreography-based Saga (Kafka events). Adding an orchestrator (Temporal, Netflix Conductor, Camunda) is a fundamentally different architectural approach that would muddy the comparison. Choose one pattern, demonstrate it well.

**What instead:** State machine in account-service + Kafka event choreography. This combination is clear and demonstrable.

---

### 8. Data Warehouse / Analytics Layer

**Why avoid:** BI, OLAP queries, and reporting pipelines are a separate domain. Neo4j already adds a second data store beyond PostgreSQL; a third (ClickHouse, BigQuery) is complexity without proportionate demo value.

**What instead:** Grafana dashboards over Prometheus + Loki cover the analytics story sufficiently.

---

## Australian Banking Specifics Worth Including

These features add authenticity without disproportionate complexity.

### BSB (Bank State Branch) System

- **Format:** 6 digits, written as `XXX-XXX` (e.g., `062-000`)
- **Structure:** First 2 digits = bank code (06 = CBA, 03 = Westpac, 01 = ANZ, 08 = NAB), next digit = state, last 3 = branch
- **Validation:** Luhn check is NOT used — BSB validity is lookup-based against APCA BSB directory
- **Demo implementation:** Static lookup table of 10-20 valid BSBs; reject invalid BSB format on account creation
- **Confidence:** HIGH (stable standard, unchanged for decades)

### NPP (New Payments Platform) Patterns

- **Settlement:** Real-time gross settlement via RBA RITS; target < 15 seconds end-to-end
- **PayID:** Alias registry (email, mobile, ABN) mapping to BSB+account; maintained by NPPA
- **Transaction limits:** Up to $1,000,000 per transaction for most institutions
- **Description field:** Up to 280 characters (longer than legacy DE fields)
- **Message standard:** ISO 20022 (pain.001, pacs.008, pacs.002) — simulate the field names, not the wire format
- **Demo implementation:** NPP payment endpoint that validates PayID → BSB+account, executes transfer, completes within simulated "15 second" SLA
- **Confidence:** HIGH for business rules; MEDIUM for exact ISO 20022 field mapping (training data)

### BPAY Patterns

- **Biller codes:** 4-6 digit numeric; assigned by BPAY Pty Ltd
- **Customer Reference Number (CRN):** Biller-specific Luhn variant validation
- **Settlement:** Batched; typically T+1 for consumer-initiated, T+3 for some biller types
- **Demo implementation:** BPAY endpoint with biller code + CRN validation, batch-queued lifecycle state
- **Confidence:** HIGH for business rules; MEDIUM for exact CRN Luhn variant

### AUSTRAC Compliance Patterns

- **Threshold Transaction Reports (TTRs):** Required for cash transactions >= $10,000 AUD
- **Suspicious Matter Reports (SMRs):** Triggered by pattern analysis, not just thresholds
- **Record retention:** 7 years minimum for transaction records, 5 years for IVTS records
- **IFTI (International Funds Transfer Instructions):** Reporting required for cross-border transfers
- **Demo implementation:** TTR trigger on transfers >= $10,000; emit to `compliance.events` Kafka topic; retain in Loki with 7-day (simulated) retention tag
- **Confidence:** HIGH for TTR threshold and principles; MEDIUM for exact SMR trigger conditions

### APRA Regulatory Framing

- **CPS 234:** Information security standard — demo shows mTLS, JWT, structured security logging
- **CPS 230:** Operational risk — demo shows circuit breakers, chaos engineering, recovery procedures
- **Demo implementation:** Reference these standards in README and in compliance event payloads (e.g., `"regulatory_basis": "AUSTRAC-TTR"`)
- **Confidence:** MEDIUM (APRA CPSs evolve; referencing the standard names adds authenticity without requiring compliance)

---

## Feature Dependencies

```
BSB validation
    └── required by → Account creation, NPP payment, BPAY payment

Local ACID transfer
    └── required by → Outbox pattern (outbox written in same TX)
    └── required by → State machine (transfer entity must exist to transition)
    └── required by → Double-entry ledger (ledger consumes transfer events)
    └── required by → AUSTRAC compliance events (trigger on transfer amount)

Outbox pattern
    └── required by → Debezium CDC (reads outbox table)
    └── required by → Saga pattern (Kafka events come from outbox)

Kafka events (from Debezium + outbox)
    └── required by → Saga consumers (ledger-service, notification-service)
    └── required by → Distributed tracing via Kafka headers

Distributed tracing (Jaeger)
    └── required by → MCP tool: get_jaeger_trace()
    └── required by → AI-assisted RCA

Prometheus metrics
    └── required by → Neo4j ETL (queries Prometheus HTTP API)
    └── required by → MCP tool: query_metrics()
    └── required by → Grafana dashboards

Neo4j service graph
    └── required by → MCP tool: query_service_graph()
    └── required by → Cypher-based RCA

MCP server (all tools)
    └── required by → Autonomous RCA demo scenario

Kubernetes + Istio
    └── required by → mTLS verification
    └── required by → Circuit breaker (Istio DestinationRule)
    └── required by → Fault injection demo scenarios
    └── required by → Kiali service graph UI
```

---

## MVP Recommendation

**Prioritize (Phase 1 MVP — Docker Compose):**

1. Local ACID transfer with outbox table (the non-negotiable foundation)
2. Debezium CDC → Kafka (makes everything else flow)
3. Transfer state machine (PENDING → COMPLETED / FAILED / COMPENSATING)
4. Double-entry ledger consumer (table stakes for "banking" authenticity)
5. Redis idempotency keys on payment endpoint
6. BSB validation on account creation
7. AUSTRAC-style structured logs (ECS format, mandatory fields)

**Defer until Phase 2 (Observability):**
- Distributed tracing via Jaeger (requires OTel Kafka propagation, non-trivial)
- Prometheus + Grafana dashboards
- Loki structured log collection

**Defer until Phase 3 (Service Mesh):**
- Kubernetes deployment
- Istio mTLS + circuit breakers
- Kong JWT + rate limiting
- Fault injection scenarios

**Defer until Phase 4-5 (Graph + AI):**
- Neo4j ETL from Prometheus
- MCP server tools
- Autonomous RCA scenarios
- NPP PayID simulation (nice-to-have; add if Phase 4 has bandwidth)
- BPAY lifecycle (nice-to-have; add if Phase 4 has bandwidth)

---

## MCP/AI Agent Capabilities: Realistic Assessment

**What works well today (HIGH confidence):**
- Tool calls with structured JSON inputs/outputs — mature, stable
- Sequential multi-tool reasoning ("check X, then based on result check Y")
- Prometheus PromQL query execution + result interpretation
- Neo4j Cypher query generation and result summarisation
- Jaeger trace span analysis

**What requires careful design (MEDIUM confidence):**
- Tool output schema matters enormously — LLMs handle flat JSON better than deeply nested structures
- RCA quality depends on prompt design + tool granularity, not just data availability
- Autonomous multi-step workflows work but require clear tool descriptions and bounded scope
- Error handling in tools must return structured errors (not raw exceptions) or the agent gets confused

**What to avoid in tool design (HIGH confidence):**
- Tools that return large raw JSON blobs (> ~2KB) — summarise in the tool, don't send raw Prometheus responses
- Tools with ambiguous names or overlapping responsibilities — `find_slow_services` and `get_service_health` must have clearly distinct purposes
- Tools that require the agent to construct Cypher/PromQL from scratch — provide parameterised templates

**Realistic demo scenario for RCA:**

```
User: "Payment-service seems slow. What's happening?"

Claude calls: find_slow_services(threshold_ms=500)
→ Returns: [{service: "payment-service", p99_ms: 1240, error_rate: 0.02}]

Claude calls: get_jaeger_trace(service="payment-service", limit=5)
→ Returns: [{trace_id: "abc123", slowest_span: "ledger-service.createEntry", duration_ms: 980}]

Claude calls: query_service_graph(from="payment-service", depth=2)
→ Returns: graph showing payment-service → ledger-service with high avg_latency

Claude calls: query_metrics(promql="rate(http_server_requests_seconds_count{service='ledger-service', status='500'}[5m])")
→ Returns: spike in ledger-service errors starting 8 minutes ago

Claude: "Root cause: ledger-service experiencing elevated error rate since 14:23 AEST.
Payment-service p99 latency is elevated because 94% of slow traces have their
longest span in ledger-service.createEntry. Recommend checking ledger-service
pod logs and DB connection pool."
```

This is achievable today. The MCP tool design (structured outputs, sensible schemas) is the critical success factor.

---

## Sources

- Project files: `plan.md`, `.planning/PROJECT.md`
- Australian Payments Network (APN) BSB standards — stable, long-established specification (HIGH confidence, training data)
- NPPA (NPP Australia) NPP technical overview — real-time gross settlement, PayID, ISO 20022 (HIGH confidence for business rules, MEDIUM for wire format details)
- AUSTRAC reporting obligations — TTR threshold AUD 10,000, 7-year retention, SMR obligations (HIGH confidence, well-documented public regulatory guidance)
- APRA CPS 234/CPS 230 — information security and operational risk prudential standards (MEDIUM confidence — standard names stable, exact requirements evolve)
- MCP (Model Context Protocol) SDK documentation — tool-call patterns, structured outputs (MEDIUM confidence — spec active as of late 2024, may have evolved)
- Spring Boot 4 / OTel integration — built-in `management.otlp.tracing` (HIGH confidence, verified against Spring Boot 4.x release notes in training data)
- Debezium PostgreSQL connector — `pgoutput` plugin, `wal_level=logical` requirement (HIGH confidence, stable for several years)
