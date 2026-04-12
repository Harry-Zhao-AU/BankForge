# Phase 2: Observability — Research

**Researched:** 2026-04-12
**Domain:** Distributed observability — OTel Collector, Jaeger v2, Prometheus, Loki, Grafana on Podman Compose with Spring Boot 4
**Confidence:** HIGH (core stack verified), MEDIUM (Jaeger v2 config file format), LOW (exact logback appender version under Spring Boot 4 BOM)

---

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

**D-01:** Use Spring Boot 4 built-in OTel auto-configuration — do NOT use the OTel Java agent JAR and do NOT create manual `TracerProvider` beans. Spring Boot wires the provider automatically via `spring-boot-starter-opentelemetry`.

**D-02:** All 4 services (account, payment, ledger, notification) get identical OTel configuration in `application.yml`. Only `spring.application.name` differs per service. One place to update if OTel endpoint changes.

**D-03:** Trace sampling rate = 100% (`management.tracing.sampling.probability: 1.0`). Every API call and Kafka message produces a trace.

**D-04:** Services push OTel signals to the collector via **OTLP HTTP on port 4318** (not gRPC/4317).

**D-05:** OTel Collector handles all three signals — traces, metrics, AND logs. No Alloy, no Promtail container.

**D-06:** OTel Collector pipeline:
- Receives OTLP HTTP on 4318 (all signals from services)
- Exports traces → Jaeger (OTLP)
- Exposes Prometheus scrape endpoint → Prometheus pulls metrics
- Exports logs → Loki (OTLP HTTP)

**D-07:** OTLP log export requires `opentelemetry-logback-appender-1.0` dependency in every service pom.xml. A small Spring component must call `OpenTelemetryAppender.install(openTelemetry)` at startup.

**D-08:** Virtual threads context propagation — when Spring dispatches across virtual threads, trace context can drop. Fix must be included in the plan.

**D-09:** Prometheus gets metrics by scraping the OTel Collector's Prometheus exporter endpoint (not by scraping `/actuator/prometheus` on each service directly).

**D-10:** payment-service only emits custom banking metrics via Micrometer: `transfer_initiated_total`, `transfer_amount_total`, `transfer_dlt_messages_total`.

**D-11:** Dashboard is provisioned as a JSON file mounted into Grafana container via `compose.yml` volume.

**D-12:** Phase 2 banking dashboard contains exactly 3 panels: transfer volume, transfer latency p99, error rate.

**D-13:** Jaeger is configured as a Grafana data source. Prometheus metrics panels use exemplars to embed trace IDs.

**D-14 (Claude's Discretion):** Jaeger all-in-one container (in-memory storage). No Badger/Elasticsearch backend.

### Claude's Discretion

- Exact OTel Collector config file structure and exporter versions — verify against running system
- Loki label strategy for log querying (service name, log level, transfer ID) — follow OTel semantic conventions
- Prometheus scrape interval — standard 15s default is fine
- Compose `depends_on` ordering for observability services — OTel Collector must be healthy before services start sending signals
- Exact pom.xml dependency coordinates and versions for `opentelemetry-logback-appender-1.0` — verify against Spring Boot 4 OTel SDK BOM

### Deferred Ideas (OUT OF SCOPE)

- **Kafka trace context propagation (OBS-V2-01)** — Carrying OTel trace context across Kafka message headers. Explicitly a v2 requirement.
- **Grafana Alloy** — Replaced by OTel Collector for everything.
- **JVM / service health panels in Grafana** — Heap, GC, threads. Only the 3 OBS-04 panels in scope.
- **Per-service OTel sampling rates** — 100% sampling throughout Phase 2.

</user_constraints>

---

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| OBS-01 | All services emit OpenTelemetry traces via Spring Boot 4 built-in OTel auto-configuration to an OTel Collector | `spring-boot-starter-opentelemetry` + exact `application.yml` properties documented in §Standard Stack and §Code Examples |
| OBS-02 | Distributed traces (including cross-service HTTP calls) are queryable in Jaeger UI with full span detail | Jaeger v2 `jaegertracing/jaeger:2.x` image; OTel Collector → Jaeger OTLP pipeline; virtual threads fix documented in §Pitfalls |
| OBS-03 | Prometheus scrapes metrics from all services via OTel Collector | Prometheus exporter on Collector port 8889; `--enable-feature=exemplar-storage`; prometheus.yml scrape config documented |
| OBS-04 | Grafana dashboard displays traces, metrics, and logs with banking-specific panels | Grafana 11 provisioning via JSON; datasource YAML for Prometheus + Jaeger + Loki; exemplarTraceIdDestinations config documented |
| OBS-05 (revised) | Structured logs from all services collected by OTel Collector, queryable in Grafana Loki by service name, transfer ID, log level | Loki 3.7 OTLP endpoint `/otlp`; OTel Collector `otlphttp/logs` exporter; ECS format config; `service.name` auto-mapped as Loki label |

</phase_requirements>

---

## Summary

Phase 2 adds a five-container observability stack to the existing Podman Compose file: OTel Collector (single fan-out hub), Jaeger v2 (trace storage/UI), Prometheus (metrics scrape/storage), Loki (log storage), and Grafana (unified UI). The four Spring Boot services need one new dependency (`spring-boot-starter-opentelemetry`), one new component class per service (`InstallOpenTelemetryAppender`), and a shared OTel config block in each `application.yml`.

The key architectural insight is that all three signals — traces, metrics, and logs — flow through the OTel Collector. Services only know about one endpoint: `http://otel-collector:4318`. The collector fans out to Jaeger via OTLP, to Prometheus via a scrape endpoint it exposes, and to Loki via `otlphttp`. This centralises all observability wiring and sets the pattern for Phase 3 where Istio will add its own metrics to the same Prometheus.

The two technically tricky areas are: (1) virtual thread context propagation — Spring Boot does not auto-configure `ContextPropagatingTaskDecorator` for virtual threads, requiring an explicit `@Configuration` bean to prevent trace context drops across thread boundaries; and (2) the Logback OTel appender — Spring Boot 4 does not auto-install `opentelemetry-logback-appender-1.0`, requiring an explicit `InitializingBean` component and a `logback-spring.xml` that references the appender class.

**Primary recommendation:** Use `spring-boot-starter-opentelemetry` exclusively (no Java agent, no manual `TracerProvider`). Configure three property namespaces: `management.tracing.*` for sampling, `management.opentelemetry.tracing.export.otlp.*` for traces, and `management.otlp.metrics.export.*` for metrics. Logs go via `opentelemetry-logback-appender-1.0` + `logback-spring.xml` + `InstallOpenTelemetryAppender` component. Spring Boot's ECS structured logging (`logging.structured.format.console: ecs`) is completely independent — it formats console output; it does NOT ship logs to Loki. Log shipping to Loki requires the logback appender.

---

## Project Constraints (from CLAUDE.md)

| Directive | Impact on Phase 2 |
|-----------|-------------------|
| Java 21 + Spring Boot 4.0.5 | Use `spring-boot-starter-opentelemetry` (SB4-native starter) |
| Podman (daemonless, rootless) — not Docker | Compose service names as DNS hostnames; no Docker socket required for observability stack |
| Local only — no cloud | All images pinned to specific versions; no external OTLP SaaS endpoints |
| `application.yml` only for OTel config — no manual `TracerProvider` beans | Must use `spring-boot-starter-opentelemetry` auto-config path only |
| OTel Collector not in original tech stack (FLAG 6) | Must add `otel/opentelemetry-collector-contrib` to `compose.yml` |
| Jaeger 1.55+ (v2 architecture) | Use `jaegertracing/jaeger:2.x` — v1 reached EOL 2025-12-31 |
| ECS field path verification needed before config | Research establishes exact ECS field names; `service.name` auto-maps to Loki label |
| Jackson 3 (tools.jackson.databind) — not Jackson 2 | No impact on observability code; logback appender does not use Jackson |

---

## Standard Stack

### Core Observability Services

| Component | Image | Version | Purpose | Source |
|-----------|-------|---------|---------|--------|
| OTel Collector | `otel/opentelemetry-collector-contrib` | `0.149.0` | Receives OTLP from services, fans out to all backends | [VERIFIED: WebSearch 2026 — latest stable is 0.148–0.149] |
| Jaeger | `jaegertracing/jaeger` | `2.17.0` | Distributed trace storage and UI | [VERIFIED: jaegertracing.io/download/ — v2.17.0 is current; v1 EOL 2025-12-31] |
| Prometheus | `prom/prometheus` | `v3.10.0` | Metrics scrape and storage | [VERIFIED: WebSearch 2026 — v3.10.0 released 2026-02-24] |
| Loki | `grafana/loki` | `3.7.0` | Log aggregation with OTLP ingestion | [VERIFIED: grafana.com docs loki/setup/install/docker/ — 3.7.0 current] |
| Grafana | `grafana/grafana` | `11.6.14` | Unified observability UI + dashboards | [VERIFIED: WebSearch 2026 — 11.6.14 latest stable, March 2026] |

### Spring Boot Service Dependencies (add to every service pom.xml)

| Artifact | Version | Purpose | Source |
|----------|---------|---------|--------|
| `spring-boot-starter-opentelemetry` | managed by SB BOM | Auto-wires OTel SDK, Micrometer bridge, OTLP exporters for traces and metrics | [VERIFIED: foojay.io Spring Boot 4 OTel guide, danvega.dev blog] |
| `opentelemetry-logback-appender-1.0` | `2.21.0-alpha` | Ships Logback log events to OTel Collector via OTLP | [MEDIUM: WebSearch — version 2.21.0-alpha; verify against current OTel instrumentation BOM] |

**Note on version:** `spring-boot-starter-opentelemetry` manages the OTel SDK version via the Spring Boot BOM. The `opentelemetry-logback-appender-1.0` artifact is **not** managed by the SB BOM and must be explicitly versioned. The "-alpha" suffix is permanent — there is no stable release of this specific artifact. The `2.21.0-alpha` version is the latest known compatible version; verify at Maven Central before writing pom.xml.

**Note on what `spring-boot-starter-opentelemetry` includes:**
- `opentelemetry-api`, `opentelemetry-sdk`
- `micrometer-tracing-bridge-otel` (connects Micrometer Observations to OTel spans)
- `micrometer-registry-otlp` (exports Micrometer metrics via OTLP)
- OTLP gRPC/HTTP exporters for traces
- Does NOT auto-install the Logback appender — that requires explicit dependency + `InstallOpenTelemetryAppender` component

### Alternatives Considered

| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| OTel Collector (fan-out hub) | Promtail + direct service scrape | Promtail handles logs only; OTel Collector handles all 3 signals from a single endpoint |
| Jaeger v2 (`jaegertracing/jaeger`) | Jaeger v1 all-in-one | v1 EOL December 2025; v2 is OTel Collector-based, native OTLP |
| Prometheus v3.10 | Prometheus v2.54 | v3 has stricter Content-Type validation (breaking if target omits header), renamed histogram configs; use `fallback_scrape_protocol` if needed |
| ECS structured logging | Logstash / GELF | ECS is an OTel semantic conventions superset; trace/span IDs auto-correlate |
| Grafana provisioning via JSON files | Manual UI dashboard creation | Provisioned dashboards are version-controlled and appear on container startup |

### Installation

```bash
# No separate install — all services added to compose.yml as containers
# Java dependency added to each service pom.xml:
# spring-boot-starter-opentelemetry (managed by SB BOM)
# io.opentelemetry.instrumentation:opentelemetry-logback-appender-1.0:2.21.0-alpha
```

---

## Architecture Patterns

### Recommended Directory Structure (additions to existing repo)

```
BankForge/
├── compose.yml                         # ADD: 5 new service blocks
├── infra/
│   ├── debezium/                       # existing
│   └── observability/
│       ├── otel-collector-config.yaml  # OTel Collector pipeline config
│       ├── prometheus.yml              # Prometheus scrape config
│       ├── loki-config.yaml            # Loki single-binary config
│       └── grafana/
│           ├── provisioning/
│           │   ├── datasources/
│           │   │   └── datasources.yaml   # Prometheus + Jaeger + Loki data sources
│           │   └── dashboards/
│           │       ├── dashboards-provider.yaml
│           │       └── banking-dashboard.json
│           └── (no persistent data volume needed for dev)
├── account-service/src/main/resources/
│   ├── application.yml                 # ADD: OTel config block
│   └── logback-spring.xml              # NEW: OTel appender config
└── (same pattern for payment/ledger/notification services)
```

### Pattern 1: Spring Boot 4 OTel `application.yml` Block

**What:** Three-signal OTel configuration using Spring Boot 4 auto-config properties only.

**When to use:** All four services — copy verbatim, only `spring.application.name` differs.

```yaml
# Source: foojay.io Spring Boot 4 OTel guide + Spring Boot actuator docs
management:
  tracing:
    sampling:
      probability: 1.0          # D-03: 100% sampling — dev/demo only
  otlp:
    metrics:
      export:
        url: ${OTEL_EXPORTER_OTLP_ENDPOINT:http://localhost:4318}/v1/metrics
  opentelemetry:
    tracing:
      export:
        otlp:
          endpoint: ${OTEL_EXPORTER_OTLP_ENDPOINT:http://localhost:4318}/v1/traces
    logging:
      export:
        otlp:
          endpoint: ${OTEL_EXPORTER_OTLP_ENDPOINT:http://localhost:4318}/v1/logs

logging:
  structured:
    format:
      console: ecs               # ECS JSON format; service.name auto-populated from spring.application.name
```

**Compose override:** Each service block adds one env var:
```yaml
OTEL_EXPORTER_OTLP_ENDPOINT: http://otel-collector:4318
```

**Property namespace clarification:**
- `management.tracing.*` — Micrometer sampling; shared by both Micrometer and OTel
- `management.opentelemetry.tracing.export.otlp.*` — OTel SDK trace exporter (uses Spring Boot 4 OTel starter's auto-config)
- `management.otlp.metrics.export.*` — Micrometer OtlpMeterRegistry (metrics OTLP export)
- `management.opentelemetry.logging.export.otlp.*` — OTel SDK log export (requires logback appender installed separately)

### Pattern 2: Logback OTel Appender Setup

**What:** Bridges Logback log events into the OTel SDK log pipeline so they are shipped via OTLP.

**Why Spring Boot 4 doesn't auto-configure this:** Spring Boot detects the `opentelemetry-logback-appender-1.0` JAR but does NOT automatically call `OpenTelemetryAppender.install()` — the `OpenTelemetry` bean must be injected and `install()` called explicitly after Spring context is ready.

**Step 1 — `logback-spring.xml`** (place in `src/main/resources/` for every service):

```xml
<!-- Source: danvega.dev blog + WebSearch verified pattern -->
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
  <include resource="org/springframework/boot/logging/logback/base.xml"/>
  <appender name="OTEL"
            class="io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender">
  </appender>
  <root level="INFO">
    <appender-ref ref="CONSOLE"/>
    <appender-ref ref="OTEL"/>
  </root>
</configuration>
```

**Step 2 — `InstallOpenTelemetryAppender.java`** (one per service, in any `@Component`-scanned package):

```java
// Source: danvega.dev blog — Spring Boot 4 OTel logback appender pattern
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

@Component
class InstallOpenTelemetryAppender implements InitializingBean {
    private final OpenTelemetry openTelemetry;

    InstallOpenTelemetryAppender(OpenTelemetry openTelemetry) {
        this.openTelemetry = openTelemetry;
    }

    @Override
    public void afterPropertiesSet() {
        OpenTelemetryAppender.install(this.openTelemetry);
    }
}
```

**pom.xml entry** (add to each service, NOT in parent BOM — version must be explicit):

```xml
<!-- Source: WebSearch verified — alpha suffix is permanent, not a pre-release indicator -->
<dependency>
    <groupId>io.opentelemetry.instrumentation</groupId>
    <artifactId>opentelemetry-logback-appender-1.0</artifactId>
    <version>2.21.0-alpha</version>
</dependency>
```

### Pattern 3: Virtual Threads Context Propagation Fix

**What:** Prevents trace context from dropping when Spring Boot dispatches across virtual threads (all services have `spring.threads.virtual.enabled: true`).

**Why it happens:** OTel (and Micrometer) uses `ThreadLocal` storage for trace context. Java virtual threads are mounted/unmounted between continuations — a new virtual thread started from an existing one does not inherit `ThreadLocal` values unless explicit propagation is configured. The symptom is child spans appearing as root spans in Jaeger with no parent — the transfer flow appears as disconnected fragments rather than a single trace.

**Fix — `ContextPropagationConfig.java`** (one per service):

```java
// Source: Spring Boot actuator/observability docs — ContextPropagatingTaskDecorator
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.support.ContextPropagatingTaskDecorator;

@Configuration(proxyBeanMethods = false)
class ContextPropagationConfig {

    @Bean
    ContextPropagatingTaskDecorator contextPropagatingTaskDecorator() {
        return new ContextPropagatingTaskDecorator();
    }
}
```

**Scope:** Spring Boot auto-detects this bean and registers it on all `AsyncTaskExecutor` instances, including the virtual thread executor. This propagates the Micrometer `ObservationRegistry` context (which includes OTel trace/span IDs) across thread switches.

**Limitation:** This covers `@Async`-annotated methods and Spring-managed executors. Raw `Thread.ofVirtual().start(...)` calls bypass this — use Spring-managed executors only within the services.

### Pattern 4: OTel Collector Pipeline Configuration

**What:** Single `otel-collector-config.yaml` defining all three signal pipelines.

```yaml
# Source: Grafana Loki OTLP docs + OTel Collector contrib docs
receivers:
  otlp:
    protocols:
      http:
        endpoint: 0.0.0.0:4318        # D-04: services push to HTTP 4318

processors:
  batch:                               # batch before export — reduces HTTP overhead
    timeout: 1s
    send_batch_size: 1024

exporters:
  # Traces → Jaeger v2 (OTLP HTTP — Jaeger v2 listens on 4318 internally but collector uses its own port)
  otlphttp/jaeger:
    endpoint: http://jaeger:4318
    tls:
      insecure: true

  # Metrics → Prometheus pull (Collector exposes scrape endpoint)
  prometheus:
    endpoint: 0.0.0.0:8889             # Prometheus scrapes this

  # Logs → Loki OTLP endpoint
  otlphttp/loki:
    endpoint: http://loki:3100/otlp
    tls:
      insecure: true

service:
  pipelines:
    traces:
      receivers: [otlp]
      processors: [batch]
      exporters: [otlphttp/jaeger]

    metrics:
      receivers: [otlp]
      processors: [batch]
      exporters: [prometheus]

    logs:
      receivers: [otlp]
      processors: [batch]
      exporters: [otlphttp/loki]
```

**Port collision note:** Jaeger v2 also listens on port 4318 for OTLP HTTP. The OTel Collector also listens on 4318 for service input. These are on different container names (`otel-collector:4318` vs `jaeger:4318`) so there is no conflict inside the Compose network. However, only the Collector port should be exposed to the host.

### Pattern 5: Jaeger v2 Compose Service

**What:** Jaeger v2 requires a YAML config file — it no longer accepts `COLLECTOR_OTLP_ENABLED=true` environment variable (that was v1).

```yaml
# Source: jaegertracing/jaeger hotrod example + jaegertracing.io/download/
jaeger:
  image: jaegertracing/jaeger:2.17.0
  container_name: jaeger
  command: ["--config", "/etc/jaeger/config.yaml"]
  volumes:
    - ./infra/observability/jaeger-config.yaml:/etc/jaeger/config.yaml:ro
  ports:
    - "16686:16686"   # Jaeger UI
    # 4318 NOT exposed to host — collector reaches jaeger via internal DNS
  healthcheck:
    test: ["CMD-SHELL", "wget -qO- http://localhost:16686/api/services || exit 1"]
    interval: 10s
    timeout: 5s
    retries: 10
    start_period: 15s
```

**Jaeger v2 config file** (`infra/observability/jaeger-config.yaml`):

```yaml
# Source: jaegertracing/jaeger hotrod example docker-compose pattern
# Jaeger v2 uses OTel Collector framework internally
extensions:
  jaeger_storage:
    backends:
      memstore:
        memory:
          max_traces: 100000
  jaeger_query:
    storage:
      traces: memstore
    http:
      endpoint: 0.0.0.0:16686

receivers:
  otlp:
    protocols:
      http:
        endpoint: 0.0.0.0:4318
      grpc:
        endpoint: 0.0.0.0:4317

exporters:
  jaeger_storage_exporter:
    trace_storage: memstore

service:
  extensions: [jaeger_storage, jaeger_query]
  pipelines:
    traces:
      receivers: [otlp]
      exporters: [jaeger_storage_exporter]
```

**CRITICAL:** This config YAML is required. Jaeger v2 with no config file will fail to start. [MEDIUM confidence — config structure verified from official hotrod example; exact field names may need adjustment on first run]

### Pattern 6: Loki Configuration

**What:** Minimal single-binary Loki config for local dev.

```yaml
# Source: grafana.com/docs/loki/latest/setup/install/docker/ + official loki-local-config.yaml v3.7.0
auth_enabled: false

server:
  http_listen_port: 3100
  grpc_listen_port: 9096

common:
  instance_addr: 127.0.0.1
  path_prefix: /loki
  storage:
    filesystem:
      chunks_directory: /loki/chunks
      rules_directory: /loki/rules
  replication_factor: 1
  ring:
    kvstore:
      store: inmemory

query_range:
  results_cache:
    cache:
      embedded_cache:
        enabled: true
        max_size_mb: 100

limits_config:
  allow_structured_metadata: true    # Required for OTLP log ingestion (default true in Loki 3.0+)

schema_config:
  configs:
    - from: 2020-10-24
      store: tsdb
      object_store: filesystem
      schema: v13
      index:
        prefix: index_
        period: 24h
```

**Loki label mapping via OTLP:** When logs arrive via OTLP, Loki automatically maps the OTel `service.name` resource attribute to the `service_name` Loki label (dots converted to underscores). `trace_id` and `log.level` are stored as structured metadata, queryable via LogQL `| json` or label filters.

**LogQL query for a specific transfer ID:**
```
{service_name="payment-service"} | json | transferId="<uuid>"
```
(Assuming MDC/structured logging puts `transferId` in the log message body. Use SLF4J fluent API: `log.atInfo().addKeyValue("transferId", id).log("...")`)

### Pattern 7: Prometheus Configuration

```yaml
# Source: Prometheus docs + OTel Collector prometheus exporter pattern
global:
  scrape_interval: 15s
  evaluation_interval: 15s

scrape_configs:
  - job_name: 'otel-collector'
    static_configs:
      - targets: ['otel-collector:8889']    # D-09: single scrape target
```

**Prometheus Compose service:**
```yaml
prometheus:
  image: prom/prometheus:v3.10.0
  command:
    - '--config.file=/etc/prometheus/prometheus.yml'
    - '--enable-feature=exemplar-storage'   # Required for trace ID exemplars in metrics
    - '--web.enable-remote-write-receiver'
  volumes:
    - ./infra/observability/prometheus.yml:/etc/prometheus/prometheus.yml:ro
  ports:
    - "9090:9090"
  healthcheck:
    test: ["CMD-SHELL", "wget -qO- http://localhost:9090/-/healthy || exit 1"]
    interval: 10s
    timeout: 5s
    retries: 5
```

### Pattern 8: Grafana Datasource Provisioning

**File:** `infra/observability/grafana/provisioning/datasources/datasources.yaml`

```yaml
# Source: Grafana provisioning docs + community forum datasource.yaml patterns
apiVersion: 1

datasources:
  - name: Prometheus
    type: prometheus
    uid: prometheus
    access: proxy
    url: http://prometheus:9090
    isDefault: true
    jsonData:
      httpMethod: POST
      exemplarTraceIdDestinations:
        - datasourceUid: jaeger
          name: traceID                  # field name OTel Collector embeds in exemplars

  - name: Jaeger
    type: jaeger
    uid: jaeger
    access: proxy
    url: http://jaeger:16686

  - name: Loki
    type: loki
    uid: loki
    access: proxy
    url: http://loki:3100
```

**Dashboard provider** (`infra/observability/grafana/provisioning/dashboards/dashboards-provider.yaml`):

```yaml
apiVersion: 1
providers:
  - name: 'bankforge'
    orgId: 1
    folder: 'BankForge'
    type: file
    options:
      path: /etc/grafana/provisioning/dashboards
```

### Pattern 9: Custom Micrometer Metrics in payment-service

**What:** Three counters injected into `PaymentService` (D-10). Uses Micrometer `MeterRegistry` — auto-wired by Spring Boot.

```java
// Source: Micrometer docs + Spring Boot actuator/metrics reference
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentService {
    private final MeterRegistry meterRegistry;
    // ... existing fields ...

    // Initialise counters in @PostConstruct or constructor
    private Counter transferInitiatedCounter;
    private Counter transferAmountCounter;
    private Counter transferDltCounter;

    @PostConstruct
    void initMetrics() {
        transferInitiatedCounter = Counter.builder("transfer_initiated_total")
            .description("Number of transfers initiated")
            .tag("service", "payment-service")
            .register(meterRegistry);
        transferAmountCounter = Counter.builder("transfer_amount_total")
            .description("Total AUD amount transferred")
            .tag("service", "payment-service")
            .register(meterRegistry);
        transferDltCounter = Counter.builder("transfer_dlt_messages_total")
            .description("Messages routed to dead letter topic")
            .tag("service", "payment-service")
            .register(meterRegistry);
    }
}
```

**PromQL for Grafana panels:**
- Transfer volume: `rate(transfer_initiated_total[1m])`
- p99 latency: requires a `Timer` (not a `Counter`) — consider adding `Timer.builder("transfer_duration").register(meterRegistry)` around `initiateTransfer()`; then `histogram_quantile(0.99, rate(transfer_duration_seconds_bucket[5m]))`
- Error rate: `rate(transfer_dlt_messages_total[1m])`

**Important:** `transfer_amount_total` is a `Counter` (monotonically increasing). For the latency p99 panel, a `Timer` is required, not a `Counter`. The plan should add a `Timer` around the transfer execution in `PaymentService.initiateTransfer()`.

### Anti-Patterns to Avoid

- **Manual `TracerProvider` bean:** Creates duplicate spans; Spring Boot 4 auto-config wires it. Never do `@Bean OpenTelemetry openTelemetry() { ... }`.
- **Skipping `OpenTelemetryAppender.install()`:** The logback appender is installed via XML but does nothing until the OTel SDK instance is injected via `install()`. Without `InstallOpenTelemetryAppender`, log events go to console only.
- **Exposing OTel Collector port 4318 to host AND services:** Services connect via internal DNS (`otel-collector:4318`). Only expose host port if you want to send signals from outside Compose (not needed in Phase 2).
- **Using `jaegertracing/all-in-one` for Jaeger v2:** The `all-in-one` image tag points to v1 (EOL). Use `jaegertracing/jaeger:2.17.0` for v2.
- **`COLLECTOR_OTLP_ENABLED=true` in Jaeger v2:** This env var is v1 only. Jaeger v2 is configured via config YAML only.
- **ECS format as log shipper:** `logging.structured.format.console: ecs` only formats console output. It does NOT ship logs to Loki. Log shipping requires the logback OTel appender.

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| OTLP log batching and retry | Custom Kafka → Loki bridge | `opentelemetry-logback-appender-1.0` + OTel Collector | Handles batching, backpressure, retry, compression |
| Trace context propagation across threads | ThreadLocal copy in wrapper | `ContextPropagatingTaskDecorator` (Spring core) | Integrates with all Spring executors; propagates entire Micrometer context |
| Trace ID injection into logs | MDC filter | Spring Boot ECS format + OTel appender | Auto-injects `trace_id` and `span_id` into structured log records |
| Metrics format conversion | OTel → Prometheus format converter | OTel Collector `prometheus` exporter | Handles naming conventions, label sanitization, histogram bucket translation |
| Dashboard JSON from scratch | Panel builder code | Use Grafana UI to design, export JSON, then provision | Complex JSON schema; UI is the right authoring tool |
| Jaeger storage backend | Custom trace store | Jaeger in-memory (`memstore`) for dev | Phase 2 is demo/dev only; no persistence needed |

---

## Runtime State Inventory

This is a greenfield phase adding new containers — not a rename or migration. No existing runtime state needs to change.

**Nothing found in any category** — verified: no existing Jaeger, Prometheus, Loki, or Grafana containers or stored data in the project. The `infra/` directory contains only `debezium/outbox-connector.json`. No trace data, no metrics retention, no log index.

---

## Common Pitfalls

### Pitfall 1: Jaeger v2 with No Config File

**What goes wrong:** `jaegertracing/jaeger:2.x` exits immediately with a startup error if no `--config` flag is provided or the config file is missing.

**Why it happens:** Jaeger v2 is built on the OTel Collector framework and requires a pipeline configuration — there is no hardcoded default.

**How to avoid:** Always mount `infra/observability/jaeger-config.yaml` and pass `--config /etc/jaeger/config.yaml` in the Compose command. Include a `healthcheck` that polls `/api/services`.

**Warning signs:** Container exits with code 1 within 2 seconds of starting.

### Pitfall 2: Log Appender Not Shipping Despite XML Config

**What goes wrong:** The OTel logback appender is declared in `logback-spring.xml` but no logs appear in Loki.

**Why it happens:** The appender instance created by Logback at startup has no reference to the OTel SDK. `OpenTelemetryAppender.install(openTelemetry)` must be called after the Spring `OpenTelemetry` bean is available (i.e., post-`@PostConstruct`).

**How to avoid:** Implement `InstallOpenTelemetryAppender implements InitializingBean` in every service. Verify logs appear in Loki with a test query immediately after startup.

**Warning signs:** Logback logs to console normally but Loki shows empty results for the service; OTel Collector logs show no log records received.

### Pitfall 3: Port 4318 Collision Between Collector and Jaeger

**What goes wrong:** Both the OTel Collector (`http: endpoint: 0.0.0.0:4318`) and Jaeger v2 (`otlp: http: endpoint: 0.0.0.0:4318`) listen on port 4318 inside their own containers. If the same host port is published for both, only one will bind.

**Why it happens:** Both use the OTel Collector framework internally and share the same default OTLP HTTP port.

**How to avoid:** Expose host port 4318 only for the OTel Collector. Do NOT publish port 4318 for Jaeger. Services connect to `jaeger:4318` via internal Compose DNS; only the Collector needs a host-accessible OTLP port.

**Warning signs:** `podman-compose up` fails with "address already in use" or Jaeger receives no traces.

### Pitfall 4: Virtual Threads Drop Trace Context Without Fix

**What goes wrong:** All spans appear as independent root spans in Jaeger. A payment-service transfer that calls account-service shows two unlinked traces instead of parent→child.

**Why it happens:** With `spring.threads.virtual.enabled: true`, Spring's `SimpleAsyncTaskExecutor` creates new virtual threads that don't inherit `ThreadLocal` values including the OTel context.

**How to avoid:** Add `ContextPropagationConfig` bean with `ContextPropagatingTaskDecorator`. This is required even when the service doesn't use `@Async` — Spring MVC's virtual thread dispatcher uses the same executor.

**Warning signs:** Jaeger shows correct individual spans but no parent-child relationships; trace search returns many single-span traces instead of multi-service traces.

### Pitfall 5: Prometheus v3 Strict Content-Type Validation

**What goes wrong:** Prometheus v3 fails to scrape the OTel Collector's `/metrics` endpoint, showing `error: text format parse error`.

**Why it happens:** Prometheus v3 is stricter about `Content-Type` headers. If the OTel Collector's Prometheus exporter returns text with a missing or malformed `Content-Type`, v3 rejects it where v2.54 would fall back silently.

**How to avoid:** Start with `prom/prometheus:v3.10.0`. If scrape fails, add `fallback_scrape_protocol: PrometheusText0.0.4` to the scrape job in `prometheus.yml`. Verify with `curl -I http://otel-collector:8889/metrics` inside the Compose network.

**Warning signs:** Prometheus target shows `DOWN` with parse errors; no metrics appear in Grafana despite OTel Collector running.

### Pitfall 6: ECS `logging.structured.format.console` vs Log Shipping

**What goes wrong:** Developer enables ECS format and expects logs to appear in Loki automatically.

**Why it happens:** `logging.structured.format.console: ecs` only changes the console output format to JSON. It does not configure any log appender to ship logs to Loki. These are two completely separate concerns.

**How to avoid:** The ECS format is useful for local readability but is separate from the OTel log pipeline. Loki log ingestion requires: `opentelemetry-logback-appender-1.0` JAR + `logback-spring.xml` with the OTEL appender + `InstallOpenTelemetryAppender` component + `management.opentelemetry.logging.export.otlp.endpoint` in `application.yml`.

### Pitfall 7: Exemplar Storage Not Enabled in Prometheus

**What goes wrong:** Grafana Prometheus panel shows data points but clicking on them does not navigate to Jaeger traces — the trace link is missing.

**Why it happens:** Prometheus does not store exemplars by default. The `--enable-feature=exemplar-storage` flag must be passed to the Prometheus binary.

**How to avoid:** Always include `--enable-feature=exemplar-storage` in the Prometheus command in `compose.yml`.

### Pitfall 8: Podman DNS and `depends_on` Ordering

**What goes wrong:** Services start and immediately fail with connection refused because the OTel Collector is not yet ready.

**Why it happens:** Podman Compose respects `depends_on` ordering but without `condition: service_healthy`, it only waits for container start, not readiness. The OTel Collector takes 2–5 seconds to initialize its pipelines.

**How to avoid:** Use `condition: service_healthy` for `otel-collector` in every service's `depends_on`. Add a healthcheck to the OTel Collector that polls its own internal metrics endpoint: `wget -qO- http://localhost:8888/metrics || exit 1`.

---

## Code Examples

### Verified OTel `application.yml` block (all four services)

```yaml
# Source: foojay.io Spring Boot 4 OTel guide [VERIFIED] + Spring Boot actuator reference
management:
  tracing:
    sampling:
      probability: 1.0
  otlp:
    metrics:
      export:
        url: ${OTEL_EXPORTER_OTLP_ENDPOINT:http://localhost:4318}/v1/metrics
  opentelemetry:
    tracing:
      export:
        otlp:
          endpoint: ${OTEL_EXPORTER_OTLP_ENDPOINT:http://localhost:4318}/v1/traces
    logging:
      export:
        otlp:
          endpoint: ${OTEL_EXPORTER_OTLP_ENDPOINT:http://localhost:4318}/v1/logs

logging:
  structured:
    format:
      console: ecs
```

### ECS JSON output fields (verified from Spring Boot docs)

```json
{
  "@timestamp": "2026-04-12T10:15:00.067Z",
  "log": { "level": "INFO", "logger": "au.com.bankforge.payment.service.PaymentService" },
  "process": { "pid": 1, "thread": { "name": "virtual-thread-1" } },
  "service": { "name": "payment-service" },
  "trace": { "id": "4bf92f3577b34da6a3ce929d0e0e4736" },
  "span": { "id": "00f067aa0ba902b7" },
  "message": "Transfer initiated",
  "transferId": "...",
  "ecs": { "version": "8.11" }
}
```

`trace.id` and `span.id` are automatically injected when OTel tracing is active — no extra configuration needed.

### compose.yml service blocks for observability stack

```yaml
# Source: OTel docs, jaegertracing hotrod example, Grafana Loki install docs — combined
  otel-collector:
    image: otel/opentelemetry-collector-contrib:0.149.0
    command: ["--config", "/etc/otelcol-contrib/config.yaml"]
    volumes:
      - ./infra/observability/otel-collector-config.yaml:/etc/otelcol-contrib/config.yaml:ro
    ports:
      - "4318:4318"    # OTLP HTTP from services (host-exposed for debugging)
    healthcheck:
      test: ["CMD-SHELL", "wget -qO- http://localhost:8888/metrics || exit 1"]
      interval: 10s
      timeout: 5s
      retries: 10
      start_period: 10s

  jaeger:
    image: jaegertracing/jaeger:2.17.0
    command: ["--config", "/etc/jaeger/config.yaml"]
    volumes:
      - ./infra/observability/jaeger-config.yaml:/etc/jaeger/config.yaml:ro
    ports:
      - "16686:16686"  # Jaeger UI
    depends_on:
      otel-collector:
        condition: service_healthy
    healthcheck:
      test: ["CMD-SHELL", "wget -qO- http://localhost:16686/api/services || exit 1"]
      interval: 10s
      timeout: 5s
      retries: 10
      start_period: 15s

  prometheus:
    image: prom/prometheus:v3.10.0
    command:
      - '--config.file=/etc/prometheus/prometheus.yml'
      - '--enable-feature=exemplar-storage'
    volumes:
      - ./infra/observability/prometheus.yml:/etc/prometheus/prometheus.yml:ro
    ports:
      - "9090:9090"
    depends_on:
      otel-collector:
        condition: service_healthy
    healthcheck:
      test: ["CMD-SHELL", "wget -qO- http://localhost:9090/-/healthy || exit 1"]
      interval: 10s
      timeout: 5s
      retries: 5

  loki:
    image: grafana/loki:3.7.0
    command: ["-config.file=/etc/loki/local-config.yaml"]
    volumes:
      - ./infra/observability/loki-config.yaml:/etc/loki/local-config.yaml:ro
    ports:
      - "3100:3100"
    healthcheck:
      test: ["CMD-SHELL", "wget -qO- http://localhost:3100/ready || exit 1"]
      interval: 10s
      timeout: 5s
      retries: 10
      start_period: 10s

  grafana:
    image: grafana/grafana:11.6.14
    environment:
      GF_AUTH_ANONYMOUS_ENABLED: "true"
      GF_AUTH_ANONYMOUS_ORG_ROLE: "Admin"
    volumes:
      - ./infra/observability/grafana/provisioning:/etc/grafana/provisioning:ro
    ports:
      - "3000:3000"
    depends_on:
      prometheus:
        condition: service_healthy
      jaeger:
        condition: service_healthy
      loki:
        condition: service_healthy
    healthcheck:
      test: ["CMD-SHELL", "wget -qO- http://localhost:3000/api/health || exit 1"]
      interval: 10s
      timeout: 5s
      retries: 5
```

---

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| Jaeger v1 `all-in-one` + `COLLECTOR_OTLP_ENABLED=true` | Jaeger v2 `jaegertracing/jaeger` + YAML config | v1 EOL 2025-12-31 | Config file required; no env var config |
| Promtail for log shipping | OTel Collector for all signals | 2024–2025 | Single pipeline; no separate log agent |
| Separate `micrometer-registry-otlp` + `micrometer-tracing-bridge-otel` + multiple OTel deps | Single `spring-boot-starter-opentelemetry` | Spring Boot 4.0 | One dependency replaces 4–6 manual OTel deps |
| Zipkin for distributed tracing | Jaeger v2 / OTLP-native backends | 2023+ | OTLP is the standard; Zipkin deprecated in Spring Boot OTel path |
| Scrape `/actuator/prometheus` directly | Scrape OTel Collector Prometheus exporter | Phase 2 design decision | Single Prometheus scrape target; Collector normalises metric names |

**Deprecated/outdated:**
- `jaegertracing/all-in-one` image: Points to v1 (EOL). Use `jaegertracing/jaeger` for v2.
- `COLLECTOR_OTLP_ENABLED=true` in Jaeger: v1 env var only — does nothing in v2.
- Promtail: Still functional but maintenance mode; Grafana Alloy is the successor (out of scope for Phase 2).
- `micrometer-tracing-bridge-otel` as standalone pom.xml dependency: Now bundled in `spring-boot-starter-opentelemetry`.

---

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | `opentelemetry-logback-appender-1.0` version `2.21.0-alpha` is compatible with Spring Boot 4.0.5's OTel SDK version | Standard Stack | If Spring Boot 4.0.5 ships a newer OTel SDK (e.g., 2.26.x), the appender version may be a minor mismatch; verify `mvn dependency:tree` for `opentelemetry-sdk` version, then pick matching appender |
| A2 | Spring Boot 4.0.5 does NOT auto-install the OTel logback appender (requires `InstallOpenTelemetryAppender`) | Pattern 2 | If SB 4 auto-installs it, the `InitializingBean` component would be harmless but redundant — test by checking if logs reach Loki without the component |
| A3 | Jaeger v2 config file format (YAML with `extensions`, `receivers`, `exporters`, `service`) is correct and stable | Pattern 5 | Jaeger v2 config uses OTel Collector schema; field names may differ from the example; validate by running `jaegertracing/jaeger:2.17.0 --help` |
| A4 | `management.opentelemetry.logging.export.otlp.endpoint` is the correct property key for OTel log export in Spring Boot 4 | Pattern 1 | Property key names changed between SB 3.4 and SB 4; if wrong, logs go to console only; verify against Spring Boot 4.0.5 autoconfigure source |
| A5 | `ContextPropagatingTaskDecorator` bean registration is sufficient for virtual thread context propagation in Spring Boot 4 | Pattern 3 | Spring Boot 4 may have changed how the decorator is registered; verify by checking traces have parent-child relationships after adding the bean |
| A6 | Prometheus v3.10 does not break OTel Collector Prometheus exporter compatibility | Standard Stack | If the Collector exposes metrics without correct Content-Type, v3 fails scrape; mitigation is to add `fallback_scrape_protocol` or pin to Prometheus v2.54 |

---

## Open Questions

1. **Exact logback appender version under Spring Boot 4.0.5 BOM**
   - What we know: `2.21.0-alpha` mentioned in WebSearch sources; Spring Boot 4 BOM does not manage this artifact
   - What's unclear: Does Spring Boot 4.0.5 ship OTel SDK 2.21.x, 2.26.x, or higher? Appender version should match SDK major+minor
   - Recommendation: In Wave 0, run `mvn dependency:tree | grep opentelemetry-sdk` in any service, then pick appender version with matching major.minor

2. **Jaeger v2 config file exact field names for `jaeger_query` extension**
   - What we know: Config structure verified from hotrod example + docs; field names `jaeger_storage`, `jaeger_query`, `jaeger_storage_exporter` are from official documentation
   - What's unclear: Whether `jaeger_query.http.endpoint` is the correct nested path or if it's `jaeger_query.ui.http.endpoint`
   - Recommendation: Run `docker run jaegertracing/jaeger:2.17.0 --help` locally to dump config schema; or use official hotrod docker-compose as ground truth

3. **`management.opentelemetry.logging.export.otlp.endpoint` property name**
   - What we know: This property path appeared in WebSearch sources and the foojay article
   - What's unclear: Spring Boot 4.0.5 might have renamed this to match OTel conventions; the `management.opentelemetry.*` namespace is newer and less documented
   - Recommendation: Test with a single service first; if logs don't reach Loki, check OTel Collector logs for incoming log records; look for Spring Boot 4 actuator log-exporter autoconfigure class name in the jar

---

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| Podman | All compose services | YES | 5.8.1 | — |
| podman-compose | `compose.yml` execution | NO | — | Use `docker compose` with `DOCKER_HOST` pointing to Podman socket (existing Phase 1 pattern) |
| Java 21 (IntelliJ JBR) | Service builds | YES (inferred from Phase 1 success) | 21.0.8 | — |
| Maven 3.9.9 (IntelliJ) | pom.xml dependency resolution | YES (inferred from Phase 1 success) | 3.9.9 | — |

**Missing dependencies with no fallback:** None.

**Missing dependencies with fallback:** `podman-compose` not found. Phase 1 used `docker compose` with Podman socket (`DOCKER_HOST=npipe:////./pipe/docker_engine`). Phase 2 follows the same pattern — no change required.

---

## Validation Architecture

### Test Framework

| Property | Value |
|----------|-------|
| Framework | Spring Boot Test (JUnit 5) — already present in all service pom.xml files |
| Config file | pom.xml surefire config with Podman socket env vars (Phase 1 pattern) |
| Quick run command | `mvn test -pl payment-service -Dtest=OtelIntegrationTest -x` |
| Full suite command | `mvn verify` (all modules) |

### Phase Requirements → Test Map

| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| OBS-01 | Services emit OTel traces to Collector | smoke (manual) | `curl http://localhost:4318/v1/traces` via Collector | No — Wave 0 |
| OBS-02 | Distributed traces visible in Jaeger | smoke (manual) | Hit Jaeger API: `curl http://localhost:16686/api/services` | No — Wave 0 |
| OBS-03 | Prometheus scrapes all services via Collector | smoke (manual) | `curl http://localhost:9090/api/v1/targets` | No — Wave 0 |
| OBS-04 | Grafana dashboard shows banking panels with data | manual verification | `curl http://localhost:3000/api/health` + browser check | No — Wave 0 |
| OBS-05 | Logs queryable in Loki by service/transferId/level | smoke (manual) | `curl http://localhost:3100/loki/api/v1/labels` | No — Wave 0 |

Phase 2 observability is inherently end-to-end — unit tests cannot verify that traces actually appear in Jaeger or logs appear in Loki. Validation is via smoke tests: POST a transfer, then query each backend for the trace/metric/log.

### Sampling Rate

- **Per task commit:** `mvn test -pl payment-service -x` (service compiles and existing unit tests pass)
- **Per wave merge:** `mvn verify` (all services build and existing tests pass)
- **Phase gate:** Full observability stack up (`podman-compose up`), end-to-end smoke test (transfer → Jaeger trace → Prometheus metric → Loki log)

### Wave 0 Gaps

- [ ] `infra/observability/otel-collector-config.yaml` — OTel Collector pipeline config (required before any service sends data)
- [ ] `infra/observability/jaeger-config.yaml` — Jaeger v2 pipeline config (required for Jaeger to start)
- [ ] `infra/observability/loki-config.yaml` — Loki single-binary config
- [ ] `infra/observability/prometheus.yml` — Prometheus scrape config
- [ ] `infra/observability/grafana/provisioning/datasources/datasources.yaml`
- [ ] `infra/observability/grafana/provisioning/dashboards/dashboards-provider.yaml`
- [ ] `infra/observability/grafana/provisioning/dashboards/banking-dashboard.json`
- [ ] `logback-spring.xml` in all four service `src/main/resources/`
- [ ] `InstallOpenTelemetryAppender.java` in all four services
- [ ] `ContextPropagationConfig.java` in all four services

---

## Security Domain

> `security_enforcement` is not explicitly set to false in config, so this section is included.

### Applicable ASVS Categories

| ASVS Category | Applies | Standard Control |
|---------------|---------|-----------------|
| V2 Authentication | No | Grafana anonymous admin enabled for local dev — acceptable |
| V3 Session Management | No | No user sessions in observability stack |
| V4 Access Control | No | All observability services on internal Compose network only |
| V5 Input Validation | No | Services don't accept observability signals from external input |
| V6 Cryptography | No | TLS not required for internal Compose networking |

### Known Threat Patterns for Observability Stack

| Pattern | STRIDE | Standard Mitigation |
|---------|--------|---------------------|
| OTel Collector accessible on host port 4318 | Spoofing | Acceptable for local dev; in Phase 3 restrict to internal Kubernetes network only |
| Grafana anonymous admin enabled | Elevation of privilege | Local dev only — `GF_AUTH_ANONYMOUS_ORG_ROLE: Admin` must be removed in Phase 3+ |
| Sensitive data in trace attributes (account IDs, amounts) | Information disclosure | Acceptable for dev; in production use OTel attribute filtering in Collector processor |

---

## Sources

### Primary (HIGH confidence)

- `jaegertracing.io/download/` — Jaeger v2.17.0 confirmed latest; v1 EOL
- `github.com/jaegertracing/jaeger` hotrod `docker-compose.yml` — Jaeger v2 image and ports
- `grafana.com/docs/loki/latest/setup/install/docker/` — Loki 3.7.0 official install
- `raw.githubusercontent.com/grafana/loki/v3.7.0/cmd/loki/loki-local-config.yaml` — Loki config structure
- `grafana.com/docs/loki/latest/send-data/otel/` — Loki OTLP endpoint `http://loki:3100/otlp`
- `grafana.com/docs/loki/latest/send-data/otel/otel-collector-getting-started/` — Collector → Loki config
- `docs.spring.io/spring-boot/reference/features/logging.html` — ECS logging properties
- `docs.spring.io/spring-boot/reference/actuator/observability.html` — `ContextPropagatingTaskDecorator`
- `foojay.io/today/spring-boot-4-opentelemetry-explained/` — Complete Spring Boot 4 OTel `application.yml`
- `danvega.dev/blog/opentelemetry-spring-boot` — `InstallOpenTelemetryAppender` pattern + logback config

### Secondary (MEDIUM confidence)

- WebSearch 2026: OTel Collector contrib latest version 0.148–0.149
- WebSearch 2026: Prometheus v3.10.0 released 2026-02-24
- WebSearch 2026: Grafana 11.6.14 latest stable March 2026
- WebSearch: Prometheus `exemplarTraceIdDestinations` + `datasourceUid` Grafana YAML pattern
- WebSearch: `opentelemetry-logback-appender-1.0:2.21.0-alpha` version reference

### Tertiary (LOW confidence)

- Assumed: Spring Boot 4.0.5 does not auto-install OTel logback appender (requires `InitializingBean` component) — flagged as A2 in Assumptions Log
- Assumed: Exact `management.opentelemetry.logging.export.otlp.endpoint` property name in SB 4.0.5 — flagged as A4

---

## Metadata

**Confidence breakdown:**
- Standard stack (images and versions): HIGH — all verified via official sites (jaegertracing.io, grafana.com, prom/prometheus) against current dates
- Spring Boot 4 OTel application.yml properties: HIGH — verified via multiple independent sources (foojay, danvega, Spring docs)
- Jaeger v2 config file format: MEDIUM — verified from official hotrod example; exact field names should be confirmed on first run
- Logback appender version: LOW — `2.21.0-alpha` sourced from WebSearch; must be cross-checked against Spring Boot 4.0.5 OTel SDK version via `mvn dependency:tree`
- Architecture patterns: HIGH — follow established OTel Collector community patterns; no novel approaches

**Research date:** 2026-04-12
**Valid until:** 2026-05-12 (Grafana, Loki, and OTel Collector release frequently; image versions should be re-verified if planning is delayed by > 30 days)
