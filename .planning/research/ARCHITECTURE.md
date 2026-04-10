# Architecture Patterns

**Project:** BankForge — Australian Core Banking Microservices Platform
**Researched:** 2026-04-10
**Confidence note:** WebSearch and WebFetch were unavailable during this session. All findings are based on project files (PROJECT.md, plan.md) and training knowledge of these established enterprise patterns. Confidence for well-established patterns (Outbox/CDC, Saga, Istio) is HIGH. Confidence for Spring Boot 4.0.5 specifics is MEDIUM (verify against official Spring docs at build time).

---

## Recommended Architecture

The system has three distinct layers, each with a clear responsibility boundary. Money never crosses a layer boundary in a single atomic transaction — each layer is internally consistent and communicates with the next via events.

```
┌─────────────────────────────────────────────────────────────┐
│  EXTERNAL LAYER                                             │
│  Kong (JWT validation, rate limiting, routing)              │
│  Keycloak (OAuth2 / OIDC token issuer)                      │
└───────────────────────┬─────────────────────────────────────┘
                        │ HTTPS (validated JWT stripped, X-User-Id header injected)
┌───────────────────────▼─────────────────────────────────────┐
│  SERVICE MESH LAYER (Istio)                                 │
│  mTLS between all pods, circuit breaking, retries           │
│  Kiali service graph (live topology)                        │
└────────┬──────────────┬──────────────┬──────────────────────┘
         │ sync HTTP    │ sync HTTP    │ sync HTTP
┌────────▼───┐  ┌───────▼────┐  ┌─────▼──────┐  ┌────────────┐
│  account   │  │  payment   │  │  ledger    │  │notification│
│  service   │  │  service   │  │  service   │  │  service   │
│ (Postgres) │  │ (Postgres) │  │ (Postgres) │  │ (Postgres) │
└────────┬───┘  └───────┬────┘  └────────────┘  └────────────┘
         │ outbox table  │ outbox table
         │               │
┌────────▼───────────────▼──────────────────────────────────┐
│  CDC LAYER (Debezium — Kafka Connect)                      │
│  Reads WAL from each Postgres outbox table                 │
│  Publishes to Kafka topics                                 │
└────────────────────────┬──────────────────────────────────┘
                         │ at-least-once events
┌────────────────────────▼──────────────────────────────────┐
│  EVENT STREAMING (Kafka)                                   │
│  Topics: transfers, payments, ledger-entries, alerts       │
└───────┬────────────────┬──────────────────────────────────┘
        │ saga steps     │ metrics feed
┌───────▼─────────┐  ┌───▼───────────────────────────────────┐
│ ledger-service  │  │  Neo4j ETL (Spring scheduled job)      │
│ notification    │  │  Queries Prometheus every 30s          │
│ fraud (future)  │  │  Writes OBSERVED_CALL edges to Neo4j   │
└─────────────────┘  └──────────────────────────────────────┘
                                     │
                             ┌───────▼──────────┐
                             │  Neo4j 5         │
                             │  Service graph   │
                             │  RCA queries     │
                             └───────┬──────────┘
                                     │ Cypher
                         ┌───────────▼────────────────────────┐
                         │  Python MCP Server                 │
                         │  tools: graph, metrics, traces,    │
                         │         balance, transfers         │
                         └───────────┬────────────────────────┘
                                     │ MCP protocol
                               Claude Desktop
```

---

## Component Boundaries

### Component: account-service
**Responsibility:** Account lifecycle (create, close, freeze), balance management, initiates money transfers.
**Owns:** `accounts` table, `transfers` table, `outbox` table (all in its PostgreSQL schema).
**Sync calls (inbound):** REST from Kong/Istio — balance check, transfer initiation.
**Sync calls (outbound):** None. It does not call other services synchronously during money movement. This is the critical safety guarantee.
**Async (outbound):** Writes `TransferInitiated` event to outbox table inside the same local ACID transaction that debits/credits balances. Debezium then publishes to Kafka.
**Does NOT:** Call ledger-service or notification-service directly.

### Component: payment-service
**Responsibility:** NPP/BPAY payment orchestration, idempotency enforcement via Redis, payment state machine.
**Owns:** `payments` table, `outbox` table.
**Sync calls (inbound):** REST from external clients via Kong.
**Sync calls (outbound):** Calls account-service (HTTP via Istio) to initiate the underlying account debit/credit.
**Async (outbound):** Writes payment lifecycle events to outbox (Debezium picks up).
**Redis:** Checks idempotency key before processing; writes key atomically with payment record.

### Component: ledger-service
**Responsibility:** Double-entry bookkeeping — every transfer becomes two ledger lines (debit entry + credit entry). Audit trail.
**Owns:** `ledger_entries` table, `outbox` table.
**Sync calls (inbound):** None — Kafka consumer only.
**Async (inbound):** Consumes `TransferCompleted` events from Kafka.
**Async (outbound):** Writes `LedgerRecorded` event to outbox.
**Invariant:** A ledger entry is created for every completed transfer, but ledger failure does NOT roll back the transfer (eventual consistency is acceptable here — the money has moved).

### Component: notification-service
**Responsibility:** Sends alerts (email, SMS, push) for banking events.
**Owns:** `notifications` table, `outbox` table.
**Sync calls:** None.
**Async (inbound):** Consumes multiple Kafka topics (transfer events, payment events).
**Retry strategy:** Kafka consumer group with dead-letter topic for failed notifications. Notification failure never affects the transfer.

### Component: Debezium (Kafka Connect)
**Responsibility:** Tail PostgreSQL WAL (Write-Ahead Log) for each service's outbox table and publish CDC events to Kafka. Eliminates dual-write problem entirely.
**Topology:** One Debezium connector per service database. Each connector has its own `database.server.name` and reads only that service's outbox table (via table include list).
**At-least-once guarantee:** Debezium offsets are stored in Kafka. A crash and restart replays from last committed offset — consumers must be idempotent.
**Outbox event router transform:** The `io.debezium.transforms.outbox.EventRouter` SMT (Single Message Transform) routes CDC records to per-aggregate Kafka topics and strips internal outbox columns from the event payload before publishing.

### Component: Kafka
**Responsibility:** Durable event log. All async communication between services.
**Topics (recommended naming):**
- `banking.account.transfer-initiated`
- `banking.account.transfer-completed`
- `banking.payment.payment-initiated`
- `banking.payment.payment-completed`
- `banking.ledger.ledger-recorded`
- `banking.notification.sent`
**Partitioning:** Partition by `account_id` or `transfer_id` to preserve ordering per entity.
**Retention:** Set to 7 days minimum for audit compliance. Compaction NOT suitable for event topics.

### Component: Redis
**Responsibility:** Idempotency keys for payment API (24h TTL), JWT token cache (5-min TTL), optional balance cache (30s TTL).
**Pattern:** `SET NX EX` — atomic check-and-set. If key exists, return cached result. If not, process and write. This prevents double-charge on network retry.
**Sync only:** Redis is a synchronous call in the payment-service request path.

### Component: Istio (service mesh)
**Responsibility:** Internal service-to-service mTLS, circuit breaking, retry policies, distributed tracing headers propagation, load balancing.
**Scope:** Internal cluster traffic only. Does not touch external-facing traffic (Kong handles that).
**Sidecar injection:** All banking service pods get an Envoy sidecar injected via namespace label `istio-injection=enabled`. Services need zero TLS code.
**Traffic management resources:** VirtualService (routing + retries + timeouts), DestinationRule (circuit breaker thresholds, mTLS mode STRICT).
**Tracing:** Istio Envoy sidecars propagate `b3` or `W3C TraceContext` headers. Spring Boot OTel SDK reads these headers and continues the trace span.

### Component: Kong (API gateway)
**Responsibility:** External-facing ingress. JWT validation against Keycloak JWKS endpoint, rate limiting (100 req/min), request routing to Istio ingress.
**Plugins required:** `jwt` (validates token, rejects expired/invalid), `rate-limiting` (per consumer or global), optionally `request-transformer` (inject `X-User-Id` header from JWT claim for services to consume).
**Auth flow:** Client presents Bearer token → Kong validates signature against Keycloak JWKS → Kong injects `X-User-Id: <sub claim>` → Services read header, no auth logic needed.
**Does NOT:** Kong does not call Keycloak on every request — it caches the JWKS and validates JWT signatures locally. This is critical for performance.

### Component: Keycloak
**Responsibility:** OAuth2 authorization server and OIDC identity provider. Issues JWTs. Single realm for this system.
**Auth flows used:** Authorization Code (human users, future UI), Client Credentials (MCP server API key flow).
**Not in the hot path:** Keycloak is only involved at token issuance time, not per-request. Kong caches JWKS.

### Component: Neo4j ETL (scheduled job)
**Responsibility:** Queries Prometheus every 30 seconds for service-to-service call metrics (rate, latency, error count), then upserts `OBSERVED_CALL` relationships in Neo4j with those metrics as edge properties.
**Data source:** Prometheus `istio_request_duration_milliseconds` histogram and `istio_requests_total` counter metrics — these come from Istio's Envoy telemetry, not application code.
**Implementation:** Spring Boot scheduled job (`@Scheduled`) calling Prometheus HTTP API (`/api/v1/query`), then using Neo4j Java driver to run Cypher MERGE statements.
**Upsert semantics:** MERGE on service pair, then SET properties — idempotent, safe to run repeatedly.

### Component: Neo4j
**Responsibility:** Service relationship graph store. Enables graph traversal queries for RCA that time-series PromQL cannot express.
**Key query patterns:**
- "Which services have >200ms average latency to their downstream dependencies?" (neighbor traversal)
- "What is the call path from payment-service to the slowest leaf service?" (shortest path)
- "Which services share a common upstream bottleneck?" (common ancestor query)
**Not a replacement for Prometheus:** Neo4j answers "what is connected to what and how healthy is that connection." Prometheus answers "what is the current value of metric X."

### Component: Python MCP Server
**Responsibility:** Exposes banking system capabilities and observability tools as MCP tools that Claude can call autonomously.
**Auth:** Uses API key against Kong (Client Credentials flow via Keycloak, or static key for simplicity in dev).
**Tool categories:**
1. Banking operations: `check_balance`, `track_transfer`, `place_order`
2. Observability: `get_jaeger_trace`, `query_metrics` (PromQL), `get_service_health`
3. Graph/RCA: `query_service_graph` (Cypher), `find_slow_services`, `root_cause_analysis`
4. Event injection: `publish_event` (for testing/simulation)
**Transport:** `stdio` transport for Claude Desktop integration (process spawned by Claude Desktop, communicates via stdin/stdout). No network exposure needed for local dev.

---

## Data Flow: Money Movement (Critical Path)

This is the most important flow in the system. Every design decision that touches money must preserve atomicity within the account-service boundary.

```
1. Client → Kong (HTTPS, Bearer JWT)
2. Kong validates JWT signature (JWKS cache), injects X-User-Id header
3. Kong → Istio ingress → payment-service (mTLS, HTTP/2)

4. payment-service:
   a. Read idempotency key from Redis (SET NX EX 86400)
      → If exists: return cached response (stop here)
      → If not: continue
   b. Validate request (BSB format, amount > 0, not self-transfer)
   c. Call account-service via HTTP (Istio handles mTLS, retry, circuit break)

5. account-service (LOCAL ACID TX — single DB transaction):
   a. BEGIN TX
   b. SELECT accounts WHERE id = $source FOR UPDATE  (pessimistic lock)
   c. SELECT accounts WHERE id = $dest FOR UPDATE
   d. Check: source.balance >= amount AND source.status = ACTIVE
   e. UPDATE accounts SET balance = balance - amount WHERE id = $source
   f. UPDATE accounts SET balance = balance + amount WHERE id = $dest
   g. INSERT INTO outbox (aggregate_id, event_type, payload) VALUES (...)
   h. COMMIT TX
   ← Return success to payment-service

6. payment-service:
   a. Write idempotency key result to Redis
   b. Update payment state machine: PENDING → PAYMENT_DONE
   c. Write PaymentCompleted event to its own outbox (in its own TX)
   ← Return 202 Accepted to client

7. Debezium (background, async):
   a. Reads account-service outbox via PostgreSQL WAL (logical replication slot)
   b. Applies EventRouter SMT → routes to topic `banking.account.transfer-initiated`
   c. Publishes to Kafka (at-least-once)

8. Kafka consumers (async, independent):
   a. ledger-service consumes event → double-entry bookkeeping → commits ledger entries
   b. notification-service consumes event → sends customer alert
   c. fraud-service (future) consumes event → checks for anomalies

9. Neo4j ETL (every 30s, independent):
   a. Queries Prometheus for Istio metrics on banking namespace
   b. UPSERTs OBSERVED_CALL edges with fresh latency/error data
```

**Money atomicity guarantee:** Steps 5a-5h are a single PostgreSQL transaction. If anything fails inside, the entire TX rolls back — no partial debit without credit. The outbox entry is written in the same TX, so if the TX commits, the event is guaranteed to be delivered (eventually) via Debezium/Kafka.

**What "at-least-once" means for consumers:** Ledger-service and notification-service may receive the same event more than once if Debezium restarts. They must be idempotent — use the `aggregate_id` (transfer ID) as a deduplication key in their own DB.

---

## Data Flow: Observability

```
Services → OTel SDK (Spring Boot 4 built-in) → OTel Collector → Jaeger (traces)
Services → Micrometer → Prometheus scrape endpoint (:8080/actuator/prometheus)
Prometheus → scrapes all service pods every 15s
Services → structured JSON logs (ECS format) → Promtail → Loki
Grafana → queries Jaeger + Prometheus + Loki in unified dashboards
Neo4j ETL → queries Prometheus → writes to Neo4j
MCP server → queries Jaeger API + Prometheus API + Neo4j Cypher
```

**Trace propagation across the mesh:** Istio Envoy reads W3C TraceContext headers and continues the span. Spring Boot OTel SDK continues the same trace from the headers. The result: a single Jaeger trace spans Kong → payment-service → account-service with timing for each hop.

---

## Saga: Choreography is Correct for This System

The plan uses Kafka Saga, which is **Choreography** (not Orchestration). This is the right choice for this system. Here is why:

**Choreography (what is implemented):**
- account-service emits `TransferInitiated` event.
- ledger-service listens and responds autonomously.
- notification-service listens and responds autonomously.
- No central coordinator.

**Orchestration (not used):**
- A "transfer-orchestrator" service would send commands to ledger-service and notification-service and wait for replies.
- Adds a network hop and a new point of failure.
- Only necessary if the saga steps have complex conditional branching or rollback dependencies between them.

**Why choreography wins here:** The downstream steps (ledger recording, notification) are fire-and-forget from the transfer's perspective. A failure in ledger-service does not require compensation in account-service — the money has moved, the ledger will catch up. Orchestration would add complexity without benefit.

**When orchestration IS appropriate:** If a future "fraud hold" step must complete before the transfer is considered done, and failure requires compensating the debit/credit, then a Saga orchestrator (e.g., Conductor, or a custom Spring State Machine + Kafka reply topics) becomes necessary. Flag this as a Phase 5+ concern.

---

## Two-Layer Gateway Pattern: Kong + Istio

This is a well-established enterprise pattern. The two layers have no overlap — they operate at different trust boundaries.

```
[Internet] → Kong → [Cluster boundary] → Istio → [Service]
```

**Kong (north-south traffic):**
- Terminates external HTTPS.
- Validates JWT: Kong fetches JWKS from Keycloak once at startup, caches it, and validates all subsequent tokens locally without calling Keycloak.
- Rate limits per consumer or global.
- Routes to the correct Kubernetes service (by path prefix or hostname).
- Strips the `Authorization` header and injects `X-User-Id` from JWT `sub` claim.
- Kong runs as a Kubernetes Deployment with a LoadBalancer service or NodePort (kind).

**Istio (east-west traffic):**
- Handles all pod-to-pod communication inside the cluster.
- mTLS STRICT mode: pods without a valid SPIFFE identity certificate are rejected.
- Circuit breaking via DestinationRule `outlierDetection`: ejects pods that return 5xx errors above threshold.
- Retries via VirtualService: 3 attempts, 3s per-try timeout. Only safe for idempotent operations (GET, or POST with idempotency keys).
- Does not validate JWTs — Kong handled that. Istio only enforces mTLS identity.

**Interaction between the two:**
- Kong runs as a regular Kubernetes pod with an Istio sidecar. Kong's outbound requests to services are intercepted by the Envoy sidecar, which applies mTLS automatically. Kong does not need TLS configuration for backend calls.
- AuthorizationPolicy (Istio RBAC) can restrict which pods can call which services (e.g., only payment-service is allowed to call account-service). This is an additive layer on top of Kong's JWT check.

---

## Outbox + Debezium: Exact Mechanics

**Why outbox (not direct Kafka publish):**
If you call `kafkaTemplate.send()` inside a DB transaction, two failure modes cause data loss:
1. DB commits, Kafka publish fails → event lost.
2. Kafka publish succeeds, DB rolls back → phantom event published.
The outbox table eliminates both. The outbox row is written in the same DB transaction. Debezium reads the WAL — if Debezium crashes and restarts, it replays from last committed Kafka offset, so no events are lost.

**PostgreSQL prerequisites:**
- `wal_level = logical` (must be set in postgresql.conf — not the default `replica`).
- Debezium user needs `REPLICATION` privilege and SELECT on the outbox table.
- Each Debezium connector creates a replication slot in the source DB. Monitor slot lag — an abandoned slot will cause WAL to accumulate and fill disk.

**Connector configuration (critical options):**
```json
{
  "connector.class": "io.debezium.connector.postgresql.PostgresConnector",
  "database.hostname": "account-db",
  "database.dbname": "account",
  "slot.name": "debezium_account",
  "table.include.list": "public.outbox",
  "transforms": "outbox",
  "transforms.outbox.type": "io.debezium.transforms.outbox.EventRouter",
  "transforms.outbox.table.field.event.id": "id",
  "transforms.outbox.table.field.event.key": "aggregate_id",
  "transforms.outbox.table.field.event.type": "event_type",
  "transforms.outbox.table.field.event.payload": "payload",
  "transforms.outbox.route.by.field": "aggregate_type",
  "transforms.outbox.route.topic.replacement": "banking.${routedByValue}"
}
```

**Outbox table schema (minimal):**
```sql
CREATE TABLE outbox (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_id    UUID        NOT NULL,   -- e.g. transfer_id
    aggregate_type  VARCHAR(50) NOT NULL,   -- e.g. "account.transfer"
    event_type      VARCHAR(50) NOT NULL,   -- e.g. "TransferInitiated"
    payload         JSONB       NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    published       BOOLEAN     NOT NULL DEFAULT FALSE
);
```

The `published` column is optional when using Debezium (Debezium reads WAL, not the column). Include it only if you want a polling fallback or cleanup job. Debezium's WAL read is independent of this flag.

**Cleanup:** Delivered outbox rows accumulate. A scheduled job should DELETE outbox rows older than N days where published = TRUE, or simply rely on Debezium's WAL replication (rows are safe to delete after the replication slot has confirmed they are past the slot LSN).

---

## Neo4j Graph Ingestion: Prometheus ETL Design

**Data source:** Istio Envoy telemetry exposes `istio_request_duration_milliseconds` (histogram) and `istio_requests_total` (counter) metrics per (source_workload, destination_workload, response_code) labels. Prometheus scrapes these from the Istio metrics endpoint.

**ETL query pattern (every 30 seconds):**
```promql
# Average latency per service pair (last 1m window)
rate(istio_request_duration_milliseconds_sum{namespace="banking"}[1m])
/ rate(istio_request_duration_milliseconds_count{namespace="banking"}[1m])

# Error rate per service pair
sum by (source_workload, destination_workload) (
  rate(istio_requests_total{namespace="banking", response_code=~"5.."}[1m])
)
```

**Neo4j write pattern:**
```cypher
MERGE (src:Service {name: $source})
MERGE (dst:Service {name: $target})
MERGE (src)-[r:OBSERVED_CALL]->(dst)
SET r.avg_latency_ms = $avg_latency,
    r.p99_latency_ms = $p99,
    r.error_rate     = $error_rate,
    r.call_count_1m  = $call_count,
    r.updated_at     = datetime()
```

**RCA query examples:**
```cypher
-- Find services with p99 > 500ms
MATCH (a:Service)-[r:OBSERVED_CALL]->(b:Service)
WHERE r.p99_latency_ms > 500
RETURN a.name, b.name, r.p99_latency_ms ORDER BY r.p99_latency_ms DESC

-- Find services downstream of a slow node
MATCH path = (entry:Service {name: "payment-service"})-[:OBSERVED_CALL*1..3]->(leaf:Service)
WHERE ALL(r IN relationships(path) WHERE r.avg_latency_ms IS NOT NULL)
RETURN path
```

---

## Build Order (What Must Exist Before What)

This is the most practically important section. Dependency violations cause days of debugging.

### Phase 1 — Core Services (local Compose)

**Hard dependencies:**
1. PostgreSQL must be running before any Spring Boot service starts (health checks in compose.yml).
2. Kafka must be running before Debezium starts (Debezium publishes to Kafka).
3. Debezium must start AFTER PostgreSQL has `wal_level = logical` configured AND the outbox table exists AND the Debezium replication user exists.
4. Redis must be running before payment-service starts (idempotency check is in the request path).

**Build order within Phase 1:**
```
1. PostgreSQL (one container per service, or shared with separate schemas)
2. Redis
3. Kafka + Zookeeper (or KRaft mode Kafka)
4. account-service (depends on Postgres, emits to outbox)
5. ledger-service (depends on Postgres, consumes from Kafka)
6. notification-service (depends on Postgres, consumes from Kafka)
7. payment-service (depends on Postgres + Redis + account-service HTTP)
8. Debezium Kafka Connect (depends on Kafka + all Postgres DBs ready)
9. Register Debezium connectors (POST to Connect REST API — do this after Connect is healthy)
```

**Critical ordering mistake to avoid:** Registering the Debezium connector before the outbox table exists. Debezium will fail with "table not found" and may not recover cleanly. Add a startup script that waits for table existence before registering connectors.

### Phase 2 — Observability (add to Compose)

**Hard dependencies:**
- OTel Collector must be running before services start (or services will buffer traces and drop them on overflow).
- Jaeger must be running before OTel Collector (Collector exports to Jaeger).
- Prometheus must be running and scrape config must include all service pods.
- Loki must be running before Promtail (Promtail pushes to Loki).
- Grafana must be running after Loki + Prometheus + Jaeger (data sources must exist).

**Build order:**
```
1. Jaeger (all-in-one for dev)
2. OTel Collector (→ Jaeger)
3. Prometheus (scrapes services + OTel Collector)
4. Loki
5. Promtail (→ Loki)
6. Grafana (→ all three above)
7. Services (restart with OTel config pointing to Collector)
```

### Phase 3 — Kubernetes Migration (kind + Istio + Kong)

**Hard dependencies:**
- Istio must be installed in the cluster before deploying banking services (sidecar injection requires Istio webhooks to be active).
- Keycloak must be running and realm configured before Kong starts (Kong needs to know the JWKS URI).
- Kong must be running before any external traffic flows.
- All Phase 1 infrastructure (Kafka, Postgres, Redis, Debezium) must be deployed as K8s manifests before banking services.

**Migration strategy (Compose → K8s):**
Do not attempt to run Compose and K8s simultaneously. The migration is a full cut-over:
1. Export all Compose config into K8s manifests.
2. Deploy infrastructure layer first (Postgres, Redis, Kafka, Debezium).
3. Deploy observability layer second.
4. Deploy Istio and verify sidecar injection.
5. Deploy banking services.
6. Deploy Kong and configure plugins.
7. Smoke test end-to-end before decommissioning Compose.

**Build order within Phase 3:**
```
1. kind cluster creation
2. Istio install (istioctl install --set profile=demo)
3. Label banking namespace (istio-injection=enabled)
4. Keycloak (K8s Deployment + Service)
5. PostgreSQL (StatefulSet per service, or single with multiple databases)
6. Redis (Deployment)
7. Kafka (StatefulSet — use Bitnami Helm chart or Strimzi operator)
8. Debezium Kafka Connect (Deployment — register connectors after Kafka ready)
9. OTel Collector, Jaeger, Prometheus, Loki, Grafana
10. Banking services (account, payment, ledger, notification)
11. Kong Ingress Controller (Helm chart: kong/kong)
12. Kong plugins (KongPlugin CRDs for JWT + rate limiting)
13. Istio VirtualServices + DestinationRules
14. Kiali (after Istio + Prometheus are both running)
```

### Phase 4 — Neo4j Graph

**Hard dependencies:**
- Prometheus must be running and have at least 1 minute of Istio metrics before the ETL job runs (otherwise PromQL rate() returns no data).
- Neo4j must be running before the ETL job starts.
- Istio must be running and generating telemetry (Phase 3 complete).

**Build order:**
```
1. Neo4j 5 (K8s Deployment, use Community edition for dev)
2. Run init.cypher (create indexes, constraints)
3. Neo4j ETL service (Spring Boot scheduled job)
4. Verify OBSERVED_CALL edges appear after first 30s cycle
```

### Phase 5 — MCP Server

**Hard dependencies:**
- All previous phases must be complete and healthy.
- Prometheus, Jaeger, Neo4j APIs must be network-accessible from the MCP server pod/process.
- Kong must be configured with an API key for the MCP server (Client Credentials or static key).

**Build order:**
```
1. Implement MCP tools in Python (one tool at a time, test each)
2. Configure claude_desktop_config.json (stdio transport — no K8s needed)
3. Test each tool against live system
4. Add root_cause_analysis composite tool last (depends on all others)
```

---

## Architectural Risks (Validate Early)

### Risk 1 — PostgreSQL WAL Level (CRITICAL, validate in Phase 1 Week 1)
**What:** `wal_level = logical` is not the default. In a containerized environment, this must be set in `postgresql.conf` or via a command-line argument to the Postgres container before Debezium can create a replication slot.
**How to validate:** Run `SHOW wal_level;` in psql. Must return `logical`, not `replica` or `minimal`.
**Mitigation:** Add `command: ["postgres", "-c", "wal_level=logical"]` to the Postgres container definition in compose.yml and K8s manifests.

### Risk 2 — Debezium Replication Slot Lag (HIGH, monitor in Phase 1)
**What:** Each Debezium connector holds a PostgreSQL replication slot. If Debezium stops but the slot is not dropped, the WAL accumulates and can fill the disk. In a development environment with limited disk, this can halt the entire Postgres instance.
**How to validate:** Query `pg_replication_slots` — slot lag should be near zero when Debezium is running.
**Mitigation:** Add a health check alert on slot lag. Know how to drop slots: `SELECT pg_drop_replication_slot('debezium_account');`.

### Risk 3 — Spring Boot 4 / Java 21 Virtual Thread Compatibility (MEDIUM, validate in Phase 1)
**What:** Spring Boot 4 with virtual threads (`spring.threads.virtual.enabled=true`) changes how blocking operations behave. Some Kafka consumer and Debezium internal threads may not behave as expected with virtual thread executors.
**Confidence:** MEDIUM — Spring Boot 4 is relatively new at time of writing. Verify against Spring Boot 4.0 release notes.
**Mitigation:** Test each service with a load test in Phase 1 before proceeding. Check that Kafka consumer groups maintain correct partition assignment under virtual threads.

### Risk 4 — Compose → Kubernetes Migration Complexity (HIGH, plan for Phase 3)
**What:** Networking, volume mounts, health checks, secrets, and service discovery all change significantly between Compose and Kubernetes. The migration is not a simple translation of compose.yml to K8s manifests.
**Specific challenges:**
- Compose `depends_on` health checks become K8s initContainers or readinessProbes.
- Compose service names become K8s Service DNS names (same format, but namespace-scoped).
- Compose volumes become PersistentVolumeClaims (StorageClass matters in kind).
- Debezium replication slots must survive pod restarts — use StatefulSets with persistent storage.
**Mitigation:** Keep the Compose file working throughout Phase 1-2. When migrating, migrate one service at a time (not all at once). Start with a stateless service (notification-service) to validate the K8s manifests before migrating stateful services.

### Risk 5 — Istio + Kong Routing Conflicts (MEDIUM, validate in Phase 3)
**What:** If Kong is deployed with an Istio sidecar (it should be), traffic from Kong to backend services goes through Envoy twice: once from Kong's pod outbound, once into the destination pod's inbound sidecar. This is correct behavior but can cause confusion when debugging latency.
**Specific risk:** Kong's health check probes to backend services may trigger Istio circuit breaker if the backends are not ready. Configure `outlierDetection` conservatively during initial setup.
**Mitigation:** Use `istioctl proxy-config` to inspect routing tables. Start with Istio in `PERMISSIVE` mode (allows plaintext), then switch to `STRICT` mTLS after validating all connectivity.

### Risk 6 — Prometheus Istio Metrics Label Names (MEDIUM, validate in Phase 4)
**What:** The Prometheus labels for Istio metrics (`source_workload`, `destination_workload`) depend on the Istio version and configuration. If the Neo4j ETL uses hardcoded label names from Istio 1.x and the installed version uses different names, the ETL will produce empty or incorrect graphs.
**Mitigation:** In Phase 4 Week 1, run a raw PromQL query against the live Prometheus to confirm exact label names before writing the ETL queries. Do not assume label names from documentation — verify against the running instance.

### Risk 7 — MCP Server Python SDK Stability (LOW, validate in Phase 5)
**What:** The Python MCP SDK is relatively new. Tool registration patterns, error handling, and stdio transport behavior may have changed between versions.
**Mitigation:** Pin the `mcp` package version. Read the SDK changelog before starting Phase 5. Test with a minimal "hello world" tool before implementing complex tools.

### Risk 8 — kind Cluster Resource Constraints (MEDIUM, validate in Phase 3)
**What:** Running Istio (with its control plane: istiod, ingress-gateway), Kong, Keycloak, Kafka, PostgreSQL (x4), Redis, Neo4j, Grafana, Jaeger, Loki on a single kind node will require significant RAM (16GB+ recommended) and CPU. On underpowered machines, pods will be OOMKilled.
**Mitigation:** Run `kubectl top nodes` and `kubectl top pods` after Phase 3 migration. Use resource requests/limits on all pods. Consider disabling Neo4j Bloom (it is a heavy UI) in low-resource environments. Kiali and Grafana can be started on-demand rather than always running.

---

## Anti-Patterns to Avoid

### Anti-Pattern 1: Synchronous Inter-Service Calls for Money Movement
**What:** payment-service calls ledger-service synchronously during a transfer.
**Why bad:** Couples the transfer success to ledger availability. If ledger is down, transfers fail. Latency adds up across the call chain.
**Instead:** payment-service writes the event to its outbox. Ledger-service consumes it from Kafka. Transfer returns success as soon as the account-service ACID TX commits.

### Anti-Pattern 2: Shared Database Between Services
**What:** Multiple services read/write the same PostgreSQL schema.
**Why bad:** Eliminates service autonomy. Schema changes affect all services. Coupling makes the system harder to evolve.
**Instead:** Each service owns its own schema. Cross-service data sharing happens only via Kafka events or explicit API calls.

### Anti-Pattern 3: Publishing Events Before Transaction Commits
**What:** Calling `kafkaTemplate.send()` inside a DB transaction before it commits (or outside the transaction entirely).
**Why bad:** If the DB TX rolls back after the Kafka publish, you have published a phantom event. Consumers will act on a transfer that never happened.
**Instead:** Use the outbox table. Debezium reads from WAL — WAL entries only appear after the TX commits. This is the only correct pattern for transactional outbox.

### Anti-Pattern 4: Non-Idempotent Kafka Consumers
**What:** ledger-service creates a new ledger entry for every Kafka message it receives.
**Why bad:** Debezium guarantees at-least-once delivery. A consumer restart will cause replay. Double-processing creates duplicate ledger entries.
**Instead:** Check for existing record by `aggregate_id` (transfer ID) before inserting. Use `INSERT ... ON CONFLICT DO NOTHING` or equivalent.

### Anti-Pattern 5: Storing Secrets in K8s ConfigMaps
**What:** Database passwords, Kafka credentials, JWT signing keys stored in ConfigMaps.
**Why bad:** ConfigMaps are plaintext in etcd. Any pod can read any ConfigMap in the same namespace.
**Instead:** Use Kubernetes Secrets (base64 encoded, not encrypted by default in kind, but the correct abstraction). For production, use sealed-secrets or Vault. For this project, Secrets are sufficient.

---

## Scalability Notes (Context for a Learning System)

This system is not designed for production scale, but understanding the scale ceiling informs architectural choices.

| Concern | Current (dev) | Production approach |
|---------|---------------|---------------------|
| Account writes | Single Postgres per service | Read replicas, connection pooling (PgBouncer) |
| Kafka throughput | Single partition per topic | Partition by account_id (enables parallelism) |
| Debezium connectors | One per service | One per service is correct — don't share |
| Neo4j | Community (single node) | Enterprise (cluster) for HA |
| Idempotency keys | Redis single node | Redis Cluster or ElastiCache |
| API Gateway | Kong single instance | Kong data plane replicas |

The current architecture correctly makes choices that scale: Kafka partitioning by entity ID, separate DB per service, outbox over direct publish. The main scale limiters are the single-node databases and single-node Redis, which are acceptable for a dev sandbox.

---

## Sources

All findings based on project files (PROJECT.md, plan.md) and training knowledge of the following well-documented patterns and technologies. External web access was unavailable during this research session.

- Debezium Outbox Event Router pattern — HIGH confidence (stable, widely documented pattern since 2019)
- PostgreSQL logical replication prerequisites — HIGH confidence (stable, documented in PostgreSQL docs)
- Istio + Kong two-layer pattern — HIGH confidence (common enterprise pattern, stable since Istio 1.5+)
- Saga choreography vs orchestration trade-offs — HIGH confidence (established microservices pattern)
- Spring Boot 4 / virtual threads specifics — MEDIUM confidence (verify against Spring Boot 4.0 release notes at build time)
- Python MCP SDK transport patterns — MEDIUM confidence (newer SDK, verify version against Anthropic MCP docs)
- kind cluster resource requirements — MEDIUM confidence (based on typical Istio resource footprint, validate against actual hardware)
