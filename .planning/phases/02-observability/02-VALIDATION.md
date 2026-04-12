---
phase: 2
slug: observability
status: draft
nyquist_compliant: true
wave_0_complete: false
created: 2026-04-12
---

# Phase 2 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | Shell scripts + curl + Maven tests |
| **Config file** | none — Wave 0 installs |
| **Quick run command** | `./scripts/smoke-test-obs.sh` |
| **Full suite command** | `./scripts/smoke-test-obs.sh --full` |
| **Estimated runtime** | ~60 seconds |

---

## Sampling Rate

- **After every task commit:** Run `./scripts/smoke-test-obs.sh`
- **After every plan wave:** Run `./scripts/smoke-test-obs.sh --full`
- **Before `/gsd-verify-work`:** Full suite must be green
- **Max feedback latency:** 60 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| 02-01-00 | 01 | 0 | OBS-02 | — | N/A | validation | `DOCKER_HOST=npipe:////./pipe/docker_engine docker run --rm jaegertracing/jaeger:2.17.0 --help 2>&1 \| grep -i config` | — | pending |
| 02-01-01 | 01 | 1 | OBS-01, OBS-03 | — | N/A | file-check | `python3 -c "import yaml; yaml.safe_load(open('infra/observability/otel-collector-config.yaml'))"` | W0 | pending |
| 02-01-02 | 01 | 1 | OBS-01 | — | N/A | file-check | `grep 'otel-collector' compose.yml \| wc -l` | W0 | pending |
| 02-02-00 | 02 | 0 | OBS-01 | — | N/A | validation | `mvn dependency:tree -pl account-service -Dincludes=io.opentelemetry` | — | pending |
| 02-02-1a | 02 | 1 | OBS-01, OBS-03 | — | N/A | compilation | `mvn compile -pl account-service,payment-service,ledger-service,notification-service -q` | W0 | pending |
| 02-02-1b | 02 | 1 | OBS-01, OBS-05 | T-02-07 | SLF4J parameterized logging | compilation | `mvn compile -pl account-service,payment-service,ledger-service,notification-service -q` | W0 | pending |
| 02-02-02 | 02 | 1 | OBS-03, OBS-04 | — | N/A | unit-test | `mvn test -pl payment-service -Dtest=PaymentServiceMetricsTest -q` | W0 | pending |
| 02-03-01 | 03 | 2 | OBS-04, OBS-05 | — | N/A | file-check + smoke | `python3 -c "import json; json.load(open('infra/observability/grafana/provisioning/dashboards/banking-dashboard.json'))"` | W0 | pending |
| 02-03-02 | 03 | 2 | OBS-01, OBS-02, OBS-03, OBS-04, OBS-05 | — | N/A | manual | `./scripts/smoke-test-obs.sh --full` + browser verification | W0 | pending |

*Status: pending -- green -- red -- flaky*

---

## Wave 0 Requirements

- [ ] Confirm Jaeger v2 config YAML field names via `jaegertracing/jaeger:2.17.0 --help` (Plan 01, Task 0)
- [ ] Confirm OTel SDK version via `mvn dependency:tree | grep opentelemetry-sdk` (Plan 02, Task 0)
- [ ] Confirm `management.opentelemetry.logging.export.otlp.endpoint` property name against SB 4.0.5 (Plan 02, Task 0)
- [ ] `scripts/smoke-test-obs.sh` — health checks for all observability services (Plan 03, Task 1)

*All Wave 0 items are required before Wave 1 execution can begin (Plan 01 Task 0, Plan 02 Task 0). Smoke test script is created in Wave 2 (Plan 03) but health checks run after Wave 1 plans complete.*

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Grafana dashboard shows transfer volume, p99 latency, error rate with real data | OBS-04 | Visual dashboard verification required | 1. Navigate to Grafana at http://localhost:3000. 2. Open the Banking Overview dashboard. 3. Initiate a transfer via API. 4. Verify panels show non-zero real data within 30s. |
| Jaeger trace shows parent/child span relationships across all 4 services | OBS-01, OBS-02 | Trace topology must be visually verified | 1. Navigate to Jaeger at http://localhost:16686. 2. Search for traces from payment-service. 3. Open a transfer trace. 4. Verify spans from account-service and payment-service are present with parent/child relationships. |
| LogQL query for specific transfer ID returns logs from all services | OBS-05 | Loki query verification required | 1. Initiate a transfer, note the transfer ID. 2. In Grafana Explore, run: `{service_name="payment-service"} \| json \| transferId="<id>"`. 3. Verify results appear. |

---

## Validation Sign-Off

- [x] All tasks have `<automated>` verify or Wave 0 dependencies
- [x] Sampling continuity: no 3 consecutive tasks without automated verify
- [x] Wave 0 covers all MISSING references
- [x] No watch-mode flags
- [x] Feedback latency < 60s
- [x] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
