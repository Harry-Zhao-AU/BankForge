---
phase: 02-observability
plan: 01
subsystem: observability-infrastructure
tags: [otel-collector, jaeger, prometheus, loki, grafana, compose, observability]
dependency_graph:
  requires: []
  provides:
    - otel-collector container config (traces/metrics/logs fan-out)
    - jaeger v2 container config (in-memory trace storage)
    - loki container config (OTLP log ingestion)
    - prometheus container config (OTel Collector scrape)
    - grafana provisioning (3 data sources, dashboard provider)
    - compose.yml observability services (5 new blocks)
    - banking services OTel env var wiring
  affects:
    - compose.yml (5 new services + 4 service env var additions)
    - account-service startup dependency
    - payment-service startup dependency
    - ledger-service startup dependency
    - notification-service startup dependency
tech_stack:
  added:
    - otel/opentelemetry-collector-contrib:0.149.0
    - jaegertracing/jaeger:2.17.0
    - prom/prometheus:v3.10.0
    - grafana/loki:3.7.0
    - grafana/grafana:11.6.14
  patterns:
    - OTel Collector as single fan-out hub for all three signals
    - Jaeger v2 with in-memory storage (jaeger_storage extension)
    - Prometheus pull model via OTel Collector scrape endpoint
    - Loki OTLP ingestion (no Promtail)
    - Grafana exemplar trace ID linking (Prometheus -> Jaeger)
key_files:
  created:
    - infra/observability/otel-collector-config.yaml
    - infra/observability/jaeger-config.yaml
    - infra/observability/loki-config.yaml
    - infra/observability/prometheus.yml
    - infra/observability/grafana/provisioning/datasources/datasources.yaml
    - infra/observability/grafana/provisioning/dashboards/dashboards-provider.yaml
  modified:
    - compose.yml
decisions:
  - OTel Collector 0.149.0 used as single telemetry fan-out hub (D-05, D-06)
  - Jaeger v2 field names validated against 2.17.0 binary before config creation
  - Jaeger does not expose port 4318 to host (internal OTLP only from otel-collector)
  - GF_AUTH_ANONYMOUS_ENABLED=true accepted for local dev (T-02-02, documented for Phase 3 removal)
metrics:
  duration: 5m
  completed: 2026-04-12
  tasks_completed: 3
  files_created: 6
  files_modified: 1
---

# Phase 2 Plan 1: Observability Infrastructure Summary

**One-liner:** Five-container observability stack (OTel Collector -> Jaeger v2 + Prometheus + Loki + Grafana) wired into compose.yml with all four banking services pointing to otel-collector:4318.

## What Was Built

### Task 0: Jaeger v2 Field Name Validation
Ran `jaegertracing/jaeger:2.17.0 components` against the actual binary to confirm extension and exporter names before creating config files.

**Validated field names (2.17.0 binary confirmed):**
- Extension: `jaeger_storage` (Beta stability) — CONFIRMED
- Extension: `jaeger_query` (Beta stability) — CONFIRMED
- Exporter: `jaeger_storage_exporter` — CONFIRMED
- Config structure (`backends.memstore.memory.max_traces`, `storage.traces`, `http.endpoint`) validated via `validate` command with test config — exit 0, no errors

**No corrections needed** — the plan's template matched the actual binary exactly.

### Task 1: Observability Config Files (6 files)

| File | Purpose | Key Content |
|------|---------|-------------|
| `infra/observability/otel-collector-config.yaml` | Three-pipeline OTel Collector | `otlphttp/jaeger`, `prometheus`, `otlphttp/loki` exporters; receives OTLP HTTP on 4318 |
| `infra/observability/jaeger-config.yaml` | Jaeger v2 in-memory storage | `jaeger_storage` + `jaeger_query` extensions; `jaeger_storage_exporter`; 100,000 max traces |
| `infra/observability/loki-config.yaml` | Loki single-binary with OTLP | `allow_structured_metadata: true`; schema v13; tsdb store |
| `infra/observability/prometheus.yml` | Prometheus scrape config | Scrapes `otel-collector:8889`; 15s interval |
| `infra/observability/grafana/provisioning/datasources/datasources.yaml` | Grafana data sources | Prometheus (default, exemplarTraceIdDestinations->jaeger), Jaeger, Loki |
| `infra/observability/grafana/provisioning/dashboards/dashboards-provider.yaml` | Dashboard auto-load | BankForge folder; path `/etc/grafana/provisioning/dashboards` |

All 6 files pass `yaml.safe_load()` validation.

### Task 2: compose.yml Updates

**5 new observability services added:**

| Service | Image | Port | Healthcheck |
|---------|-------|------|-------------|
| `otel-collector` | `otel/opentelemetry-collector-contrib:0.149.0` | 4318 | `wget http://localhost:8888/metrics` |
| `jaeger` | `jaegertracing/jaeger:2.17.0` | 16686 | `wget http://localhost:16686/api/services` |
| `prometheus` | `prom/prometheus:v3.10.0` | 9090 | `wget http://localhost:9090/-/healthy` |
| `loki` | `grafana/loki:3.7.0` | 3100 | `wget http://localhost:3100/ready` |
| `grafana` | `grafana/grafana:11.6.14` | 3000 | `wget http://localhost:3000/api/health` |

**Dependency chain:**
```
banking services -> otel-collector (service_healthy)
                   otel-collector -> jaeger (service_healthy)
                   otel-collector -> prometheus (service_healthy)
                   loki (no upstream dep - starts independently)
                   jaeger + prometheus + loki -> grafana (service_healthy)
```

**4 banking services modified** — each received:
- `OTEL_EXPORTER_OTLP_ENDPOINT: http://otel-collector:4318` in `environment:`
- `otel-collector: condition: service_healthy` in `depends_on:`

**No existing service configuration changed** — all original ports, database URLs, and settings are unchanged.

## Verification Results

All plan acceptance criteria met:

- `otel-collector` appears 13 times in compose.yml (service definition + 6 depends_on entries)
- All 4 banking services have `OTEL_EXPORTER_OTLP_ENDPOINT: http://otel-collector:4318`
- Jaeger port 4318 NOT exposed to host (internal OTLP only)
- No port collisions: 4318, 16686, 9090, 3100, 3000
- `docker-compose config` validates without errors
- All 6 YAML files parse without errors

## Deviations from Plan

None - plan executed exactly as written. Jaeger v2 field names matched the plan template, no corrections were required.

## Known Stubs

None. All config files are complete and production-ready for the local dev environment. No hardcoded empty values or placeholder text.

## Threat Flags

No new threat surface introduced beyond what was documented in the plan's threat model:

| Threat ID | Disposition | Notes |
|-----------|-------------|-------|
| T-02-01 | accept | OTel Collector port 4318 exposed to localhost only; local dev |
| T-02-02 | accept | `GF_AUTH_ANONYMOUS_ORG_ROLE: Admin` - local dev only; must be removed for Phase 3 shared environments |
| T-02-03 | accept | Trace attributes may contain account IDs; local dev with test data only |
| T-02-04 | accept | No auth on Prometheus/Loki/Jaeger; all on Compose internal network |
| T-02-05 | mitigate | `max_traces: 100000` configured in jaeger-config.yaml |

## Jaeger v2 Validation Output (Task 0 Reference)

```
Extensions confirmed in jaegertracing/jaeger:2.17.0:
  - jaeger_storage (Beta)
  - jaeger_query (Beta)
Exporters confirmed:
  - jaeger_storage_exporter
  - jaeger_storage (note: both exist; jaeger_storage_exporter is the correct pipeline exporter)
Config validate exit code: 0 (no errors with plan template config)
```

## Self-Check: PASSED

- `infra/observability/otel-collector-config.yaml` — EXISTS
- `infra/observability/jaeger-config.yaml` — EXISTS
- `infra/observability/loki-config.yaml` — EXISTS
- `infra/observability/prometheus.yml` — EXISTS
- `infra/observability/grafana/provisioning/datasources/datasources.yaml` — EXISTS
- `infra/observability/grafana/provisioning/dashboards/dashboards-provider.yaml` — EXISTS
- `compose.yml` — MODIFIED (5 new services + 4 services updated)
- Task 1 commit: `563bf4c` — EXISTS
- Task 2 commit: `e886a0a` — EXISTS
