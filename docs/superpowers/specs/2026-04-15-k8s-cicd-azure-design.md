# Design: CI/CD, Kubernetes & Azure IaC for BankForge

**Date:** 2026-04-15  
**Status:** Approved

---

## Context

BankForge is a local-only banking sandbox (Podman Compose + kind) currently 35% through a 6-phase roadmap. Phases 1, 1.1, and 2 are complete (ACID core, CDC pipeline, observability). Phases 3–5 (service mesh, graph RCA, AI/MCP) are not yet started.

The goal is to add enterprise DevOps patterns — CI/CD, cloud-agnostic Kubernetes manifests, and optional Azure IaC — as a **learning showcase**. These patterns sit alongside the existing banking patterns without disrupting the roadmap.

**Core principle:** K8s manifests are cloud-agnostic. The same YAML runs unchanged on local kind and on Azure AKS. Only Terraform code is Azure-specific.

---

## Key Decisions

| Concern | Decision | Rationale |
|---------|----------|-----------|
| CI/CD platform | GitHub Actions | Free, in-repo, industry standard |
| Container registry | ghcr.io | Free; no Azure service needed |
| K8s manifest format | Raw YAML for own services; Helm for third-party | Transparent; reuses maintained charts |
| Secrets | GitHub Actions secrets → `kubectl create secret` | No Azure Key Vault; no cost |
| Storage | `emptyDir` everywhere | Re-seed on startup is acceptable; $0 cost |
| Ingress | Kong in KIC mode | Already planned for Phase 3; replaces Nginx Ingress |
| Observability on K8s | Same stack in pods (Jaeger, Prometheus, Loki, Grafana) | Cloud-agnostic; no Azure Monitor |
| IaC tool | Terraform | Cloud-agnostic skill, industry standard |
| Azure managed services | None | AKS node VMs only; everything else runs in pods |
| Standby cost | $0 | Full `terraform destroy` between sessions |
| Local → kind bridge | Self-hosted GitHub Actions runner on laptop | GitHub Actions CD reaches local kind cluster |

---

## Updated Phase Sequence

```
✅ Phase 1    — Core Banking (ACID + Saga)
✅ Phase 1.1  — CDC Pipeline + kind Spike
✅ Phase 2    — Observability
   Phase 2.5  — CI/CD Foundation          ← NEW
   Phase 3    — Service Mesh & Auth        (unchanged + writes k8s/ manifests)
   Phase 4    — Graph & RCA               (unchanged + adds Neo4j manifest)
   Phase 5    — AI / MCP                  (unchanged + adds MCP server manifest)
   Phase 6    — Azure IaC                 ← NEW MILESTONE (optional)
```

---

## Phase 2.5 — CI/CD Foundation

**Scope:** GitHub Actions CI only. No Kubernetes, no Azure.

### Deliverables

`.github/workflows/ci.yml` — triggered on push to `main` and PRs:

```
jobs:
  build-account-service    ← Maven build + test + push to ghcr.io
  build-payment-service
  build-ledger-service
  build-notification-service
  (all run in parallel)
```

Image naming: `ghcr.io/harry-zhao-au/bankforge/<service>:<git-sha>`, tagged `:latest` on `main`.

Self-hosted GitHub Actions runner installed on laptop — enables `cd.yml` (Phase 3) to reach local kind cluster.

### Secrets required

| Secret | Purpose |
|--------|---------|
| `GHCR_TOKEN` | Push images to ghcr.io (GitHub PAT, `write:packages` scope) |

---

## Phase 3 — Service Mesh & Auth (K8s additions)

Phase 3 scope is unchanged. It additionally creates the `k8s/` directory.

### Directory structure

```
k8s/
├── services/
│   ├── account-service.yaml       ← Deployment + Service + ConfigMap
│   ├── payment-service.yaml
│   ├── ledger-service.yaml
│   └── notification-service.yaml
└── infrastructure/
    ├── postgresql.yaml             ← StatefulSet, emptyDir, runs Flyway on startup
    ├── redis.yaml                  ← Deployment, emptyDir
    ├── kafka.yaml                  ← StatefulSet KRaft, emptyDir
    ├── debezium.yaml
    ├── keycloak.yaml
    └── observability/
        ├── otel-collector.yaml
        ├── jaeger.yaml
        ├── prometheus/             ← Helm: prometheus-community/kube-prometheus-stack
        ├── loki/                   ← Helm: grafana/loki
        └── grafana/                ← Helm: grafana/grafana
```

### Ingress

Kong deployed via Helm in KIC mode — handles both cluster-edge routing and API gateway (JWT, rate limiting). No separate Nginx Ingress Controller needed.

```bash
helm install kong kong/kong --set ingressController.enabled=true
```

On kind: access via `kubectl port-forward`. On AKS: Kong Service type `LoadBalancer` → Azure auto-provisions Public IP.

### CD workflow

`.github/workflows/cd.yml` — triggers on merge to `main` after CI passes, runs on self-hosted runner:

```
kubectl create secret (postgres password, etc.)
kubectl apply -f k8s/
helm upgrade --install (kong, prometheus, loki, grafana)
smoke test: curl /actuator/health on each service
```

### Secrets required (additions)

| Secret | Purpose |
|--------|---------|
| `POSTGRES_PASSWORD` | Injected as Kubernetes Secret before apply |
| `KUBECONFIG_KIND` | base64-encoded kind kubeconfig for self-hosted runner |

### Cloud-agnostic guarantee

- All `k8s/` YAML uses only standard Kubernetes resources
- No Azure-specific annotations or CRDs
- `emptyDir` for all stateful components — no StorageClass, no PVC
- Same manifests deploy to kind (Phase 3) and AKS (Phase 6) unchanged

---

## Phase 4 — Graph & RCA (addition only)

Adds one manifest:

```
k8s/infrastructure/neo4j.yaml    ← StatefulSet, emptyDir
```

---

## Phase 5 — AI / MCP (addition only)

Adds one manifest:

```
k8s/services/mcp-server.yaml    ← Deployment (Python container)
```

---

## Phase 6 — Azure IaC (new optional milestone)

**Prerequisite:** Phases 3–5 complete and verified on kind.

**Proof point:** The same `k8s/` manifests from Phases 3–5 deploy unchanged to AKS via Terraform-provisioned infrastructure.

### Terraform scope

```
terraform/azure/
├── main.tf          ← AKS cluster (2 × Standard_B4ms, or spot nodes)
├── network.tf       ← VNet + subnet
├── storage.tf       ← Storage Account for Terraform state backend
├── outputs.tf       ← exports kubeconfig, cluster name
└── variables.tf
```

Terraform does NOT provision PostgreSQL, Redis, Kafka, ACR, or Key Vault — all run in pods.

### Workflows

**`deploy-azure.yml`** (manual trigger):
1. `terraform apply` — provisions AKS (~5-8 min)
2. Extract kubeconfig from Terraform output → set as env var
3. `kubectl apply -f k8s/` — identical to kind deploy
4. Helm installs for third-party charts
5. Smoke test all `/actuator/health` endpoints

**`destroy-azure.yml`** (manual trigger only):
1. `terraform destroy` — deletes AKS + VNet + Storage Account
2. Azure auto-deletes the `MC_*` resource group (Load Balancer, Public IP, all node disks)
3. Standby cost after destroy: **$0**

### Secrets required (additions)

| Secret | Purpose |
|--------|---------|
| `AZURE_CLIENT_ID` | Terraform auth to Azure |
| `AZURE_CLIENT_SECRET` | Terraform auth to Azure |
| `AZURE_TENANT_ID` | Terraform auth to Azure |
| `AZURE_SUBSCRIPTION_ID` | Terraform auth to Azure |

### Cost

| Scenario | Cost |
|----------|------|
| Standby (full `terraform destroy`) | **$0/month** |
| Active, 2 hrs/day, 2 × Standard_B4ms | ~$20/month |
| Active, 2 hrs/day, spot nodes | ~$6-8/month |

---

## Final Repository Structure

```
BankForge/
├── k8s/                          ← cloud-agnostic (Phases 3–5)
│   ├── services/
│   └── infrastructure/
├── terraform/
│   └── azure/                    ← Azure-specific only (Phase 6)
├── .github/
│   └── workflows/
│       ├── ci.yml                ← Phase 2.5
│       ├── cd.yml                ← Phase 3 (self-hosted runner → kind)
│       ├── deploy-azure.yml      ← Phase 6 (manual)
│       └── destroy-azure.yml     ← Phase 6 (manual)
├── compose.yml                   ← unchanged (local Podman dev)
└── [existing service modules]
```

---

## Verification

| Phase | Verification |
|-------|-------------|
| 2.5 | Push commit → GitHub Actions green → image visible in `ghcr.io` packages tab |
| 3 | `kubectl get pods -n bankforge` all Running on kind; `curl` health endpoints via port-forward |
| 6 | Same `kubectl get pods` check against AKS kubeconfig; confirm identical manifests deployed |

---

## Explicitly Out of Scope

- Argo CD (adds significant complexity for a sandbox; own learning project)
- Azure Monitor / Application Insights (observability runs in pods, cloud-agnostic)
- Azure Key Vault (GitHub Actions secrets suffice)
- Azure Container Registry (ghcr.io is free)
- PersistentVolumeClaims (emptyDir everywhere)
- Helm charts for own 4 services (raw YAML is more transparent for learning)
