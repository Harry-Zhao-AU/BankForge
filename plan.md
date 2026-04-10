# Core Banking Service Mesh — Project Plan

## Overview

A realistic Australian core banking platform demonstrating enterprise
microservices patterns with a full service mesh, observability stack,
and AI agent integration.

---

## Services

| Service | Responsibility | Pattern |
|---|---|---|
| `account-service` | Accounts, balances, history | Local ACID TX |
| `payment-service` | Transfers, NPP, BPAY | Local ACID TX |
| `ledger-service` | Double-entry bookkeeping | Local ACID TX |
| `notification-service` | Email, SMS, push alerts | Saga + Kafka |

### Transfer Flow

```
account-service
    │
    │ LOCAL ACID TX (same DB — debit + credit + outbox)
    ├── debit account A
    └── credit account B
    │
    │ Debezium CDC
    ▼
Kafka
    ├── Saga → ledger-service      (async record)
    ├── Saga → notification-service (async alert)
    └── Saga → fraud-service        (async check)
```

---

## Transaction Patterns

| Pattern | Used For | Reason |
|---|---|---|
| **Local ACID TX** | Money movement | Never partial, strongest consistency |
| **Saga + Kafka** | Notification, audit, fraud | Async, eventual consistency ok |
| **Outbox + CDC** | All services | Reliable event delivery, no dual-write |
| **State Machine** | Transfer lifecycle | Track every state transition |
| **Idempotency keys** | Payment API | Prevent double charge (Redis) |

### Transfer State Machine

```
PENDING → PAYMENT_PROCESSING → PAYMENT_DONE
                                    │
                             STOCK_RESERVING → CONFIRMED
                                    │
                              COMPENSATING → CANCELLED
```

---

## Tech Stack

| Category | Technology | Notes |
|---|---|---|
| Language | Java 21 LTS | LTS, virtual threads |
| Framework | Spring Boot 4.0.5 | Latest stable, built-in OTel |
| Database | PostgreSQL 15 | Transactional data + outbox table |
| Cache | Redis 7 | Idempotency keys, JWT cache |
| Messaging | Kafka | Event streaming |
| CDC | Debezium | Postgres outbox → Kafka |
| Graph DB | Neo4j 5 | Service relationship graph |
| Tracing | Jaeger | Distributed traces |
| Metrics | Prometheus | Time-series metrics |
| Logs | Loki + Promtail | Structured audit logs |
| Dashboard | Grafana | Unified: traces + metrics + logs |
| Service Mesh | Istio | mTLS, circuit breaking, retries |
| API Gateway | Kong | JWT auth, rate limiting, routing |
| Auth | Keycloak | OAuth2 + OIDC + JWT |
| Mesh UI | Kiali | Live Istio service graph |
| Containers | Podman | Daemonless, rootless |
| Orchestration | Kubernetes (kind) | Local K8s cluster |
| AI Interface | Python MCP server | Claude integration |

---

## Project Structure

```
core-banking-mesh/
├── services/
│   ├── account-service/
│   │   ├── src/main/java/com/banking/account/
│   │   │   ├── domain/
│   │   │   │   ├── Account.java
│   │   │   │   ├── Transfer.java
│   │   │   │   └── TransferState.java
│   │   │   ├── saga/
│   │   │   │   └── TransferSagaHandler.java
│   │   │   ├── statemachine/
│   │   │   │   └── TransferStateMachine.java
│   │   │   └── outbox/
│   │   │       └── OutboxPublisher.java
│   │   ├── pom.xml
│   │   └── Dockerfile
│   ├── payment-service/
│   ├── ledger-service/
│   └── notification-service/
│
├── mcp-server/               ← Python
│   ├── main.py
│   ├── tools/
│   │   ├── graph_tools.py
│   │   ├── metrics_tools.py
│   │   └── trace_tools.py
│   └── Dockerfile
│
├── infrastructure/
│   ├── kafka/
│   │   └── topics.yml
│   ├── debezium/
│   │   └── connector.json
│   ├── keycloak/
│   │   └── realm.json
│   └── neo4j/
│       └── init.cypher
│
├── k8s/
│   ├── namespaces/
│   │   └── banking.yaml
│   ├── services/
│   │   ├── account-service.yaml
│   │   ├── payment-service.yaml
│   │   ├── ledger-service.yaml
│   │   └── notification-service.yaml
│   ├── istio/
│   │   ├── gateway.yaml
│   │   ├── virtual-services.yaml
│   │   └── destination-rules.yaml
│   └── kong/
│       ├── ingress.yaml
│       └── plugins.yaml
│
├── observability/
│   ├── prometheus/
│   │   └── prometheus.yml
│   ├── grafana/
│   │   └── dashboards/
│   ├── loki/
│   │   └── loki-config.yml
│   └── promtail/
│       └── promtail-config.yml
│
├── neo4j-etl/
│   └── GraphIngestionService.java
│
├── compose.yml               ← local dev (Phase 1-2)
└── README.md
```

---

## Data Stores Detail

### PostgreSQL — per service
```sql
-- Each service has its own DB schema
-- account-service
CREATE TABLE accounts (
    id          UUID PRIMARY KEY,
    bsb         VARCHAR(6),
    number      VARCHAR(10),
    balance     DECIMAL(15,2),
    status      VARCHAR(20),
    created_at  TIMESTAMP
);

CREATE TABLE outbox (
    id              UUID PRIMARY KEY,
    aggregate_id    UUID,
    aggregate_type  VARCHAR(50),
    event_type      VARCHAR(50),
    payload         JSONB,
    created_at      TIMESTAMP,
    published       BOOLEAN DEFAULT FALSE
);
```

### Redis — idempotency + cache
```
key: "idempotency:TXN-{id}"   TTL: 24h
key: "jwt:{token_hash}"        TTL: 5min
key: "balance:{account_id}"    TTL: 30s
```

### Neo4j — service graph
```cypher
MERGE (a:Service {name: $source})
MERGE (b:Service {name: $target})
MERGE (a)-[r:OBSERVED_CALL]->(b)
SET r.avg_latency_ms = $latency,
    r.p99_latency_ms = $p99,
    r.error_count    = $errors,
    r.call_count     = $calls,
    r.updated_at     = $now
```

---

## Observability Stack

### Three Pillars

| Pillar | Tool | What it answers |
|---|---|---|
| Traces | Jaeger | Why was transfer TXN-123 slow? |
| Metrics | Prometheus | What is avg latency this week? |
| Logs | Loki | What exactly failed and why? |
| Graph | Neo4j | Which service is the bottleneck? |

### Spring Boot 4 — OTel Built-in
```yaml
# application.yml — no manual TracerProvider needed!
management:
  otlp:
    tracing:
      endpoint: http://otel-collector:4317
  tracing:
    sampling:
      probability: 1.0

spring:
  threads:
    virtual:
      enabled: true   # Java 21 virtual threads

logging:
  structured:
    format:
      console: ecs    # structured JSON logs → Loki
```

---

## Service Mesh

### Traffic Flow
```
Internet
    │ HTTPS
    ▼
Kong (external)
├── JWT validation (Keycloak)
├── Rate limiting (100 req/min)
└── Route to services
    │
    ▼
Istio (internal)
├── mTLS automatic
├── Circuit breaking
├── Retries
└── Load balancing
    │
    ▼
Microservices
(just read X-User-Id header — no auth code!)
```

### Kong Plugins
```yaml
# JWT auth
plugin: jwt
config:
  claims_to_verify: [exp]

# Rate limiting
plugin: rate-limiting
config:
  minute: 100
  policy: local
```

### Istio VirtualService
```yaml
apiVersion: networking.istio.io/v1alpha3
kind: VirtualService
metadata:
  name: payment-service
spec:
  hosts:
  - payment-service
  http:
  - route:
    - destination:
        host: payment-service
    timeout: 10s
    retries:
      attempts: 3
      perTryTimeout: 3s
```

---

## Auth

| Client | Method | Provider |
|---|---|---|
| ui-web (human users) | OAuth2 + OIDC | Keycloak |
| MCP server (AI agent) | API Key | Kong |
| Service-to-service | mTLS | Istio (automatic) |

---

## MCP Server Tools

```python
# Claude can call these tools:

place_order(user_id, items, amount)
check_balance(account_id)
track_transfer(transfer_id)
get_service_health()
find_slow_services(threshold_ms=500)
root_cause_analysis(service_name)
query_service_graph(cypher)
query_metrics(promql)
get_jaeger_trace(trace_id)
publish_event(topic, event_type, payload)
```

---

## Phases

### Phase 1 — Core Services (Week 1–2)

**Goal:** 4 services running locally with patterns implemented

**Stack:**
- Java 21 + Spring Boot 4.0.5
- PostgreSQL + Kafka + Redis + Debezium
- Podman Compose

**Deliverables:**
- [ ] account-service (CRUD + local ACID transfer)
- [ ] payment-service (NPP payment flow)
- [ ] ledger-service (double-entry bookkeeping)
- [ ] notification-service (Kafka consumer)
- [ ] Outbox pattern on all services
- [ ] Debezium CDC connector
- [ ] State machine for transfer lifecycle
- [ ] Redis idempotency keys
- [ ] compose.yml running all services

---

### Phase 2 — Observability (Week 3)

**Goal:** Full visibility into service behaviour

**Stack:**
- OTel (Spring Boot 4 built-in)
- Jaeger + Prometheus + Loki + Grafana

**Deliverables:**
- [ ] OTel configured in all services
- [ ] Jaeger traces flowing
- [ ] Prometheus metrics scraping
- [ ] Loki + Promtail log collection
- [ ] Grafana dashboard (traces + metrics + logs)
- [ ] Banking-specific alerts (error rate, latency)

---

### Phase 3 — Service Mesh (Week 4–5)

**Goal:** Move from Podman Compose to K8s + Istio

**Stack:**
- Kubernetes (kind)
- Istio + Kong + Keycloak + Kiali

**Deliverables:**
- [ ] kind cluster running locally
- [ ] Istio installed + sidecar injection enabled
- [ ] All services deployed as K8s manifests
- [ ] Kong ingress with JWT + rate limiting
- [ ] Keycloak realm configured
- [ ] Kiali dashboard showing live graph
- [ ] mTLS verified between services

---

### Phase 4 — Neo4j Graph (Week 6)

**Goal:** Service relationship graph with live metrics

**Stack:**
- Neo4j 5 Community
- ETL service (Spring Boot scheduled job)
- Neo4j Bloom

**Deliverables:**
- [ ] Neo4j running in K8s
- [ ] ETL service querying Prometheus every 30s
- [ ] OBSERVED_CALL relationships with metrics on edges
- [ ] Neo4j Bloom showing service graph
- [ ] Cypher queries for root cause analysis

---

### Phase 5 — MCP + AI Agent (Week 7+)

**Goal:** Claude can query and operate the banking system

**Stack:**
- Python MCP server
- Claude Desktop integration

**Deliverables:**
- [ ] MCP server with all tools implemented
- [ ] Claude can check account balance
- [ ] Claude can trace slow transfers
- [ ] Claude can query Neo4j service graph
- [ ] Claude can find root cause of latency issues
- [ ] claude_desktop_config.json configured

---

## Portfolio Value

This project demonstrates:

### Australian Banking Specific
- Double-entry ledger (every Australian bank uses this)
- BSB/account number handling
- NPP (New Payments Platform) payment patterns
- AUSTRAC-style audit logging
- APRA-style compliance event trails

### Enterprise Patterns
- Local ACID transactions for money movement
- Saga pattern for async workflows
- Outbox + CDC for reliable messaging
- State machine for lifecycle management
- Idempotency keys for safe retries

### Infrastructure
- Kubernetes + Istio service mesh
- Kong API gateway with auth
- Full observability (traces + metrics + logs + graph)
- Neo4j graph-powered root cause analysis

### Modern Java
- Java 21 virtual threads
- Spring Boot 4 built-in OTel
- Structured logging (ECS format)
- HTTP Service Clients (declarative)

### AI Integration
- MCP server exposing banking operations
- Claude agent doing autonomous RCA
- Neo4j Cypher queries via MCP

---

## Quick Start (Phase 1)

```bash
# Prerequisites
podman machine init && podman machine start

# Clone and start
git clone https://github.com/yourname/core-banking-mesh
cd core-banking-mesh
podman-compose up --build

# Verify services
curl http://localhost:8000/actuator/health  # account-service
curl http://localhost:8001/actuator/health  # payment-service
curl http://localhost:8002/actuator/health  # ledger-service
curl http://localhost:8003/actuator/health  # notification-service

# Access UIs
open http://localhost:16686   # Jaeger
open http://localhost:9090    # Prometheus
open http://localhost:3001    # Grafana
open http://localhost:7474    # Neo4j Browser
```

---

*Generated: April 2026*
*Stack: Java 21 + Spring Boot 4.0.5 + Istio + Kong + Neo4j + Kafka*