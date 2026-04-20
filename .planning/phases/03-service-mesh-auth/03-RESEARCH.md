---
phase: 03-service-mesh-auth
type: research
created: 2026-04-20
status: complete
---

# Phase 03 Research: Service Mesh & Auth

**Environment baseline:** kind v0.27.0 · Podman rootful · WSL2/Windows · kubectl v1.35.3 · Spring Boot 4.0.5 / Java 21

---

## 1. kind Node Image

| Item | Value |
|---|---|
| kind version | v0.27.0 |
| **Default K8s version** | **v1.32.2** |
| Default image (pinned) | `kindest/node:v1.32.2@sha256:f226345927d7e348497136874b6d207e0b32cc52154ad8323129352923a3142f` |
| Alt image (more Istio headroom) | `kindest/node:v1.33.0@sha256:02f73d6ae3f11ad5d543f16736a2cb2a63a300ad60e81dac22099b0b04784a4e` |

**Decision:** Use `v1.32.2` (default) — within Istio 1.29's actively-tested range. If Istio install proves unstable, fall back to `v1.33.0`.

kubectl v1.35.3 being 3 minor versions ahead of the cluster is fine — kubectl is backward-compatible.

---

## 2. Istio

| Item | Value |
|---|---|
| **Latest stable** | **Istio 1.29.2** (released 2026-04-13) |
| Actively tested K8s | 1.31, 1.32, 1.33, 1.34, 1.35 |
| K8s 1.32 compatibility | Fully supported |
| Profile for BankForge | `demo` (includes all observability hooks, control plane + gateway) |

**Install commands:**

```bash
curl -L https://istio.io/downloadIstio | ISTIO_VERSION=1.29.2 sh -
export PATH="$PWD/istio-1.29.2/bin:$PATH"

istioctl install --set profile=demo -y

# Verify
istioctl verify-install
kubectl get pods -n istio-system
```

**mTLS staging (CRITICAL — never skip):**

```bash
# Step 1: Label namespace BEFORE deploying any pods
kubectl label namespace bankforge istio-injection=enabled

# Step 2: Deploy pods — verify 2/2 READY
kubectl get pods -n bankforge

# Step 3: Confirm PERMISSIVE mode (default after install)
kubectl get peerauthentication -n bankforge

# Step 4: Switch to STRICT only after all pods show 2/2
kubectl apply -f - <<EOF
apiVersion: security.istio.io/v1beta1
kind: PeerAuthentication
metadata:
  name: default
  namespace: bankforge
spec:
  mtls:
    mode: STRICT
EOF
```

---

## 3. Kong Kubernetes Ingress Controller

| Item | Value |
|---|---|
| Helm chart | `kong/ingress` v0.24.0 |
| Kong Gateway image | `kong:3.9` |
| KIC image | `kong/kubernetes-ingress-controller:3.5` |
| Helm repo | `https://charts.konghq.com` |
| Mode | DB-less, Ingress Controller |

**Install commands:**

```bash
helm repo add kong https://charts.konghq.com
helm repo update

helm install kong kong/ingress \
  -n kong --create-namespace \
  --set controller.ingressController.enabled=true
```

**Kong + Istio coexistence rules:**
- Kong namespace: do NOT label with `istio-injection=enabled`
- Kong pods: annotate `sidecar.istio.io/inject: "false"` in values.yaml
- Apply PERMISSIVE `PeerAuthentication` in the kong namespace so Kong's plaintext requests reach mTLS services

```yaml
# values override for Kong Helm install
podAnnotations:
  sidecar.istio.io/inject: "false"
```

```yaml
# PeerAuthentication in kong namespace
apiVersion: security.istio.io/v1beta1
kind: PeerAuthentication
metadata:
  name: kong-permissive
  namespace: kong
spec:
  mtls:
    mode: PERMISSIVE
```

---

## 4. Keycloak

| Item | Value |
|---|---|
| **Latest stable** | **26.6.1** (released 2026-04-15) |
| Image | `quay.io/keycloak/keycloak:26.6.1` |
| Admin username env var | `KC_BOOTSTRAP_ADMIN_USERNAME` (old `KEYCLOAK_ADMIN` deprecated in 26) |
| Admin password env var | `KC_BOOTSTRAP_ADMIN_PASSWORD` |
| Realm auto-import | `--import-realm` arg + `/opt/keycloak/data/import/` volume |

**Minimum K8s pod env vars (dev mode):**

```yaml
env:
  - name: KC_BOOTSTRAP_ADMIN_USERNAME
    value: "admin"
  - name: KC_BOOTSTRAP_ADMIN_PASSWORD
    valueFrom:
      secretKeyRef:
        name: keycloak-secret
        key: admin-password
  - name: KC_PROXY_HEADERS
    value: "xforwarded"
  - name: KC_HTTP_ENABLED
    value: "true"
args: ["start-dev", "--import-realm"]
```

**Realm bootstrap strategy:**
- Create banking realm via UI once, export as JSON, commit as `ConfigMap`
- Mount at `/opt/keycloak/data/import/banking-realm.json`
- Pod auto-imports on startup via `--import-realm` flag

---

## 5. Spring Boot 4 on Kubernetes

### Config (DB, Kafka, Redis hostnames)

**Pattern: plain ConfigMap env vars** — no Spring Cloud Kubernetes needed.

Spring Boot's relaxed binding maps `SPRING_DATASOURCE_URL` → `spring.datasource.url` automatically.

```yaml
# configmap.yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: account-service-config
  namespace: bankforge
data:
  SPRING_DATASOURCE_URL: "jdbc:postgresql://account-db:5432/accountdb"
  SPRING_KAFKA_BOOTSTRAP_SERVERS: "kafka:9092"
  SPRING_DATA_REDIS_HOST: "redis"
  SPRING_DATA_REDIS_PORT: "6379"
```

```yaml
# deployment.yaml
envFrom:
  - configMapRef:
      name: account-service-config
```

### Actuator Health Probes

Spring Boot 4 **auto-detects Kubernetes** and enables liveness/readiness probes — no manual config needed.

Add `startupProbe` to handle slow Flyway migrations and Kafka consumer init (prevents restart loops):

```yaml
livenessProbe:
  httpGet:
    path: /actuator/health/liveness
    port: 8080
  initialDelaySeconds: 60
  periodSeconds: 20
  failureThreshold: 3
  timeoutSeconds: 5
readinessProbe:
  httpGet:
    path: /actuator/health/readiness
    port: 8080
  initialDelaySeconds: 30
  periodSeconds: 10
  failureThreshold: 5
  timeoutSeconds: 5
startupProbe:
  httpGet:
    path: /actuator/health/liveness
    port: 8080
  initialDelaySeconds: 10
  periodSeconds: 5
  failureThreshold: 30   # 150s total for cold start
```

---

## 6. Resource Limits (single-node WSL2 dev cluster)

| Workload | CPU Request | CPU Limit | Memory Request | Memory Limit |
|---|---|---|---|---|
| Spring Boot 4 service (×4) | 500m | 1500m | 512Mi | 768Mi |
| Istio sidecar (per pod) | 50m | none | 64Mi | 256Mi |
| PostgreSQL (×4 schemas, can share) | 250m | 1000m | 256Mi | 512Mi |
| Kafka KRaft (single node) | 500m | 2000m | 1Gi | 2Gi |
| Redis | 100m | 500m | 128Mi | 256Mi |
| Debezium (Kafka Connect) | 250m | 1000m | 512Mi | 1Gi |
| Kong Gateway | 250m | 1000m | 256Mi | 512Mi |
| Keycloak | 500m | 2000m | 768Mi | 1Gi |

**WSL2 memory budget:** ~12 GiB allocated to WSL2 recommended for comfortable operation.

**JVM note:** With `-XX:MaxRAMPercentage=75.0` and 768Mi limit → ~576Mi heap. Bump to 1Gi if OOMKilled.

**CPU limit note:** Avoid hard CPU limits on JVM pods (causes GC throttling latency). Use high limits or omit for dev.

---

## 7. k8s/ Directory Structure

Per approved design doc (`2026-04-15-k8s-cicd-azure-design.md`):

```
k8s/
├── namespace.yaml
├── services/
│   ├── account-service.yaml       # Deployment + Service + ConfigMap
│   ├── payment-service.yaml
│   ├── ledger-service.yaml
│   └── notification-service.yaml
└── infrastructure/
    ├── postgresql.yaml             # StatefulSet, emptyDir, Flyway init
    ├── redis.yaml                  # Deployment, emptyDir
    ├── kafka.yaml                  # StatefulSet KRaft, emptyDir
    ├── debezium.yaml               # Deployment (Kafka Connect + Debezium)
    ├── keycloak.yaml               # Deployment + banking realm ConfigMap
    └── observability/
        ├── otel-collector.yaml
        ├── jaeger.yaml
        ├── prometheus/             # Helm: prometheus-community/kube-prometheus-stack
        ├── loki/                   # Helm: grafana/loki
        └── grafana/                # Helm: grafana/grafana
```

**Ingress:** Kong KIC in `kong` namespace (no Nginx Ingress Controller needed).

---

## 8. Flags and Open Items

| # | Severity | Item |
|---|---|---|
| F1 | LOW | K8s 1.32.2 is at the bottom of Istio 1.29's tested range. If issues arise, pin kind node to `v1.33.0`. |
| F2 | MEDIUM | Kong chart v0.24.0 defaults to `kong:3.9`. Run `helm search repo kong/ingress` at install time to confirm version hasn't changed. |
| F3 | LOW | Keycloak 26: old `KEYCLOAK_ADMIN` env var still works but deprecated. Use `KC_BOOTSTRAP_ADMIN_USERNAME` going forward. |
| F4 | HIGH | 4 PostgreSQL instances (one per service) is the correct isolation pattern, but on a single-node kind cluster this is 4 StatefulSets. Consider sharing a single PostgreSQL pod with 4 schemas (one per service) to save memory. Decision needed before writing manifests. |
| F5 | MEDIUM | WSL2 memory allocation must be set to at least 12 GiB in `.wslconfig` before deploying the full stack. |
| F6 | LOW | `port-forward.sh` must be written in Phase 3 (STATE.md todo) — MCP server in Phase 5 requires host→cluster connectivity from Windows. |

---

## Decisions — LOCKED (2026-04-20)

| Decision | Choice | Rationale |
|---|---|---|
| K8s node version | **v1.32.2** | Default for kind v0.27.0; within Istio 1.29 tested range |
| PostgreSQL topology | **4 separate StatefulSets** (one per service) | True pod-level isolation; each service owns its DB pod |
| Observability scope in Phase 3 | **Kiali only** | Prometheus, OTel Collector, Loki, Grafana have Phase 2 Compose configs and will be ported as K8s manifests — not new design work. Only Kiali is new in Phase 3. |

---

*Research completed: 2026-04-20*
