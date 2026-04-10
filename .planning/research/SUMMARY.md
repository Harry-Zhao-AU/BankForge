# Research Summary: BankForge

**Synthesized:** 2026-04-10
**Sources:** STACK.md, FEATURES.md, ARCHITECTURE.md, PITFALLS.md

---

## Executive Summary

BankForge is an Australian core banking demonstration platform built to make enterprise microservices patterns observable and verifiable together, not just in isolation. The standard approach for this domain is a layered architecture: a Spring Boot (Java 21) service tier using the Transactional Outbox and Debezium CDC pattern for reliable event delivery, a Kafka-based choreography Saga for async downstream processing (ledger, notifications), and a Kubernetes service mesh (Istio plus Kong) for zero-trust networking and external auth. The AI observability layer on top -- Neo4j service graph driven by Prometheus ETL, exposed to Claude via a Python MCP server -- is genuinely novel and the differentiating element of the project.

The recommended build strategy is strictly phased: nail the money movement core (ACID transfer + outbox + Debezium + Kafka) in Compose before touching Kubernetes. The failure mode in this domain is skipping phases -- attempting Istio before the CDC pipeline is solid, or writing MCP tools before Prometheus and Jaeger are generating real data. Each phase produces a working, demonstrable system that is the foundation for the next.

The two highest-risk items are not architectural: (1) Spring Boot 4.0.5 may not be GA -- verify before writing any code and fall back to 3.4.x if needed; and (2) Podman rootless + kind has known networking friction on Windows that must be validated in Phase 1 before it becomes a blocker in Phase 3. All other risks are well-understood and have clear mitigations documented in the research.

---

## Recommended Stack

All versions require verification before use -- these are research-derived estimates from training data (cutoff August 2025).

### Core Java / Spring

| Technology | Recommended Version | Notes |
|---|---|---|
| Java | 21 LTS | Virtual threads (Project Loom) production-ready; no reason to use 23+ non-LTS |
| Spring Boot | 3.4.x or 4.0.x (verify 4.0.x GA status first) | 4.0.5 as in plan.md may not be released; 3.4.x has all required features |
| Spring Data JPA | Bundled with Spring Boot | Standard ORM; best-in-class Hibernate integration |
| Spring Kafka | Bundled with Spring Boot | Native @KafkaListener idioms and error handling |
| Spring Security | Bundled with Spring Boot | OAuth2 resource server for JWT validation within services |
| Spring State Machine | 4.0.x | Purpose-built FSM for the 6-state transfer lifecycle |
| Resilience4j | 2.2.x | Circuit breaker; replaces deprecated Hystrix; native Spring Boot integration |
| Flyway | 10.x | DB migrations; critical for outbox table schema management |
| MapStruct | 1.6.x | Compile-time DTO mapping; zero reflection |

### Databases

| Technology | Recommended Version | Notes |
|---|---|---|
| PostgreSQL | 16 or 17 (plan says 15) | 16+ has improved logical replication stability benefiting Debezium CDC |
| Redis | 7.2.x | Pin to 7.2 not just 7; Lettuce client preferred over Jedis for virtual thread compatibility |
| Neo4j | 5.x Community | Current major version; 4.x is EOL; Community is appropriate for local sandbox |

### Messaging

| Technology | Recommended Version | Notes |
|---|---|---|
| Apache Kafka | 3.8.x | KRaft mode (no ZooKeeper); ZooKeeper support removed in 3.8+ |
| Debezium | 3.0.x | Kafka Connect deployment; native KRaft support; EventRouter SMT for outbox routing |
| Kafka UI (Provectus) | latest | Add to Compose from Phase 1; invaluable for debugging CDC pipeline |

### Observability

| Technology | Recommended Version | Notes |
|---|---|---|
| OTel Collector | 0.100.x+ | Mandatory -- plan implies it but omits it from stack; decouples services from backend changes |
| Jaeger | 1.55+ (v2 unified binary) | Use jaegertracing/jaeger all-in-one; old multi-component deployment is outdated |
| Prometheus | 2.54.x or 3.0.x | Either works; 3.0 has new query engine but 2.54 is more proven |
| Loki | 3.x | Label-based; cheaper operationally than Elasticsearch |
| Promtail | 3.x | Acceptable for this project; production systems should migrate to Grafana Alloy |
| Grafana | 11.x | Query Jaeger + Prometheus + Loki in unified dashboards |

### Service Mesh and Gateway

| Technology | Recommended Version | Notes |
|---|---|---|
| Istio | 1.23.x or 1.24.x | Sidecar mode (not ambient); better documented; Kiali has full support |
| Kong | 3.7.x or 3.8.x | Kong Ingress Controller (KIC) pattern; jwt + rate-limiting plugins |
| Keycloak | 25.x or 26.x | Quarkus-based distribution (17+); RS256 JWT issuance; realm.json bootstrap |
| Kiali | 2.x | Service graph visualization; requires Istio + Prometheus |

### Infrastructure

| Technology | Recommended Version | Notes |
|---|---|---|
| Podman | 4.x or 5.x | Rootless preferred; rootful as fallback if kind networking fails |
| kind | 0.24.x | Use KIND_EXPERIMENTAL_PROVIDER=podman; validate single-node networking early |

### AI / MCP Layer

| Technology | Recommended Version | Notes |
|---|---|---|
| Python | 3.12 or 3.13 | MCP server; async-first (asyncio, httpx.AsyncClient) |
| mcp (PyPI) | 1.0.x+ | Verify exact package name and version; stdio transport for Claude Desktop |
| httpx | 0.27.x | Async HTTP client for Prometheus and Jaeger API calls |
| neo4j (Python driver) | 5.x | Use AsyncGraphDatabase.driver() for non-blocking Neo4j access |

---

## Table Stakes Features

Features without which the demo fails to prove its core thesis. Must exist before any differentiating work begins.

| # | Feature | Why Non-Negotiable | Phase |
|---|---|---|---|
| 1 | Local ACID money movement | Debit + credit + outbox in one TX; bedrock correctness proof | Phase 1 |
| 2 | Outbox Pattern + Debezium CDC | Solves dual-write problem; without it services just call each other | Phase 1 |
| 3 | Transfer state machine | Makes distributed workflow state explicit and auditable | Phase 1 |
| 4 | Double-entry ledger | Accounting correctness; every Australian bank uses this | Phase 1 |
| 5 | Redis idempotency keys on payment API | Prevents double-charge on retry; production-grade pattern | Phase 1 |
| 6 | Saga choreography (Kafka consumers) | Contrasts with ACID; shows when to use each pattern | Phase 1 |
| 7 | BSB validation on account creation | Australian banking authenticity | Phase 1 |
| 8 | AUSTRAC-style structured audit logs (ECS) | Regulatory authenticity; feeds Loki queries | Phase 1 |
| 9 | Distributed tracing end-to-end (Jaeger) | Enables MCP RCA tools; without this get_jaeger_trace() is meaningless | Phase 2 |
| 10 | Prometheus metrics + Grafana dashboard | Feeds Neo4j ETL and MCP query_metrics() tool | Phase 2 |
| 11 | Kubernetes deployment + Istio mTLS STRICT | Service mesh pattern; zero-trust networking | Phase 3 |
| 12 | Kong API gateway with JWT auth | External vs internal auth separation; rate limiting | Phase 3 |
| 13 | MCP server with core banking + RCA tools | The AI integration that makes this project unique | Phase 5 |

### Differentiators (Build if Phase 4-5 Have Bandwidth)

- Neo4j service graph with Prometheus ETL -- enables graph-traversal RCA that PromQL cannot express; what real SRE platforms do
- Autonomous RCA via MCP -- Claude reasoning across multiple observability signals; most AI + banking demos use mocked data
- BPAY lifecycle simulation -- distinct batch/scheduled payment pattern vs NPP real-time
- NPP PayID resolution -- Australian authenticity; low complexity relative to demo value
- APRA compliance event trail (CPS 234, CPS 230 references in compliance event payloads)
- Chaos engineering via Istio fault injection -- makes observability meaningful; without faults to observe, monitoring is theoretical

### Features to Defer to v2+

- Frontend UI -- Swagger UI + Claude Desktop is sufficient; React UI adds weeks with no pattern value
- Real NPP/BPAY rail integration -- requires RITS membership; not achievable for a demo
- Event sourcing / CQRS -- outbox table is close enough; adding a full event store competes for attention
- gRPC inter-service communication -- REST + OTel is simpler to demo; document as upgrade path
- Multi-tenancy -- adds row-level security and Keycloak realm complexity without pattern value
- Distributed saga orchestrator (Temporal/Conductor) -- choreography is the right choice here; mixing orchestration muddies the comparison

---

## Architecture Overview

### Layer Structure


The system has three trust boundaries and five horizontal layers:

```
[External]
  Kong     JWT validation, rate limiting, X-User-Id header injection from JWT sub claim
  Keycloak OAuth2 token issuer; NOT in the per-request hot path

[Mesh boundary]
  Istio    mTLS STRICT, circuit breaking, retries, trace propagation, Kiali

[Service tier]
  account-service       owns ACID money movement; writes outbox; no outbound sync calls during transfers
  payment-service       orchestrates NPP/BPAY; idempotency via Redis; calls account-service sync
  ledger-service        Kafka consumer only; double-entry bookkeeping
  notification-service  Kafka consumer only; customer alerts

[CDC layer]
  Debezium reads PostgreSQL WAL from each service outbox table and publishes to Kafka topics

[Event stream]
  Kafka KRaft (no ZooKeeper)
  Topics: banking.account.transfer-initiated, banking.account.transfer-completed,
          banking.payment.*, banking.ledger.*, compliance.events

[Graph + AI layer]
  Neo4j ETL    queries Prometheus Istio metrics every 30s; upserts OBSERVED_CALL edges
  Neo4j 5      service relationship graph; graph-traversal RCA queries
  Python MCP   exposes banking ops + observability as MCP tools for Claude Desktop
```

### Key Design Decisions

**Money atomicity guarantee:** account-service executes debit + credit + outbox INSERT in a single PostgreSQL transaction. If anything fails the entire TX rolls back. Debezium reads the WAL after commit -- events are guaranteed to be delivered eventually without any dual-write risk.

**Kong + Istio two-layer gateway:** Kong handles north-south (external) traffic -- JWT validation against Keycloak JWKS (cached locally, not per-request), rate limiting, X-User-Id header injection. Istio handles east-west (pod-to-pod) traffic -- mTLS identity, circuit breaking, retries. Kong runs as a pod with an Istio sidecar; its outbound calls to backend services automatically get mTLS via Envoy.

**Saga choreography, not orchestration:** account-service emits TransferInitiated. ledger-service and notification-service react autonomously. No central coordinator. Correct choice because downstream steps are fire-and-forget from the transfer perspective -- ledger failure does not require compensating the debit/credit.

**Neo4j is not a replacement for Prometheus:** Prometheus answers what the current value of metric X is. Neo4j answers what is connected to what and how healthy that connection is. The ETL joins them -- MERGE on service pairs, SET latency/error properties from Prometheus rate queries over 5-minute windows.

### Build Order (Hard Dependencies per Phase)

**Phase 1 -- Compose startup order:**
PostgreSQL -> Redis -> Kafka (KRaft) -> account/ledger/notification services -> payment-service -> Debezium Connect -> register connectors (only after outbox tables exist)

**Phase 2 -- Add observability to Compose:**
Jaeger -> OTel Collector -> Prometheus -> Loki -> Promtail -> Grafana -> restart services with OTel endpoint configured

**Phase 3 -- Kubernetes (full cut-over from Compose, not simultaneous):**
kind cluster -> Istio install -> label namespace istio-injection=enabled -> Keycloak -> PostgreSQL StatefulSets -> Redis -> Kafka -> Debezium -> observability stack -> banking services -> Kong KIC Helm -> Kong plugins -> VirtualServices + DestinationRules -> Kiali

**Phase 4 -- Graph:**
Neo4j (must have Istio metrics in Prometheus first; minimum 1 minute of data) -> init.cypher -> Neo4j ETL service -> verify OBSERVED_CALL edges appear after first 30s cycle

**Phase 5 -- MCP:**
Implement tools one at a time -> claude_desktop_config.json (stdio transport) -> test each tool against live system -> add root_cause_analysis composite tool last

---

## Critical Pitfalls to Avoid

Top pitfalls by severity with phase mapping. Full detail in PITFALLS.md.

### Phase 1

**P1-A: PostgreSQL WAL level not set to logical (CRITICAL)**
Debezium starts, appears connected, and produces zero events. Default wal_level=replica is insufficient.
Prevention: Add postgres -c wal_level=logical -c max_replication_slots=5 as container command args. Smoke test: SHOW wal_level; must return logical before registering any connector.

**P1-B: Replication slot leak causing WAL disk exhaustion (CRITICAL)**
podman-compose down without dropping replication slots leaves orphaned slots. PostgreSQL cannot purge WAL; disk fills; cluster dies.
Prevention: slot.drop.on.stop=true in Debezium connector config. Add make clean target that drops slots. Set max_replication_slots=3.

**P1-C: Balance read-modify-write race without SELECT FOR UPDATE (CRITICAL)**
Two concurrent transfers from the same account both read the same balance and both debit -- producing a negative balance. Default Read Committed isolation does not prevent this.
Prevention: @Lock(LockModeType.PESSIMISTIC_WRITE) on balance query. Integration test: 10 concurrent transfers from one account -- verify correct number succeed.

**P1-D: Monetary amounts stored as double instead of BigDecimal (CRITICAL)**
IEEE 754 rounding produces fractional cent errors. Balance assertions fail; audit reports show discrepancies.
Prevention: All monetary fields use BigDecimal in Java and DECIMAL(15,4) in PostgreSQL. RoundingMode.HALF_EVEN for all divisions.

**P1-E: No dead letter queue for failed Saga consumers (CRITICAL)**
A failed Kafka message either retries forever (blocking partition progress) or is silently dropped. Both are unacceptable for financial events.
Prevention: Configure DeadLetterPublishingRecoverer + DefaultErrorHandler with exponential backoff from day one. DLQ topic: {topic-name}.DLT. No automatic retry for financial events.

**P1-F: Both CDC and polling outbox active simultaneously (HIGH)**
If the published column polling scheduler AND Debezium CDC are both active, events are published twice.
Prevention: Pick CDC-only outbox, no scheduler. Do not mix patterns.

**P1-G: Compensation events not idempotent (CRITICAL)**
Compensation events may be delivered more than once via Kafka at-least-once. Without idempotency, accounts are double-credited.
Prevention: saga_log table with saga_id + step + status as idempotency store. State machine rejects re-entry into COMPENSATING if already in CANCELLED.

### Phase 2

**P2-A: Manual TracerProvider bean conflicting with Spring Boot 4 auto-configuration (MEDIUM)**
Two TracerProvider instances cause duplicate or dropped spans.
Prevention: Use only application.yml config for OTel. Inject io.micrometer.tracing.Tracer for custom spans, not raw OTel Tracer.

**P2-B: ECS log format field paths not matching Promtail pipeline (MEDIUM)**
ECS produces nested JSON. Promtail pipeline stage must reference exact paths; mismatch causes Loki label extraction to fail silently.
Prevention: Verify ECS field names from a running service before writing Promtail config.

### Phase 3

**P3-A: Namespace missing istio-injection: enabled label (CRITICAL)**
Pods start without Istio sidecar. mTLS absent, Kiali graph empty, circuit breaking inactive.
Prevention: Namespace YAML must include istio-injection: enabled before pods are deployed. Verify every pod shows 2/2 READY.

**P3-B: Kong JWT plugin configured with HS256 instead of RS256 (CRITICAL)**
Keycloak issues RS256 JWTs. Kong configured with HS256 causes all API calls to return 401.
Prevention: Configure Kong JWT consumer with algorithm: RS256 and Keycloak realm RSA public key from JWKS endpoint.

**P3-C: X-User-Id header forgeable by external clients (CRITICAL)**
Without stripping, a client can forge X-User-Id: admin and services will trust it.
Prevention: Kong request-transformer removes X-User-Id from incoming requests before JWT plugin injects the validated value.

**P3-D: OOM on kind cluster with full observability stack (HIGH)**
Istio sidecars plus full observability plus Spring Boot JVM heap exhausts available RAM. Pods OOMKill each other.
Prevention: Set memory requests and limits on all pods. Use -XX:MaxRAMPercentage=70. Allocate 12GB or more to kind node.

**P3-E: MCP server cannot reach kind cluster from Windows host (HIGH)**
Podman uses WSL2 VM. kind runs inside that VM. Claude Desktop MCP server runs on Windows and cannot resolve cluster-internal hostnames.
Prevention: Use kubectl port-forward for each required service. Maintain a port-forward script as background processes.

### Phase 4

**P4-A: ETL overwrites Neo4j edge metrics with instantaneous values (HIGH)**
SET overwrites on each 30-second cycle. Error spikes are lost between runs.
Prevention: Query Prometheus using rate(metric[5m]) windows, not instant values. Add last_updated timestamp to edges.

**P4-B: Prometheus metric names not matching actual Spring Boot 4 output (HIGH)**
ETL written against assumed metric names silently returns empty results if names differ between Spring Boot versions.
Prevention: Run raw PromQL against the live system before writing ETL. Enumerate actual metrics via /api/v1/label/__name__/values.

### Phase 5

**P5-A: MCP write tools with no confirmation gate (CRITICAL)**
publish_event allows Claude to publish arbitrary Kafka events. Incorrect analysis could corrupt transfer state.
Prevention: All write tools default to dry_run=True. Annotate dangerous tools clearly. Add audit logging to every write tool invocation.

**P5-B: Unstructured error responses from MCP tools (CRITICAL)**
Raw Python tracebacks prevent Claude from distinguishing retryable from non-retryable failures.
Prevention: Every tool returns structured result with success bool, data, and error object containing code, message, and retryable fields.

**P5-C: Synchronous MCP server blocking on slow backends (HIGH)**
Single-threaded synchronous Python blocks all tool calls when one backend is slow.
Prevention: async def for all tool handlers. httpx.AsyncClient for HTTP. AsyncGraphDatabase.driver() for Neo4j. Explicit timeouts.

**P5-D: Windows path issues in claude_desktop_config.json (MEDIUM)**
Most MCP examples target macOS/Linux. Windows + WSL2 paths cause Claude Desktop to show MCP server as disconnected.
Prevention: Use absolute Windows paths. Test MCP server startup independently. Include start-mcp.bat in the repo.

---

## Open Questions

### Before Phase 1 (Blockers)

1. Is Spring Boot 4.0.x GA and stable?
   Check https://spring.io/projects/spring-boot. If not GA or released less than 2 months ago, use 3.4.x. This is the single highest-risk item.

2. Does Podman rootless work with kind on this machine?
   Validate in Week 1: start a kind cluster with KIND_EXPERIMENTAL_PROVIDER=podman, deploy a test pod, verify DNS. If it fails, switch to rootful Podman.

### Before Phase 1 (Decide Early)

3. Spring State Machine vs hand-rolled enum FSM?
   Recommendation is Spring State Machine for a 6-state lifecycle. Verify Spring Boot 4-compatible release before committing.

4. Remove place_order() from MCP tool list.
   This does not belong in a banking domain. Replace with list_accounts() and get_audit_trail().

5. CDC-only outbox or polling outbox?
   For CDC-only (recommended), the published column is unnecessary. Decide once and document the decision.

### Before Phase 2

6. Exact OTel property key names for the Spring Boot version in use.
   Verify management.otlp.tracing.endpoint is the correct key for the actual version.

### Before Phase 3

7. Kafka KRaft environment variable configuration.
   Verify exact compose.yml environment variables for broker+controller combined mode.

8. Istio version and Kubernetes compatibility matrix.
   Check https://istio.io/latest/docs/releases/supported-releases/ before starting Phase 3.

9. Kong JWT plugin vs JWKS-based dynamic validation.
   The built-in jwt plugin breaks on Keycloak key rotation. Decide before configuring Kong.

### Before Phase 4

10. Verify actual Prometheus Istio metric label names against the running system.
    Run raw PromQL against the live instance before writing the ETL.

### Before Phase 5

11. MCP Python SDK current version and transport API.
    SDK was actively evolving at research cutoff. Verify package name, version, stdio transport API. Pin in requirements.txt.

12. Neo4j Bloom availability in Community Server container image.
    Neo4j Browser covers all Cypher needed. Verify before planning Bloom-dependent demo scenarios.

---

## Implications for Roadmap

### Suggested Phase Structure

The 5-phase structure is correct and confirmed by research. Phase dependencies are hard and cannot be reordered.

Phase 1 -- ACID Core + CDC Pipeline (Docker Compose)
Rationale: Everything downstream depends on correct money movement and reliable event delivery.
Delivers: ACID transfers, outbox pattern, Debezium CDC, Kafka Saga, state machine, ledger, Redis idempotency, BSB validation, AUSTRAC audit logs.
Must avoid: WAL level config, SELECT FOR UPDATE, BigDecimal everywhere, DLQ from day one, replication slot leak prevention.

Phase 2 -- Observability (add to Compose)
Rationale: Tracing and metrics must be proven before Kubernetes networking can obscure them.
Delivers: End-to-end Jaeger traces, Prometheus metrics, Loki log collection, Grafana dashboards.
Must avoid: Manual TracerProvider beans, ECS field path mismatch in Promtail pipeline.
Research flag: Kafka trace header propagation (TextMapPropagator) is the one non-trivial OTel step.

Phase 3 -- Kubernetes + Service Mesh
Rationale: Istio and Kong are the most operationally complex elements. Full cut-over after Phases 1-2 are solid.
Delivers: kind cluster, Istio mTLS STRICT, Kong JWT auth, Keycloak, all services on Kubernetes.
Must avoid: Namespace label before pod deployment, RS256 JWT config, X-User-Id header stripping, resource limits, port-forward script.
Research flag: Phase 3 benefits from dedicated research on Podman + kind + Istio compatibility matrix for Windows.

Phase 4 -- Graph + RCA Foundation
Rationale: Neo4j ETL depends on Istio metrics in Prometheus. Graph RCA is only meaningful with real traffic data.
Delivers: Neo4j service graph with live Prometheus ETL, Cypher-based RCA queries.
Must avoid: Verify Prometheus metric label names before writing ETL, use rate queries not instant values.

Phase 5 -- MCP AI Integration
Rationale: All observability signals must be real and populated. Tools built against mocked data fail on real data.
Delivers: Python MCP server, core banking tools, observability tools, autonomous RCA demo.
Must avoid: Async tool handlers, structured error schema, write tool safety defaults, absolute Windows paths.
Research flag: MCP SDK API surface needs verification before writing tools.

### Phases Needing Dedicated Research

| Phase | Topic | Why |
|---|---|---|
| Phase 1 | Spring Boot 4.0.x GA verification | Cannot start without this; determines entire dependency tree |
| Phase 1 | Podman rootless + kind validation | Must prototype in Week 1 |
| Phase 3 | Istio + kind + Podman compatibility matrix | Networking complexity; Phase 3 is highest operational risk |
| Phase 5 | MCP Python SDK current API | SDK was evolving; tool registration patterns may have changed |

### Phases With Well-Documented Patterns (Lower Research Risk)

- Outbox pattern + Debezium connector -- stable since 2019; PITFALLS.md covers all known edge cases
- Saga choreography with Kafka and DLQ -- well-established Spring Kafka pattern
- Keycloak realm setup and Kong JWT plugin -- standard OAuth2/OIDC integration
- Neo4j Cypher for graph RCA -- stable query language; patterns in ARCHITECTURE.md are verified

---

## Confidence Assessment

| Area | Confidence | Notes |
|---|---|---|
| Java / Spring Boot patterns | HIGH | Established patterns; training data covers Spring Boot 3/4 extensively |
| Spring Boot 4.0.x specific version | LOW | Cannot verify GA status without external access |
| Outbox + Debezium mechanics | HIGH | Stable pattern since 2019; well-documented |
| Kafka KRaft mode | HIGH | Production-ready since 3.3; ZooKeeper removal in 3.8 is fact |
| Australian banking domain | HIGH | BSB, NPP, BPAY, AUSTRAC are well-documented stable public standards |
| Istio sidecar mode patterns | HIGH | Stable since Istio 1.5+; pitfalls are well-documented |
| Podman rootless + kind on Windows | MEDIUM | Known friction; must be validated hands-on |
| MCP Python SDK | MEDIUM | SDK was actively evolving at knowledge cutoff (August 2025) |
| Observability stack image versions | MEDIUM | Directional estimates; all image tags must be verified |
| Neo4j ETL Prometheus label names | MEDIUM | Label names depend on Istio version; verify against running instance |

Overall confidence: MEDIUM-HIGH. Architectural patterns and design decisions are high confidence. Operational risk areas (Spring Boot version, Podman compatibility, MCP SDK stability) are flagged with concrete validation steps.

---

## Aggregated Sources

All research was conducted from training knowledge (cutoff August 2025) -- external network access was unavailable. Verify all version numbers manually before implementation.

| Source | What to Verify |
|---|---|
| https://spring.io/projects/spring-boot | Spring Boot 4.0.x GA status (BLOCKER before Phase 1) |
| https://github.com/spring-projects/spring-boot/releases | Exact stable version, release notes |
| https://kafka.apache.org/downloads | Kafka 3.8+ KRaft setup, current stable version |
| https://debezium.io/releases/ | Debezium 3.x stable version, PostgreSQL connector docs |
| https://istio.io/latest/docs/releases/supported-releases/ | Istio / Kubernetes compatibility matrix |
| https://konghq.com/install/ | Kong Gateway current version |
| https://www.keycloak.org/downloads | Keycloak current version |
| https://pypi.org/project/mcp/ | MCP Python SDK current version and API |
| https://github.com/modelcontextprotocol/python-sdk | MCP SDK changelog, stdio transport API |
| https://neo4j.com/download-center/ | Neo4j 5.x community image tag, Bloom licensing |
| https://grafana.com/grafana/download | Grafana, Loki, Promtail/Alloy current versions |
| https://www.postgresql.org/ftp/latest/ | PostgreSQL 16/17 current patch version |
