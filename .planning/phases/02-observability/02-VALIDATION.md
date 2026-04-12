---
phase: 2
slug: observability
status: draft
nyquist_compliant: false
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
| 02-01-01 | 01 | 0 | OBS-01 | — | N/A | integration | `curl -s http://localhost:8080/actuator/health` | ❌ W0 | ⬜ pending |
| 02-01-02 | 01 | 1 | OBS-01 | — | N/A | integration | `curl -s http://localhost:4318/v1/traces` | ❌ W0 | ⬜ pending |
| 02-02-01 | 02 | 1 | OBS-02 | — | N/A | integration | `curl -s http://localhost:9090/api/v1/targets` | ❌ W0 | ⬜ pending |
| 02-03-01 | 03 | 1 | OBS-03 | — | N/A | integration | `curl -s http://localhost:16686/api/traces` | ❌ W0 | ⬜ pending |
| 02-04-01 | 04 | 1 | OBS-04 | — | N/A | integration | `curl -s http://localhost:3100/ready` | ❌ W0 | ⬜ pending |
| 02-05-01 | 05 | 2 | OBS-05 | — | N/A | manual | Grafana dashboard shows real data panels | — | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] `scripts/smoke-test-obs.sh` — health checks for all observability services
- [ ] Confirm OTel SDK version via `mvn dependency:tree | grep opentelemetry-sdk`
- [ ] Confirm Jaeger v2 config YAML field names via `jaegertracing/jaeger:2.17.0 --help`
- [ ] Confirm `management.opentelemetry.logging.export.otlp.endpoint` property name against SB 4.0.5

*All Wave 0 items are required before Wave 1 execution can begin.*

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Grafana dashboard shows transfer volume, p99 latency, error rate with real data | OBS-02 | Visual dashboard verification required | 1. Navigate to Grafana at http://localhost:3000. 2. Open the Banking Overview dashboard. 3. Initiate a transfer via API. 4. Verify panels show non-zero real data within 30s. |
| Jaeger trace shows parent/child span relationships across all 4 services | OBS-01 | Trace topology must be visually verified | 1. Navigate to Jaeger at http://localhost:16686. 2. Search for traces from account-service. 3. Open a transfer trace. 4. Verify spans from account-service, payment-service, ledger-service, notification-service are all present with parent/child relationships. |
| LogQL query for specific transfer ID returns logs from all services | OBS-03 | Loki query verification required | 1. Initiate a transfer, note the transfer ID. 2. In Grafana Explore, run: `{transfer_id="<id>"}`. 3. Verify results appear from all 4 services. |

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 60s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
