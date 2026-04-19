# BankForge

A realistic Australian core banking platform demonstrating enterprise microservices patterns — four Java/Spring Boot services wired together with local ACID transactions, Saga/Outbox/CDC messaging, full observability (Jaeger + Prometheus + Loki + Grafana), and a foundation for an AI-driven root cause analysis system.

**Goal:** A running, end-to-end system where every enterprise pattern (ACID, Saga, Outbox, CDC, distributed tracing, service mesh) is implemented and queryable — proving the patterns work together, not just in theory.

---

## Architecture Overview

```
                         ┌─────────────────────────────────┐
                         │         Kong API Gateway        │
                         └────────────────┬────────────────┘
                                          │
          ┌───────────────────────────────┼──────────────────────────────┐
          │                              │                               │
  ┌───────▼──────┐              ┌────────▼───────┐              ┌────────▼──────┐
  │   account-   │◄─────────────│   payment-     │              │  notification-│
  │   service    │   REST +      │   service      │              │  service      │
  │   :8081      │   Resilience4j│   :8082        │              │  :8084        │
  └──────┬───────┘              └────────┬───────┘              └───────▲───────┘
         │ ACID TX                       │ Redis idempotency             │
         │ (debit + credit               │ Transfer FSM                  │ Kafka
         │  + outbox row)                │                               │
  ┌──────▼───────┐              ┌────────▼───────┐              ┌────────┴──────┐
  │  account-db  │              │  payment-db    │              │  ledger-      │
  │  PostgreSQL  │              │  PostgreSQL    │              │  service      │
  │  (WAL=logical│              │                │              │  :8083        │
  │   for CDC)   │              └────────────────┘              └───────▲───────┘
  └──────┬───────┘                                                      │ Kafka
         │ WAL                                                           │
  ┌──────▼───────┐   Outbox SMT   ┌───────────────┐                    │
  │   Debezium   ├───────────────►│     Kafka      ├────────────────────┘
  │   Connect    │                │  (KRaft 3.9.2) │
  │   :8085      │                └───────────────┘
  └──────────────┘

  OTel Collector → Jaeger (traces) + Prometheus (metrics) + Loki (logs) → Grafana
```

### Services

| Service | Port | Responsibility |
|---------|------|----------------|
| `account-service` | 8081 | Account lifecycle, ACID balance transfers, outbox writes, BSB validation |
| `payment-service` | 8082 | External API entry point, Redis idempotency, transfer FSM, circuit breaker to account-service |
| `ledger-service` | 8083 | Consumes CDC events, records double-entry bookkeeping, publishes confirmation events |
| `notification-service` | 8084 | Async alerts, structured AUSTRAC-style audit logging |

### Infrastructure

| Component | Version | Role |
|-----------|---------|------|
| PostgreSQL | 17 Alpine | Per-service databases, WAL logical replication for CDC |
| Kafka | 3.9.2 (KRaft) | Event streaming, no ZooKeeper |
| Redis | 7.2 | Idempotency keys (24 h TTL) |
| Debezium Connect | 3.1 | CDC: polls outbox tables → Kafka topics |
| OTel Collector | 0.149.0 | Receives OTLP, fans out to Jaeger/Prometheus/Loki |
| Jaeger | 2.17.0 | Distributed trace UI |
| Prometheus | 3.10.0 | Metrics |
| Loki | 3.7.0 | Log aggregation |
| Grafana | 11.6.14 | Unified dashboards |

---

## Key Patterns Implemented

### ACID Transfer (account-service)
Debit, credit, and outbox row written in a single PostgreSQL transaction. Balance reads use `SELECT FOR UPDATE` (pessimistic lock) to prevent concurrent overdraft. All monetary values stored as `DECIMAL(15,4)`.

### Outbox + CDC (Debezium → Kafka)
Transactional outbox table captures domain events inside the same ACID transaction as the balance update. Debezium reads PostgreSQL WAL and routes events to Kafka topics via the EventRouter SMT — no dual-write, no lost events.

### Saga via Kafka
`payment-service` orchestrates the transfer saga. `ledger-service` consumes `banking.transfer.initiated`, posts the double-entry journal, and publishes `banking.transfer.confirmed` (or routed to DLT on failure). State transitions are event-driven.

### Transfer State Machine
Enum-based FSM in the `common` module:
```
PENDING → PAYMENT_PROCESSING → PAYMENT_DONE → POSTING → CONFIRMED
                                                       ↘ COMPENSATING → CANCELLED
```

### Idempotency
`payment-service` checks a Redis key before forwarding any transfer request. Duplicate requests within 24 hours return the original response without re-executing.

### Circuit Breaker
`payment-service → account-service` calls are wrapped in a Resilience4j circuit breaker. Trips after 5 failures in a 10-call sliding window; half-open probe after 30 s.

### Observability
Spring Boot 4 auto-configures OTel. Every service exports traces, metrics, and logs via OTLP to the collector. Grafana datasources (Jaeger, Prometheus, Loki) are pre-provisioned for correlation.

---

## Quick Start

### Prerequisites
- Podman (rootful, WSL2 backend on Windows) — not Docker
- Java 21 + Maven (or use IntelliJ bundled Maven)
- `kind` 0.24+ for Kubernetes phases

### 1. Start the full stack

```bash
podman compose up -d
```

This starts all four service databases, Kafka, Redis, Debezium Connect, and the full observability stack.

### 2. Build and run services

```bash
# From repo root
"C:/Program Files/JetBrains/IntelliJ IDEA Community Edition 2025.2.4/plugins/maven/lib/maven3/bin/mvn" \
  -Dmaven.test.skip=true clean package

podman compose up -d account-service payment-service ledger-service notification-service
```

### 3. Register the Debezium connector

```bash
bash scripts/register-connector.sh
# or manually:
curl -s -X POST http://localhost:8085/connectors \
  -H "Content-Type: application/json" \
  -d @infra/debezium/outbox-connector.json
```

### 4. Validate observability

```bash
bash scripts/smoke-test-obs.sh
```

### Access UIs

| UI | URL | Credentials |
|----|-----|-------------|
| Grafana | http://localhost:3000 | anonymous admin |
| Jaeger | http://localhost:16686 | — |
| Prometheus | http://localhost:9090 | — |
| Debezium Connect | http://localhost:8085 | — |

---

## Project Structure

```
BankForge/
├── account-service/          # Account management + ACID transfers
├── payment-service/          # Transfer orchestration + idempotency
├── ledger-service/           # Double-entry bookkeeping
├── notification-service/     # Audit logging + async alerts
├── common/                   # Shared FSM, DTOs, validation
├── infra/
│   ├── observability/        # OTel collector, Prometheus, Loki, Grafana configs
│   └── debezium/             # Connector JSON
├── scripts/                  # Smoke tests, connector registration, kind setup
├── docs/                     # Architecture notes
├── .planning/                # Roadmap, state, requirements (GSD workflow)
├── compose.yml               # Full dev stack
└── pom.xml                   # Parent POM (Java 21, Spring Boot 4.0.5)
```

---

## Roadmap

| Phase | Focus | Status |
|-------|-------|--------|
| 1 | Service scaffold, ACID transfers, Redis idempotency, transfer FSM | Complete |
| 1.1 | Kafka KRaft, Debezium CDC, DLT topics, AUSTRAC audit logging | Complete |
| 2 | Full observability — OTel → Jaeger/Prometheus/Loki/Grafana | Complete |
| 3 | kind cluster, Istio mTLS, Kong gateway, Keycloak JWT | Upcoming |
| 4 | Neo4j service graph, Cypher RCA queries, Prometheus ETL | Planned |
| 5 | Python MCP server, Claude Desktop integration, autonomous RCA | Planned |

See `.planning/ROADMAP.md` for detailed success criteria and `.planning/STATE.md` for current progress.

---

## Tech Stack

- **Java 21** + **Spring Boot 4.0.5** — virtual threads, built-in OTel auto-configuration
- **Kafka 3.9.2** (KRaft, no ZooKeeper) + **Debezium 3.1** CDC
- **PostgreSQL 17** per-service, **Redis 7.2** for idempotency
- **Resilience4j 2.2** circuit breaker, **Flyway 10** migrations, **MapStruct 1.6** DTO mapping
- **OpenTelemetry Collector 0.149**, **Jaeger 2.17**, **Prometheus 3.10**, **Loki 3.7**, **Grafana 11.6**
- **Podman** (rootful, WSL2) — no Docker Desktop dependency
