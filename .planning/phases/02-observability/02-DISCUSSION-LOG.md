# Phase 2: Observability — Discussion Log

**Session:** 2026-04-12
**Areas covered:** OTel instrumentation model, Metrics pipeline, Log shipping, Grafana dashboard scope

---

## Area 1: OTel Instrumentation Model

**Q: How should services emit OTel signals (traces, metrics)?**
Options: Spring Boot auto-config / OTel Java agent JAR / Hybrid
→ **Selected: Spring Boot auto-config** (micrometer-tracing-bridge-otel + opentelemetry-exporter-otlp)

**Q: All services get identical OTel config, or per-service customisation?**
Options: Identical via shared application.yml base / Per-service env var overrides
→ **Selected: Identical via shared application.yml base**

**Q: What sampling rate for traces?**
Options: 100% / 10%
→ **Selected: 100%**

**Q: OTel Collector transport from services: gRPC (4317) or HTTP (4318)?**
Options: HTTP/4318 / gRPC/4317
→ **Selected: HTTP/protobuf on 4318**

---

## Area 2: Metrics Pipeline

**Q: How should Prometheus get metrics from the services?**
Options: OTLP push via OTel Collector / Direct scrape / Both
→ **Selected: OTLP push → OTel Collector → Prometheus scrapes collector**

**Q: Should services emit custom banking metrics?**
Options: Custom banking metrics / Spring Boot defaults only
→ **Selected: Custom banking metrics**

**Q: Which services should emit custom banking metrics?**
Options: payment-service only / All 4 services / payment-service + ledger-service
→ **Selected: payment-service only**

---

## Area 3: Log Shipping in Podman Compose

**Background discussion:** User asked what Promtail is → explanation of log shipper role vs Logback.
User asked if there's a better choice than Promtail → Grafana Alloy introduced as modern successor.
User asked why not use one collector for everything → OTel Collector fan-out architecture presented.
User requested research before deciding → research agent confirmed Spring Boot 4 supports OTLP logs
with caveats (manual appender install, virtual threads context loss).

**Q: How should logs be collected?**
Options: OTel Collector for everything / OTel Collector + Alloy / Volume-mounted files + Promtail
→ **Selected: OTel Collector for everything**
→ Note: OBS-05 requirement updated — "collected by OTel Collector" replaces "collected by Promtail"

---

## Area 4: Grafana Dashboard Scope

**Q: How should the Grafana banking dashboard be created?**
Options: Custom JSON provisioned via compose.yml / Import Grafana Labs templates / Build interactively
→ **Selected: Custom JSON provisioned via compose.yml**

**Q: What panels must be in the Phase 2 banking dashboard?**
Options (multi-select): Transfer volume / Transfer latency p99 / Error rate / JVM panels
→ **Selected: Transfer volume + Transfer latency p99 + Error rate** (JVM panels deferred)

**Q: Should the dashboard link traces — click a spike and jump to Jaeger?**
Options: Yes (Jaeger data source + exemplars) / No
→ **Selected: Yes — Jaeger data source + exemplars**

---

*Log generated: 2026-04-12*
