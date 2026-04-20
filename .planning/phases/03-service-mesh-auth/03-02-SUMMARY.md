---
phase: 03-service-mesh-auth
plan: 02
subsystem: banking-services
tags: [k8s, spring-boot, istio, mtls, deployments, port-forward]

provides:
  - "4x Spring Boot service Deployments + Services + ConfigMaps"
  - "PeerAuthentication STRICT for bankforge namespace"
  - "port-forward.sh for host→cluster connectivity (MCP server + local debug)"

key-files:
  created:
    - k8s/services/account-service.yaml
    - k8s/services/payment-service.yaml
    - k8s/services/ledger-service.yaml
    - k8s/services/notification-service.yaml
    - k8s/istio/peer-authentication-strict.yaml
    - scripts/port-forward.sh

key-decisions:
  - "notification-service has no datasource in application.yml — ConfigMap only has Kafka + OTel (no DB env vars)"
  - "OTel endpoint set to http://otel-collector:4318 for all services — will fail gracefully until Plan 03-04 deploys otel-collector"
  - "STRICT applied only after all service pods reach 2/2 READY — never applied cold"
  - "port-forward.sh includes observability ports even though those services land in Plan 03-04 — script is idempotent (failed forwards are suppressed via &>/dev/null)"
  - "imagePullPolicy: Always — ghcr.io images; :latest tag used for dev (pinned sha in Phase 6)"

requirements-completed: [MESH-01 (partial — services pending human apply)]

status: awaiting-human-verify
completed: 2026-04-20
---

# Phase 03 Plan 02: Banking Service Manifests + Istio STRICT mTLS Summary

**All 6 files created. Human apply + verification required.**

## Files Created (6)

| File | Description |
|---|---|
| `k8s/services/account-service.yaml` | ConfigMap (DB URL, OTel) + Deployment + Service |
| `k8s/services/payment-service.yaml` | ConfigMap (DB, Kafka, Redis, account-service URL, OTel) + Deployment + Service |
| `k8s/services/ledger-service.yaml` | ConfigMap (DB, Kafka, OTel) + Deployment + Service |
| `k8s/services/notification-service.yaml` | ConfigMap (Kafka, OTel only — no DB) + Deployment + Service |
| `k8s/istio/peer-authentication-strict.yaml` | PeerAuthentication STRICT for bankforge namespace |
| `scripts/port-forward.sh` | Forwards 9 services; trap SIGINT cleans up all PIDs |

## Human Verification Steps

```bash
# 1. Apply all 4 service manifests
kubectl apply -f k8s/services/

# 2. Wait until all 4 service pods are 2/2 READY
kubectl get pods -n bankforge -w

# 3. Apply STRICT mTLS (only after step 2 complete)
kubectl apply -f k8s/istio/peer-authentication-strict.yaml

# 4. Confirm STRICT is active
kubectl get peerauthentication -n bankforge

# 5. Verify intra-mesh traffic still works (payment → account)
kubectl exec -n bankforge deploy/payment-service -- \
  curl -sf http://account-service:8080/actuator/health

# 6. Verify bare pod (no sidecar) is rejected
kubectl run mtls-test --image=curlimages/curl --restart=Never \
  --annotations='sidecar.istio.io/inject=false' \
  -n bankforge -- curl -sf http://account-service:8080/actuator/health
kubectl logs mtls-test -n bankforge   # expect: curl: (56) recv failure
kubectl delete pod mtls-test -n bankforge
```

## Next

Plan 03-03: Keycloak + Kong JWT + rate limiting.
