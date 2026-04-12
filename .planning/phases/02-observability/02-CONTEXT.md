# Phase 2: Observability — Context

**Gathered:** 2026-04-12
**Status:** Ready for planning

<domain>
## Phase Boundary

Full observability stack added to the existing Podman Compose setup — OTel Collector, Jaeger, Prometheus, Loki, and Grafana. All 4 Spring Boot services emit traces, metrics, and logs via OTLP to a single OTel Collector, which fans out to the three backends. No Kubernetes. No Istio. Compose-only phase.

This phase proves distributed tracing, metrics, and log aggregation work end-to-end before Phase 3 migrates to Kubernetes.

</domain>

<decisions>
## Implementation Decisions

### OTel Instrumentation Model
- **D-01:** Use Spring Boot 4 built-in OTel auto-configuration — do NOT use the OTel Java agent JAR and do NOT create manual `TracerProvider` beans. Spring Boot wires the provider automatically via `spring-boot-starter-opentelemetry`.
- **D-02:** All 4 services (account, payment, ledger, notification) get identical OTel configuration in `application.yml`. Only `spring.application.name` differs per service. One place to update if OTel endpoint changes.
- **D-03:** Trace sampling rate = **100%** (`management.tracing.sampling.probability: 1.0`). Every API call and Kafka message produces a trace. This is a dev/demo system — no requests slip through untraced.
- **D-04:** Services push OTel signals to the collector via **OTLP HTTP on port 4318** (not gRPC/4317). Simpler in Compose networking; Spring Boot 4 OTLP exporter defaults to HTTP.

### OTel Collector — Single Pipeline for All Signals
- **D-05:** **OTel Collector handles all three signals** — traces, metrics, AND logs. No Alloy, no Promtail container. Services push everything via OTLP to the collector; collector fans out to Jaeger, Prometheus, and Loki.
- **D-06:** OTel Collector pipeline:
  - Receives OTLP HTTP on 4318 (all signals from services)
  - Exports traces → Jaeger (OTLP)
  - Exposes Prometheus scrape endpoint → Prometheus pulls metrics
  - Exports logs → Loki (OTLP HTTP)
- **D-07:** OTLP log export requires `opentelemetry-logback-appender-1.0` dependency in every service pom.xml. A small Spring component must call `OpenTelemetryAppender.install(openTelemetry)` at startup — this is NOT auto-wired by Spring Boot 4. Researcher must find the exact version compatible with Spring Boot 4 / OTel SDK in use.
- **D-08:** Virtual threads context propagation — when Spring dispatches across virtual threads, trace context can drop (child spans appear as unrelated traces in Jaeger). Researcher must investigate and include the fix in the plan. This is a known issue with Java 21 virtual threads + OTel. Services all have `spring.threads.virtual.enabled: true`.

### Metrics Pipeline
- **D-09:** Prometheus gets metrics by scraping the OTel Collector's Prometheus exporter endpoint (not by scraping `/actuator/prometheus` on each service directly). Single scrape target. Aligns with OBS-03 "via OTel Collector" and sets up the pattern Phase 3 Istio metrics will extend.
- **D-10:** **payment-service only** emits custom banking metrics via Micrometer. Other 3 services emit standard Spring Boot / JVM metrics only. Custom metrics in payment-service:
  - `transfer_initiated_total` — counter, tagged by state (PENDING, PAYMENT_PROCESSING, CONFIRMED, CANCELLED)
  - `transfer_amount_total` — counter, total AUD amount transferred
  - `transfer_dlt_messages_total` — counter, messages routed to dead letter topic

### Grafana Dashboard
- **D-11:** Dashboard is **provisioned as a JSON file** mounted into the Grafana container via `compose.yml` volume. Appears automatically on startup. Version-controlled. No manual browser steps to create it.
- **D-12:** Phase 2 banking dashboard contains exactly **3 panels** (minimum to satisfy OBS-04):
  1. Transfer volume — transfers initiated per minute (from `transfer_initiated_total`)
  2. Transfer latency p99 — end-to-end transfer duration at 99th percentile
  3. Error rate — failed transfers / DLT message rate (from `transfer_dlt_messages_total`)
- **D-13:** Jaeger is configured as a Grafana data source. Prometheus metrics panels use **exemplars** to embed trace IDs — clicking a latency spike opens the exact trace in Jaeger. Demonstrates the observability loop end-to-end.

### Jaeger Deployment
- **D-14 (Claude's Discretion):** Jaeger all-in-one container (in-memory storage). No Badger/Elasticsearch backend needed for Compose-based dev. Data does not persist across container restarts — acceptable for a demo/learning environment.

### Claude's Discretion
- Exact OTel Collector config file structure and exporter versions — researcher verifies against running system
- Loki label strategy for log querying (service name, log level, transfer ID) — follow OTel semantic conventions
- Prometheus scrape interval — standard 15s default is fine
- Compose `depends_on` ordering for observability services — OTel Collector must be healthy before services start sending signals
- Exact pom.xml dependency coordinates and versions for `opentelemetry-logback-appender-1.0` — verify against Spring Boot 4 OTel SDK BOM

</decisions>

<requirements_delta>
## Requirements Updated by This Discussion

- **OBS-05 (revised):** Structured logs from all services are collected by the **OTel Collector** (not Promtail) and queryable in Grafana Loki by service name, transfer ID, and log level. Promtail/Alloy are not used in this phase.

</requirements_delta>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Project Definition
- `.planning/REQUIREMENTS.md §OBS-01..05` — Observability requirements; note OBS-05 is revised per Requirements Delta above
- `.planning/ROADMAP.md §Phase 2` — Phase goal, critical constraints (no manual TracerProvider, verify ECS field paths), and 3 success criteria
- `.planning/phases/01-acid-core-cdc-pipeline/01-CONTEXT.md` — Phase 1 decisions; all services already have virtual threads enabled
- `.planning/phases/01.1-cdc-pipeline-compliance-kind-spike/01.1-CONTEXT.md` — Phase 1.1 decisions; compose.yml already has Kafka, Debezium, 4 services

### Critical Research Items (Researcher Must Verify)
- Exact Spring Boot 4 OTel property key names for all three signal paths — STATE.md flags this as an open question
- `opentelemetry-logback-appender-1.0` version compatible with Spring Boot 4 OTel SDK BOM and method for installing it at startup
- Virtual threads + OTel context propagation fix for Java 21 (`StructuredTaskScope` + custom ThreadFactory or alternative approach)
- OTel Collector Loki exporter config (`otlphttp` exporter to Loki's OTLP endpoint, `limits_config.allow_structured_metadata: true`)

### External References
- Spring OTel blog (Nov 2025): https://spring.io/blog/2025/11/18/opentelemetry-with-spring-boot/
- OTel Spring Boot Starter getting started: https://opentelemetry.io/docs/zero-code/java/spring-boot-starter/getting-started/
- Grafana Loki OTLP integration: https://grafana.com/docs/loki/latest/send-data/otel/

### Critical Constraints (from ROADMAP.md)
- `CLAUDE.md §FLAG 6` — OTel Collector is required; add to compose.yml
- ROADMAP Phase 2 constraint: Use only `application.yml` for OTel configuration — do not create manual `TracerProvider` beans

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `compose.yml` (repo root) — Phase 2 adds 5 new services: `otel-collector`, `jaeger`, `prometheus`, `loki`, `grafana`. Existing service blocks remain unchanged except for new OTel env vars.
- All 4 service `application.yml` files — need OTel config block added; `spring.application.name` already set correctly in each.
- Phase 1 `TransferService` in payment-service — this is where custom Micrometer counters (`transfer_initiated_total`, `transfer_amount_total`) are injected.

### Established Patterns
- Compose health check pattern (Phase 1) — replicate for all 5 new observability services
- Spring `application.yml` env var override pattern (Phase 1) — OTel endpoint uses same `${ENV_VAR:default}` pattern for compose vs local dev

### Integration Points
- payment-service `TransferService` (or equivalent) — inject `MeterRegistry` for custom counters
- All service main classes or `@Configuration` classes — add `OpenTelemetryAppender.install(openTelemetry)` component
- `compose.yml` — add observability service block with volume mounts for OTel Collector config, Prometheus config, Grafana provisioning

</code_context>

<specifics>
## Specific Ideas

- OTel Collector is the single fan-out hub — not a sidecar, one shared collector for all 4 services. Simpler than per-service collectors.
- Grafana provisioning: mount two directories — `provisioning/datasources/` (Jaeger + Prometheus + Loki data source YAMLs) and `provisioning/dashboards/` (banking dashboard JSON + dashboard provider YAML).
- Exemplar support: Prometheus must be configured with `--enable-feature=exemplar-storage` to store trace IDs embedded in metrics; Grafana panel must have exemplars enabled.
- The virtual threads context fix is a prerequisite for traces being useful — without it, multi-hop payment-service → account-service calls appear as disconnected spans. Researcher must resolve this before the plan is written.

</specifics>

<deferred>
## Deferred Ideas

- **Kafka trace context propagation (OBS-V2-01)** — Carrying OTel trace context across Kafka message headers for end-to-end async trace continuity. Explicitly a v2 requirement — not in Phase 2 scope.
- **Grafana Alloy** — User asked about replacing Promtail with Alloy; discussion led to OTel Collector for everything instead. Alloy remains the Promtail successor for projects that need file/journal-based log collection.
- **JVM / service health panels in Grafana** — Heap usage, GC pauses, active threads. User selected the 3 OBS-04 panels only. Can be added in Phase 3+ when running on Kubernetes.
- **Per-service OTel sampling rates** — e.g. lower sampling for high-volume ledger-service. Deferred — 100% sampling is correct for Phase 2 demo volume.

</deferred>

---

*Phase: 02-observability*
*Context gathered: 2026-04-12*
