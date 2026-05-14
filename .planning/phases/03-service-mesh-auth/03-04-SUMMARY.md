---
phase: 03-service-mesh-auth
plan: 04
name: Kong Gateway + Keycloak JWT Auth
status: complete
completed: 2026-05-14
---

# Phase 03 Plan 04 Summary: Kong Gateway + Keycloak JWT Auth

## Outcome

All 4 banking services sit behind Kong Gateway with Keycloak RS256 JWT authentication. Kong validates every inbound request, strips forged `X-User-Id` headers, and injects a verified header from the JWT `sub` claim. Services receive authenticated requests with no auth code of their own.

## What Was Deployed

| Resource | Namespace | Status |
|---|---|---|
| Keycloak Deployment (quay.io/keycloak/keycloak:26.6.1) | bankforge | Running 2/2 |
| keycloak-realm-config ConfigMap (bankforge realm, 2 users, 2 clients) | bankforge | Applied |
| Kong controller (kong/ingress v0.24.0) | kong | Running 1/1 |
| Kong gateway proxy | kong | Running 1/1 |
| PeerAuthentication PERMISSIVE | kong | Active |
| 4× Ingress resources (account, payment, ledger, notification) | bankforge | Programmed |
| KongPlugin: jwt-auth (RS256) | bankforge | Programmed |
| KongPlugin: inject-user-id (Lua pre-function) | bankforge | Programmed |
| KongPlugin: otel-tracing | bankforge | Programmed |

## Validation Results (2026-05-14)

| Check | Result |
|---|---|
| Keycloak realm `bankforge` auto-imported on pod start | PASS |
| Token issued via `kong` client (RS256) | PASS — 1113-char JWT |
| Unauthenticated request → 401 | PASS |
| Authenticated POST /api/accounts → 201 | PASS |
| Authenticated GET /api/accounts/{id} → 200 | PASS |
| Forged X-User-Id stripped, JWT sub injected | PASS — Lua plugin confirmed in cluster |
| PeerAuthentication PERMISSIVE in kong namespace | PASS |
| Istio STRICT mTLS in bankforge namespace | PASS (x-envoy headers on responses) |

## Key Decisions Confirmed

| Decision | Detail |
|---|---|
| kong client for password grant | `bankforge-app` has `directAccessGrantsEnabled: false`; use `kong` client with secret `kong-client-secret` |
| No strip-path on ingress | Full path forwarded to backends (e.g. `/api/accounts/{id}` → service receives `/api/accounts/{id}`) |
| Lua pre-function for X-User-Id | KongPlugin `inject-user-id` type `pre-function`; decodes JWT base64, extracts sub, clears and re-sets header |

## kubectl Access Note

From Windows Git Bash, kubectl cannot reach `127.0.0.1:50063` (kind API bound to WSL2 loopback only). Use:
```bash
wsl -d podman-machine-default -- kubectl <command>
```
Or add a Windows netsh portproxy rule:
```powershell
netsh interface portproxy add v4tov4 listenport=50063 listenaddress=127.0.0.1 connectport=50063 connectaddress=172.27.43.186
```

## Files Created

```
k8s/infrastructure/keycloak.yaml
k8s/istio/kong-permissive.yaml
k8s/kong/helm-values.yaml
k8s/kong/kong-plugins.yaml
k8s/kong/ingress.yaml
scripts/kong-register-keycloak-key.sh
scripts/validate-auth-k8s.sh
scripts/port-forward.sh (updated with Keycloak + Kong ports)
```
