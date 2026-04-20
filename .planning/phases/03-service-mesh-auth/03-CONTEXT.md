---
phase: 03-service-mesh-auth
type: context
created: 2026-04-20
status: active
requirements: [MESH-01, MESH-02, MESH-03, MESH-04, MESH-05, MESH-06, MESH-07]
---

# Phase 03 Context: Service Mesh & Auth

**Goal:** All 4 Spring Boot services running in a kind Kubernetes cluster behind Istio mTLS STRICT and Kong JWT auth. No service handles auth code; no plaintext pod-to-pod traffic exists.

---

## Locked Decisions

| ID | Decision | Value |
|---|---|---|
| D-01 | kind node image | `kindest/node:v1.32.2@sha256:f226345927d7e348497136874b6d207e0b32cc52154ad8323129352923a3142f` |
| D-02 | Istio version | 1.29.2 |
| D-03 | Istio install profile | `demo` |
| D-04 | Istio mTLS staging | PERMISSIVE first → STRICT after all pods show 2/2 READY |
| D-05 | Kong Helm chart | `kong/ingress` v0.24.0 |
| D-06 | Kong JWT algorithm | RS256 (Keycloak issues RS256; HS256 causes universal 401) |
| D-07 | Kong X-User-Id stripping | Kong strips incoming header before injecting from JWT claims (header forgery prevention) |
| D-08 | Keycloak version | 26.6.1 (`quay.io/keycloak/keycloak:26.6.1`) |
| D-09 | Keycloak admin env vars | `KC_BOOTSTRAP_ADMIN_USERNAME` / `KC_BOOTSTRAP_ADMIN_PASSWORD` (26.x names) |
| D-10 | Keycloak realm bootstrap | `--import-realm` flag + ConfigMap volume at `/opt/keycloak/data/import/` |
| D-11 | PostgreSQL topology | 4 separate StatefulSets, one per service (true pod isolation) |
| D-12 | Storage | `emptyDir` everywhere — re-seed on startup acceptable; cloud-agnostic |
| D-13 | Spring Boot config in K8s | Plain ConfigMap env vars (`envFrom: configMapRef`) — no Spring Cloud K8s |
| D-14 | Observability scope in Phase 3 | Port Prometheus, OTel Collector, Loki, Grafana as K8s manifests (from Phase 2 configs). Add Kiali as new component. |
| D-15 | Kong namespace | No `istio-injection=enabled`; pods annotated `sidecar.istio.io/inject: "false"` |
| D-16 | Kong → mesh traffic | `PeerAuthentication PERMISSIVE` in kong namespace; application namespace stays STRICT |
| D-17 | Resource limits | See Research §6 baseline table |
| D-18 | K8s namespace | `bankforge` (all banking services + infra) |

---

## Critical Constraints (from ROADMAP.md)

- **PERMISSIVE → STRICT:** Never go straight to STRICT. Retroactive sidecar injection requires pod restarts and drops traffic.
- **RS256 JWT:** Kong JWT plugin must be configured with RS256. Keycloak's RS256 public key must be loaded into Kong.
- **X-User-Id stripping:** Kong strips any incoming `X-User-Id` header, then injects the verified one from JWT `sub` claim.
- **Resource limits:** All pods must have CPU/memory requests + limits. Istio sidecars + Spring Boot JVMs risk OOMKill without limits.
- **port-forward.sh:** Must be written in this phase. MCP server (Phase 5) requires host→cluster connectivity from Windows.

---

## Service-to-DB Mapping (4 StatefulSets)

| Service | DB StatefulSet | Schema / DB name |
|---|---|---|
| account-service | `account-db` | `accountdb` |
| payment-service | `payment-db` | `paymentdb` |
| ledger-service | `ledger-db` | `ledgerdb` |
| notification-service | `notification-db` | `notificationdb` |

Each service's ConfigMap sets `SPRING_DATASOURCE_URL` pointing to its own DB pod.

---

## Image Source

All 4 service images come from ghcr.io CI pipeline (Phase 2.5):
```
ghcr.io/harry-zhao-au/bankforge-account-service:latest
ghcr.io/harry-zhao-au/bankforge-payment-service:latest
ghcr.io/harry-zhao-au/bankforge-ledger-service:latest
ghcr.io/harry-zhao-au/bankforge-notification-service:latest
```

Deployments reference `:latest` for dev. Phase 6 (Azure) will pin to sha tags.

---

## k8s/ Directory Structure

```
k8s/
├── namespace.yaml
├── services/
│   ├── account-service.yaml       # Deployment + Service + ConfigMap
│   ├── payment-service.yaml
│   ├── ledger-service.yaml
│   └── notification-service.yaml
└── infrastructure/
    ├── account-db.yaml             # StatefulSet, emptyDir
    ├── payment-db.yaml
    ├── ledger-db.yaml
    ├── notification-db.yaml
    ├── redis.yaml                  # Deployment, emptyDir
    ├── kafka.yaml                  # StatefulSet KRaft, emptyDir
    ├── debezium.yaml               # Deployment (Kafka Connect)
    ├── keycloak.yaml               # Deployment + banking realm ConfigMap
    └── observability/
        ├── otel-collector.yaml
        ├── jaeger.yaml
        ├── kiali.yaml              # NEW in Phase 3
        ├── prometheus/             # Helm: prometheus-community/kube-prometheus-stack
        ├── loki/                   # Helm: grafana/loki
        └── grafana/                # Helm: grafana/grafana
```

---

## Plan Breakdown

| Plan | Name | Key Output |
|---|---|---|
| 03-01 | kind cluster + Istio PERMISSIVE + Infrastructure | Cluster up, namespace labelled, Istio installed, all infra pods running |
| 03-02 | Banking service manifests + Istio STRICT | All services 2/2 READY, mTLS STRICT enforced, port-forward.sh |
| 03-03 | Keycloak + Kong JWT + rate limiting | JWT auth working, X-User-Id injected, 429 on rate limit |
| 03-04 | Kiali + Observability K8s manifests + CD + Human verify | All MESH-01..07 success criteria confirmed |

---

## Success Criteria (from ROADMAP.md)

1. Every banking service pod shows **2/2 READY** containers (Istio sidecar injected)
2. Direct pod-to-pod HTTP without valid Istio identity **rejected** by Envoy sidecar (mTLS STRICT)
3. Valid Keycloak JWT → reaches banking API; missing/invalid JWT → **401 at Kong**; services never see raw JWTs, only injected `X-User-Id`
4. >100 requests/minute from single client → **429** from Kong after limit
5. Kiali dashboard shows **live service graph** with Kong, all 4 banking services, and Debezium — health + traffic rates updating

---

## Open Items (carry forward from STATE.md)

- [ ] WSL2 `.wslconfig` must allocate ≥12 GiB RAM before starting full stack
- [ ] `port-forward.sh` script for all services MCP server needs (Phase 5)
- [ ] Verify Istio control plane pods are healthy before labelling bankforge namespace
- [ ] Debezium connector must be re-registered after cluster restart (same as Compose — not auto-applied)

---

*Created: 2026-04-20*
