# Requirements: BankForge

**Defined:** 2026-04-10
**Core Value:** A running, end-to-end system where every enterprise pattern (ACID, Saga, Outbox, mTLS, distributed tracing) is implemented and queryable via AI agent — proving the patterns work together, not just in theory.

## v1 Requirements

### Core Services (CORE)

- [x] **CORE-01**: System exposes account-service REST API for creating accounts, checking balances, and listing transfer history
- [x] **CORE-02**: System executes account-to-account transfers atomically via account-service (debit + credit + outbox write in one PostgreSQL transaction)
- [x] **CORE-03**: System exposes payment-service REST API for initiating NPP-style payment flows
- [ ] **CORE-04**: System records every transfer as a double-entry ledger pair via ledger-service (debit entry + credit entry)
- [ ] **CORE-05**: System delivers async notifications (email/SMS/push simulation) via notification-service consuming Kafka events

### Transaction Patterns (TXNS)

- [x] **TXNS-01**: Transfer debit, credit, and outbox row are committed in a single local ACID PostgreSQL transaction — no partial states possible
- [ ] **TXNS-02**: Outbox rows are captured by Debezium CDC and published to Kafka without dual-write (Debezium reads WAL, not the outbox directly)
- [ ] **TXNS-03**: Downstream services (ledger, notification) consume Kafka events via Saga choreography — no central orchestrator
- [x] **TXNS-04**: Transfer lifecycle is tracked through state machine transitions: PENDING → PAYMENT_PROCESSING → PAYMENT_DONE → CONFIRMED (or COMPENSATING → CANCELLED)
- [x] **TXNS-05**: Payment API accepts idempotency keys stored in Redis (TTL 24h) — duplicate requests return cached response without re-executing transfer

### Australian Banking (AUBN)

- [ ] **AUBN-01**: account-service validates BSB format (6 digits, format NNN-NNN) and account number format (6–10 digits) on account creation and transfer
- [ ] **AUBN-02**: Audit log records a flag for any transfer ≥ AUD $10,000 with timestamp, account IDs, amount, and transfer ID (AUSTRAC-style threshold event)

### Observability (OBS)

- [ ] **OBS-01**: All services emit OpenTelemetry traces via Spring Boot 4 built-in OTel auto-configuration to an OTel Collector
- [ ] **OBS-02**: Distributed traces (including cross-service HTTP calls) are queryable in Jaeger UI with full span detail
- [ ] **OBS-03**: Prometheus scrapes metrics from all services and the Istio control plane via OTel Collector
- [ ] **OBS-04**: Grafana dashboard displays traces, metrics, and logs in a unified view with banking-specific panels (transfer volume, latency p99, error rate)
- [ ] **OBS-05**: Structured ECS-format logs from all services are collected by Promtail and queryable in Grafana Loki

### Service Mesh & Auth (MESH)

- [ ] **MESH-01**: All services run as Kubernetes pods in a kind cluster with Istio sidecar injection enabled (2/2 READY)
- [ ] **MESH-02**: Istio enforces mTLS in STRICT mode for all pod-to-pod communication — no plaintext internal traffic
- [ ] **MESH-03**: Kong API gateway validates JWTs issued by Keycloak and injects trusted `X-User-Id` header — services read only this header, no auth code required
- [ ] **MESH-04**: Kong strips any incoming `X-User-Id` header before injecting the verified one from JWT claims — prevents header forgery
- [ ] **MESH-05**: Kong enforces rate limiting at 100 requests/minute per client
- [ ] **MESH-06**: Keycloak issues OAuth2/OIDC JWTs for the banking realm — human users authenticate via Keycloak, MCP server via API key
- [ ] **MESH-07**: Kiali dashboard shows live Istio service graph with traffic rates and health indicators

### Graph & RCA (GRAPH)

- [ ] **GRAPH-01**: Neo4j stores a service graph where nodes are services and edges (OBSERVED_CALL) carry avg latency, p99 latency, error count, and call count
- [ ] **GRAPH-02**: ETL service queries Prometheus Istio metrics every 30 seconds and upserts OBSERVED_CALL relationships via Cypher MERGE+SET
- [ ] **GRAPH-03**: System supports Cypher queries that identify bottleneck services (highest avg latency, highest error rate) for root cause analysis

### AI Integration / MCP (MCP)

- [ ] **MCP-01**: Python MCP server exposes `check_balance(account_id)` and `get_transfer_history(account_id)` as read-only tools
- [ ] **MCP-02**: Python MCP server exposes `track_transfer(transfer_id)` returning current state machine status and events
- [ ] **MCP-03**: Python MCP server exposes `find_slow_services(threshold_ms)` querying Prometheus for services exceeding latency threshold
- [ ] **MCP-04**: Python MCP server exposes `get_jaeger_trace(trace_id)` returning trace spans for a specific transfer
- [ ] **MCP-05**: Python MCP server exposes `query_metrics(promql)` for ad-hoc Prometheus queries
- [ ] **MCP-06**: Python MCP server exposes `query_service_graph(cypher)` for ad-hoc Neo4j Cypher queries
- [ ] **MCP-07**: Python MCP server exposes `root_cause_analysis(service_name)` that composes find_slow_services + get_jaeger_trace + query_service_graph into an autonomous RCA workflow
- [ ] **MCP-08**: Claude Desktop is configured to connect to the MCP server and can run an end-to-end RCA scenario: detect slow service → pull trace → query graph → explain root cause

## v2 Requirements

### Australian Banking

- **AUBN-V2-01**: NPP PayID simulation — resolve email/phone to BSB+account via mock PayID registry
- **AUBN-V2-02**: BPAY payment batch lifecycle with CRN format validation and batch processing simulation

### Observability

- **OBS-V2-01**: Kafka OTel trace propagation — trace context carried across async Kafka message headers for end-to-end trace continuity

### AI Integration

- **MCP-V2-01**: `publish_event(topic, event_type, payload, dry_run=True)` write tool — Kafka publish with mandatory dry-run guard

### Resilience

- **RES-V2-01**: Fraud-service consuming transfer events and publishing fraud-check results back to Kafka (requires compensation Saga design)
- **RES-V2-02**: Circuit breaker configuration on inter-service HTTP calls via Istio DestinationRule

## Out of Scope

| Feature | Reason |
|---------|--------|
| Frontend UI | API + dashboards only — not core to pattern demonstration |
| Real NPP/BPAY rails | Mock simulation only — real rail access requires banking licence |
| AUSTRAC real reporting | Threshold flagging in logs only — not submitting to AUSTRAC |
| Production deployment | Local kind cluster only — no cloud provider |
| Multi-tenancy | Single Keycloak realm, single cluster |
| Event sourcing / CQRS | Adds significant complexity, not needed to demonstrate patterns |
| Distributed Saga orchestrator | Choreography (Kafka) is sufficient for this system's Saga steps |

## Traceability

| Requirement | Phase | Status |
|-------------|-------|--------|
| CORE-01 | Phase 1 | Complete |
| CORE-02 | Phase 1 | Complete |
| CORE-03 | Phase 1 | Complete |
| CORE-04 | Phase 1 | Pending |
| CORE-05 | Phase 1 | Pending |
| TXNS-01 | Phase 1 | Complete |
| TXNS-02 | Phase 1 | Pending |
| TXNS-03 | Phase 1 | Pending |
| TXNS-04 | Phase 1 | Complete |
| TXNS-05 | Phase 1 | Complete |
| AUBN-01 | Phase 1 | Pending |
| AUBN-02 | Phase 1 | Pending |
| OBS-01 | Phase 2 | Pending |
| OBS-02 | Phase 2 | Pending |
| OBS-03 | Phase 2 | Pending |
| OBS-04 | Phase 2 | Pending |
| OBS-05 | Phase 2 | Pending |
| MESH-01 | Phase 3 | Pending |
| MESH-02 | Phase 3 | Pending |
| MESH-03 | Phase 3 | Pending |
| MESH-04 | Phase 3 | Pending |
| MESH-05 | Phase 3 | Pending |
| MESH-06 | Phase 3 | Pending |
| MESH-07 | Phase 3 | Pending |
| GRAPH-01 | Phase 4 | Pending |
| GRAPH-02 | Phase 4 | Pending |
| GRAPH-03 | Phase 4 | Pending |
| MCP-01 | Phase 5 | Pending |
| MCP-02 | Phase 5 | Pending |
| MCP-03 | Phase 5 | Pending |
| MCP-04 | Phase 5 | Pending |
| MCP-05 | Phase 5 | Pending |
| MCP-06 | Phase 5 | Pending |
| MCP-07 | Phase 5 | Pending |
| MCP-08 | Phase 5 | Pending |

**Coverage:**
- v1 requirements: 34 total
- Mapped to phases: 34
- Unmapped: 0 ✓

---
*Requirements defined: 2026-04-10*
*Last updated: 2026-04-10 after initial definition*
