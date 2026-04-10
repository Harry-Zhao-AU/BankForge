# BankForge

## What This Is

A realistic Australian core banking platform demonstrating enterprise microservices patterns — 4 Java/Spring Boot services with local ACID transactions, Saga/Outbox/CDC messaging, full observability (Jaeger + Prometheus + Loki + Grafana), an Istio service mesh on Kubernetes, and a Claude MCP server that queries the live system. Built as a learning sandbox and long-term foundation for an AI-driven root cause analysis system.

## Core Value

A running, end-to-end system where every enterprise pattern (ACID, Saga, Outbox, mTLS, distributed tracing) is implemented and queryable via AI agent — proving the patterns work together, not just in theory.

## Requirements

### Validated

(None yet — ship to validate)

### Active

- [ ] 4 Spring Boot 4 / Java 21 services: account, payment, ledger, notification
- [ ] Local ACID transactions for money movement (debit + credit + outbox in one TX)
- [ ] Saga + Kafka for async downstream workflows (ledger, notification, fraud)
- [ ] Outbox + Debezium CDC for reliable event delivery (no dual-write)
- [ ] Transfer state machine (PENDING → PROCESSING → DONE / CANCELLED)
- [ ] Redis idempotency keys on payment API
- [ ] Full observability: OTel traces → Jaeger, metrics → Prometheus, logs → Loki, dashboards in Grafana
- [ ] Kubernetes (kind) cluster with Istio sidecar injection and mTLS
- [ ] Kong API gateway with JWT auth (Keycloak) and rate limiting
- [ ] Neo4j service graph: OBSERVED_CALL edges with latency/error metrics from Prometheus ETL
- [ ] Python MCP server exposing banking operations and RCA tools to Claude
- [ ] Claude can perform autonomous root cause analysis via MCP (trace slow transfers, query Neo4j, read metrics)

### Out of Scope

- Production deployment — local dev environment only (kind cluster)
- Real NPP/BPAY integration — patterns are simulated, not live rails
- Frontend UI — API + observability dashboards only
- Multi-tenancy / customer onboarding — single-realm Keycloak config

## Context

- **Domain:** Australian banking — BSB/account numbers, NPP payment patterns, double-entry ledger, AUSTRAC-style audit logging
- **Primary goal:** Learn enterprise microservices patterns hands-on
- **Secondary goal:** This system becomes the foundation for a more advanced AI-driven RCA system (Phase 4-5 are the seeds)
- **Runtime:** Podman + kind (local K8s), daemonless/rootless containers
- **Phase progression:** Compose-first (Phase 1-2) → K8s migration (Phase 3) → Graph + AI layer (Phase 4-5)
- **Plan source:** plan.md — treated as a draft; architecture and phases are flexible

## Constraints

- **Tech stack:** Java 21 LTS + Spring Boot 4.0.5 — locked for virtual threads and built-in OTel
- **Container runtime:** Podman (daemonless, rootless) — not Docker
- **Local only:** kind cluster, no cloud provider dependencies
- **Database isolation:** Each service owns its own PostgreSQL schema (no shared DB)

## Key Decisions

| Decision | Rationale | Outcome |
|----------|-----------|---------|
| Local ACID TX for transfers (not distributed saga) | Money movement must be atomic — partial debit/credit is unacceptable | — Pending |
| Outbox + Debezium CDC instead of direct Kafka publish | Eliminates dual-write problem; guaranteed at-least-once delivery | — Pending |
| Neo4j for service graph (not just Prometheus) | Cypher queries enable relationship traversal for RCA that PromQL can't express | — Pending |
| MCP server in Python (not Java) | Lighter footprint; Python MCP SDK is mature; AI tooling ecosystem | — Pending |
| Kong external + Istio internal (two-layer mesh) | Kong handles external auth/rate-limiting; Istio handles internal mTLS — services need zero auth code | — Pending |

## Evolution

This document evolves at phase transitions and milestone boundaries.

**After each phase transition** (via `/gsd-transition`):
1. Requirements invalidated? → Move to Out of Scope with reason
2. Requirements validated? → Move to Validated with phase reference
3. New requirements emerged? → Add to Active
4. Decisions to log? → Add to Key Decisions
5. "What This Is" still accurate? → Update if drifted

**After each milestone** (via `/gsd-complete-milestone`):
1. Full review of all sections
2. Core Value check — still the right priority?
3. Audit Out of Scope — reasons still valid?
4. Update Context with current state

---
*Last updated: 2026-04-10 after initialization*
