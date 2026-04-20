---
phase: 03-service-mesh-auth
plan: 01
subsystem: infrastructure
tags: [kind, istio, kubernetes, postgresql, kafka, redis, debezium, k8s-manifests]

provides:
  - "kind cluster config (v1.32.2) with Kong port mappings (30080/30443)"
  - "Bootstrap script with Podman WSL2 workarounds (--disable-dns, KIND_EXPERIMENTAL_PROVIDER)"
  - "bankforge namespace with istio-injection: enabled"
  - "4x PostgreSQL 17 StatefulSets with wal_level=logical for Debezium CDC"
  - "Redis 7.2 Deployment"
  - "Kafka 3.9.2 KRaft StatefulSet (no ZooKeeper)"
  - "Debezium 3.1 Deployment with auto-registering outbox connectors via ConfigMap-mounted entrypoint"

key-files:
  created:
    - infra/kind/kind-config.yaml
    - scripts/k8s-cluster-up.sh
    - k8s/namespace.yaml
    - k8s/infrastructure/account-db.yaml
    - k8s/infrastructure/payment-db.yaml
    - k8s/infrastructure/ledger-db.yaml
    - k8s/infrastructure/notification-db.yaml
    - k8s/infrastructure/redis.yaml
    - k8s/infrastructure/kafka.yaml
    - k8s/infrastructure/debezium.yaml

key-decisions:
  - "ledger-outbox-connector.json name is 'bankforge-ledger-outbox-connector' (differs from plan draft) — verbatim copy from infra/debezium/"
  - "ledger connector topic.prefix is 'bankforge-ledger', table is 'public.ledger_outbox_event', route is 'banking.transfer.confirmed' — all verbatim from source file"
  - "Headless Service (clusterIP: None) on all PostgreSQL StatefulSets — enables stable pod DNS account-db-0.account-db.bankforge"
  - "Kafka drops PLAINTEXT_HOST listener — no host-accessible Kafka port in K8s (port-forward.sh covers debug access)"
  - "Debezium connector JSONs embedded verbatim in ConfigMap; entrypoint script copied from infra/debezium/debezium-entrypoint.sh"

requirements-completed: [MESH-01 (partial — cluster ready, pods not yet deployed)]

duration: ~25min
completed: 2026-04-20
status: awaiting-human-verify
---

# Phase 03 Plan 01: kind Cluster + Istio + Infrastructure Summary

**All 10 infrastructure files created. Human verification (Task 4) required to confirm cluster bootstrap and pod readiness.**

## Files Created (10)

| File | Description |
|---|---|
| `infra/kind/kind-config.yaml` | Single-node cluster, `kindest/node:v1.32.2`, ports 30080/30443 for Kong |
| `scripts/k8s-cluster-up.sh` | Full bootstrap: Podman pre-flight → kind create → Istio 1.29.2 demo → namespace → DB secrets |
| `k8s/namespace.yaml` | `bankforge` namespace with `istio-injection: enabled` |
| `k8s/infrastructure/account-db.yaml` | PostgreSQL 17 StatefulSet + headless Service, `wal_level=logical` |
| `k8s/infrastructure/payment-db.yaml` | PostgreSQL 17 StatefulSet + headless Service, `wal_level=logical` |
| `k8s/infrastructure/ledger-db.yaml` | PostgreSQL 17 StatefulSet + headless Service, `wal_level=logical` |
| `k8s/infrastructure/notification-db.yaml` | PostgreSQL 17 StatefulSet + headless Service, `wal_level=logical` |
| `k8s/infrastructure/redis.yaml` | Redis 7.2 Deployment + Service |
| `k8s/infrastructure/kafka.yaml` | Kafka 3.9.2 KRaft StatefulSet + Service (no ZooKeeper) |
| `k8s/infrastructure/debezium.yaml` | 2 ConfigMaps + Service + Deployment; auto-registers both outbox connectors |

## Deviation from Plan

The plan draft invented ledger connector content. Actual `infra/debezium/ledger-outbox-connector.json` differs:
- Name: `bankforge-ledger-outbox-connector` (not `ledger-outbox-connector`)
- `topic.prefix`: `bankforge-ledger`
- `table.include.list`: `public.ledger_outbox_event`
- `transforms.outbox.route.topic.replacement`: `banking.transfer.confirmed` (fixed topic, not pattern)
- `transforms.outbox.table.expand.json.payload` (not `transforms.outbox.expand.json.payload`)

All four differences are verbatim from the source file — the K8s ConfigMap is correct.

## Human Verification Required

Run these commands after `bash scripts/k8s-cluster-up.sh` + `kubectl apply -f k8s/infrastructure/`:

```bash
# All pods 2/2 READY
kubectl get pods -n bankforge

# Debezium connectors registered
kubectl exec -n bankforge deploy/debezium -- curl -sf http://localhost:8083/connectors

# PostgreSQL wal_level confirmed
kubectl exec -n bankforge account-db-0 -- psql -U account -d accountdb -c "SHOW wal_level;"

# No ZooKeeper
kubectl get pods -n bankforge | grep -i zookeeper
```

## Next

Plan 03-02: Banking service manifests + switch Istio to STRICT mTLS.
