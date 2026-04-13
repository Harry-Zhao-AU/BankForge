---
phase: 02-observability
plan: 03
subsystem: observability-verification
tags: [grafana, dashboard, smoke-test, loki, jaeger, prometheus, cdc, debezium]
dependency_graph:
  requires:
    - otel-collector (02-01)
    - service OTel instrumentation (02-02)
  provides:
    - Banking Overview Grafana dashboard (3 panels, provisioned on startup)
    - smoke-test-obs.sh (automated health + data verification)
    - verified end-to-end: transfer → Jaeger trace → Prometheus metric → Loki log → Grafana dashboard
requirements_delivered: [OBS-01, OBS-02, OBS-03, OBS-04, OBS-05]
---

# Plan 02-03 Summary: Grafana Dashboard + End-to-End Verification

## What Was Built

- **`infra/observability/grafana/provisioning/dashboards/banking-dashboard.json`** — Banking Overview dashboard with 3 panels provisioned on Grafana startup:
  - Panel 1: Transfer Volume (per minute) — `rate(transfer_initiated_total{state=~"CONFIRMED|PENDING"}[1m])`
  - Panel 2: Transfer Latency p99 (ms) — `histogram_quantile(0.99, rate(transfer_duration_milliseconds_bucket{service="payment-service"}[5m]))`
  - Panel 3: Error Rate (DLT Messages per minute) — `rate(transfer_dlt_messages_total{service="payment-service"}[1m])`
  - All panels have exemplar links to Jaeger traces

- **`scripts/smoke-test-obs.sh`** — Automated health + data verification script (11/11 checks pass in `--full` mode)

## End-to-End Verification Results

| Signal | Status | Evidence |
|--------|--------|----------|
| Jaeger traces | ✅ | All 4 services visible: payment-service, account-service, ledger-service, notification-service. Cross-service spans in single trace (payment → account). |
| Prometheus metrics | ✅ | `transfer_initiated_total`, `transfer_duration_milliseconds_*`, `transfer_amount_total` flowing via OTel Collector |
| Loki logs | ✅ | All 4 services present: `{service_name="payment-service|account-service|ledger-service|notification-service"}` |
| Grafana dashboard | ✅ | Banking Overview auto-loads with 3 panels showing real data |
| Smoke test | ✅ | 11/11 PASS (basic + full mode) |

## Key Decisions

| Decision | Rationale |
|----------|-----------|
| Grafana panel unit `ms` not `s` for latency | `transfer_duration_milliseconds_bucket` le values are in milliseconds (Micrometer Timer base unit). Panel set to `s` displayed 44.7ms as "44.7 seconds". Fix: `"unit": "ms"`. |
| Debezium connector must be registered via REST API on each stack startup | `infra/debezium/outbox-connector.json` exists but is not auto-applied. Manual `POST /connectors` required. Without it, ledger/notification receive no Kafka events → no traces or Loki logs from those services. |
| Idle services don't appear in Loki until they emit log events | OTel log appender only exports when log events occur. Services appear in Loki after first request/Kafka message, not at startup. Not a configuration issue. |

## Bugs Fixed During Verification

1. **Grafana p99 unit mismatch** — Panel 2 had `"unit": "s"` displaying ~45ms as ~45 seconds. Fixed to `"unit": "ms"`. Title updated to "Transfer Latency p99 (ms)".

2. **Debezium connector not registered** — After container restarts the CDC connector is lost (no auto-registration). Registered `bankforge-outbox-connector` via `POST http://localhost:8085/connectors`. This unblocked: ledger/notification Kafka consumption, Jaeger traces for those services, and Loki log export.

## Phase 2 Complete

All OBS requirements delivered:
- **OBS-01**: Distributed traces — payment-service and account-service in same Jaeger trace with parent-child spans
- **OBS-02**: Full span detail queryable (HTTP method, URL, status code, transferId)
- **OBS-03**: Prometheus metrics via OTel Collector — state-tagged counters + percentile histogram
- **OBS-04**: Grafana Banking Overview dashboard — 3 panels with exemplar trace links
- **OBS-05**: Loki logs queryable by `service_name` and structured fields

## Known Limitations (Deferred)

- **DB spans not in traces** — JDBC queries are not instrumented (no Micrometer JDBC tracer). Deferred to Phase 4 (Graph & RCA Foundation).
- **Debezium connector not auto-registered on startup** — Requires manual `POST` after `podman compose up`. A startup script or Kafka Connect auto-config would solve this.

## Operational Notes

- Test accounts for future sessions: create with `initialBalance` field (not `balance`) in POST body
- Debezium connector registration: `curl -X POST http://localhost:8085/connectors -H "Content-Type: application/json" -d @infra/debezium/outbox-connector.json`
- Smoke test: `./scripts/smoke-test-obs.sh --full`
