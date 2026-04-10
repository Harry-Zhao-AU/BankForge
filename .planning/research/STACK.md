# Technology Stack Research

**Project:** BankForge — Australian Core Banking Microservices Platform
**Researched:** 2026-04-10
**Researcher note:** All external network tools (WebSearch, WebFetch, Bash/curl) were blocked in this environment. Version data comes from training knowledge (cutoff: August 2025) plus plan.md analysis. Where verification was blocked, confidence levels are explicitly stated. Priority recommendation: verify the Spring Boot 4.0 version situation before any code is written — it is the single highest-risk item in the plan.

---

## ✓ Spring Boot 4.0.x — CONFIRMED GA (verified 2026-04-10)

Spring Boot 4.0.x released November 2025. Confirmed actively supported (green) as of 2026-04-10 per spring.io lifecycle page:
- OSS support through 2026-12
- Enterprise support through 2027-12

**Use Spring Boot 4.0.x as planned. No fallback to 3.4.x needed.**

Note: Spring Boot 3.5.x (released May 2025) also exists and has longer enterprise support (until 2032). For this learning project 4.0.x is the right choice — it's what the plan specifies and it's stable.

---

## Recommended Stack

### Core Java / Spring

| Technology | Recommended Version | Plan Version | Status | Confidence |
|------------|--------------------|-----------|----|------------|
| Java | 21 LTS | 21 LTS | CONFIRMED | HIGH |
| Spring Boot | **3.4.x or 4.0.x (verify GA)** | 4.0.5 | NEEDS VERIFICATION | LOW |
| Spring Framework | 6.2.x (if SB 3.4) / 7.0.x (if SB 4.0) | (implied by SB 4.0.5) | NEEDS VERIFICATION | LOW |
| Spring Data JPA | Bundled with Spring Boot | — | OK | HIGH |
| Spring Kafka | Bundled with Spring Boot | — | OK | HIGH |
| Spring Security | Bundled with Spring Boot | — | OK | HIGH |

**Why Java 21 LTS:** Virtual threads (Project Loom) are production-ready in 21. For a banking service with high I/O (DB calls, Kafka, HTTP), virtual threads eliminate blocking thread pool exhaustion with zero code changes when enabled via `spring.threads.virtual.enabled=true`. Java 23+ exists but is not LTS — do not upgrade mid-project. Java 21 is the right choice. (Confidence: HIGH)

**Why Spring Boot over alternatives:**
- Micronaut and Quarkus both compete on cold-start time (relevant for Lambda/FaaS, not relevant for long-running K8s services)
- Spring Boot has the most mature Debezium + Outbox + Saga tooling in the Java ecosystem
- Spring Boot 4.0 / 3.4 both ship OTel auto-configuration out of the box — the plan's key "no manual TracerProvider" requirement is met by both
- Quarkus is worth knowing but switching mid-project from Spring Boot would be a significant cost with no banking-relevant benefit

**Spring Boot 4.0 vs 3.4 decision tree:**
- If 4.0.x is GA and has been out for 2+ months: use 4.0.x (Spring Framework 7, virtual threads first-class, better OTel integration)
- If 4.0.x is still RC/milestone: use 3.4.x — every feature in the plan is available there
- Do NOT use a milestone or RC for the foundation of a banking platform learning project; debugging framework bugs on top of pattern complexity is counterproductive

### Application Frameworks & Libraries

| Library | Recommended Version | Purpose | Why |
|---------|-------------------|---------|-----|
| Spring Data JPA | (bundled with SB) | ORM / repository layer | Standard; HibernateJPA integration is best-in-class |
| Spring Kafka | (bundled with SB) | Kafka consumer/producer | Native Spring idioms, @KafkaListener, error handling |
| Spring State Machine | 4.0.x | Transfer state machine | Purpose-built FSM for Spring; avoids rolling own state machine |
| Resilience4j | 2.2.x | Circuit breaker (service-to-service) | Spring Boot native integration; replaces deprecated Hystrix |
| Flyway | 10.x | DB migration | Repeatable schema migrations; critical for outbox table setup |
| MapStruct | 1.6.x | DTO ↔ entity mapping | Compile-time, zero-reflection mapping; better than ModelMapper |
| Lombok | 1.18.x | Boilerplate reduction | Use judiciously; @Builder and @Value are safe bets |
| jackson-databind | (bundled with SB) | JSON serialisation | Default; do not replace with Gson |

**Spring State Machine flag:** The plan mentions a state machine for the transfer lifecycle but does not specify the implementation. Options:
1. Spring State Machine (library) — most feature-complete, some complexity overhead
2. Hand-rolled enum + switch (simplest) — fine for 5 states, harder to audit
3. Axon Framework StateMachine — overkill; brings CQRS/ES as a package deal

Recommendation: Use Spring State Machine for this project because (a) the state diagram in plan.md is non-trivial (6 states with compensation paths) and (b) it integrates with Spring's event publishing model. (Confidence: MEDIUM — Spring State Machine project has been slower to update; verify it has a Spring Boot 4.0 compatible release if using SB 4.0.)

### Database

| Technology | Recommended Version | Plan Version | Status | Confidence |
|------------|--------------------|-----------|----|------------|
| PostgreSQL | 16 or 17 | 15 | UPDATE RECOMMENDED | MEDIUM |
| Redis | 7.2.x | 7 | OK (pin to 7.2) | HIGH |
| Neo4j | 5.x Community | 5 | OK | HIGH |

**PostgreSQL 15 vs 16/17:**
- PostgreSQL 15 is still receiving security updates but entered "minor releases only" mode in Q4 2024
- PostgreSQL 16 added parallel query improvements and logical replication improvements (relevant for Debezium)
- PostgreSQL 17 (released September 2024) is the current major version with further logical replication refinements
- **Recommendation: Use PostgreSQL 16 or 17.** The Debezium CDC connector benefits from PostgreSQL 16+'s improved logical replication slot stability. Using 15 is not wrong but misses small Debezium reliability improvements.
- The outbox table design in plan.md is compatible with all three versions.

**Redis 7.2:** Pin to 7.2.x (not just "Redis 7") to ensure reproducibility. Redis 7.2 added LMPOP and OBJECT FREQ improvements. More importantly, the Jedis / Lettuce clients bundled in Spring Boot 3.4+ are verified against 7.2. (Confidence: HIGH)

**Neo4j 5 Community:** Correct choice. Neo4j 5.x is the current major version. Community edition is appropriate for a learning project; it lacks clustering and some enterprise features but has full APOC and Bloom. Neo4j 4.x reached EOL — do not use it. (Confidence: HIGH)

**Neo4j Bloom flag:** Bloom was bundled with Neo4j Desktop but requires a license for standalone server use. For a local kind deployment, Neo4j Browser (included free) provides Cypher querying. Neo4j Bloom requires either Neo4j Desktop or an Aura account. Verify whether Bloom is accessible in the Community Server container image — it may not be. Fallback: Neo4j Browser is sufficient for this project's RCA goals.

### Messaging & Event Streaming

| Technology | Recommended Version | Plan Version | Status | Confidence |
|------------|--------------------|-----------|----|------------|
| Apache Kafka | 3.8.x or 3.9.x | unspecified | SPECIFY VERSION | HIGH |
| Confluent Schema Registry | 7.7.x | (not in plan) | CONSIDER ADDING | MEDIUM |
| Debezium | 3.0.x | unspecified | SPECIFY VERSION | MEDIUM |
| Kafka UI (Provectus) | latest | (not in plan) | RECOMMEND ADDING | LOW |

**Kafka 3.x KRaft mode:** Kafka 3.3+ made KRaft (Kafka without ZooKeeper) production-ready. Kafka 3.8+ fully removes ZooKeeper support in the default distribution. The plan does not specify a Kafka version or mention ZooKeeper. **Use KRaft mode from the start** — eliminates ZooKeeper as a dependency, simplifies the Compose setup, and reflects current (2025) Kafka deployment practice. Do NOT use a ZooKeeper-based Kafka setup for a new project in 2026. (Confidence: HIGH)

**Debezium 3.0.x:** Debezium 3.0 was released in late 2024 / early 2025. Key changes relevant to this project:
- Native KRaft support (important given the KRaft recommendation above)
- Improved PostgreSQL logical replication slot management
- Debezium Server (standalone JAR) vs Kafka Connect deployment — for a local kind cluster, Debezium running inside Kafka Connect (as a connector plugin) is the standard pattern and is what the plan's `connector.json` implies
- The outbox routing SMT (Single Message Transform) `io.debezium.transforms.outbox.EventRouter` is what converts the outbox table rows into domain events on correct Kafka topics — ensure this is configured in `connector.json`

**Schema Registry decision:** The plan does not include Schema Registry. For a learning project, you can omit it and use plain JSON. However, the Outbox pattern with JSON payloads risks silent schema drift. Consider adding Schema Registry (Confluent or Apicurio) in Phase 2 alongside observability. This is MEDIUM confidence because it adds operational complexity that may not be worth it for a local-only project.

**Kafka UI:** Add `provectuslabs/kafka-ui` to the Compose file for Phase 1. It provides a web UI to inspect topics, consumer groups, and lag — invaluable for debugging the Outbox/CDC pipeline. Zero configuration beyond pointing it at the Kafka broker. (Confidence: HIGH as a practical recommendation)

### Observability Stack

| Technology | Recommended Version | Plan Version | Status | Confidence |
|------------|--------------------|-----------|----|------------|
| OpenTelemetry (Java agent or SDK) | 1.x (bundled in SB 4) | "built-in" | OK — verify auto-config | MEDIUM |
| OTel Collector | 0.100.x+ | (implied) | ADD TO PLAN | HIGH |
| Jaeger | 1.55+ (v2 architecture) | unspecified | NEEDS VERSION | MEDIUM |
| Prometheus | 2.54.x or 3.0.x | unspecified | SPECIFY VERSION | MEDIUM |
| Loki | 3.x | unspecified | SPECIFY VERSION | MEDIUM |
| Promtail | 3.x | unspecified | SPECIFY VERSION | MEDIUM |
| Grafana | 11.x | unspecified | SPECIFY VERSION | MEDIUM |
| Grafana Alloy | 1.x | NOT IN PLAN | CONSIDER REPLACING PROMTAIL | LOW |

**OTel Collector is mandatory — not optional:** The plan's OTel config shows `http://otel-collector:4317` as the endpoint, which implies a collector is in the stack. However, it does not appear in the tech stack table or Compose file. The OTel Collector must be explicitly included. It decouples your services from backend changes (you can swap Jaeger for Tempo without touching service config), and it handles tail-based sampling — important when you want to keep only traces where something went wrong.

**Jaeger v2 architecture:** Jaeger 1.55+ migrated to a unified binary (`jaeger`) replacing the old `jaeger-collector` + `jaeger-query` + `jaeger-agent` split. For new projects in 2026, use the `jaegertracing/jaeger` all-in-one image with the new architecture. The old multi-component deployment is the outdated pattern. (Confidence: MEDIUM — based on Jaeger's public roadmap through mid-2025; verify image names before Phase 2.)

**Promtail vs Grafana Alloy:** Promtail is in maintenance mode. Grafana announced in 2024 that Alloy is the successor to both Promtail and Agent. For a greenfield project, consider using Grafana Alloy for log collection instead of Promtail. However, Promtail still works and has more documentation examples. Since this is a learning project where documentation matters, Promtail is acceptable — just do not plan to use Promtail in a production context past 2026. (Confidence: MEDIUM)

**Prometheus 3.0:** Prometheus 3.0 was released in late 2024 and introduces a new query engine with significant performance improvements and UTF-8 metric names. For a learning project, use either 2.54.x (stable, proven) or 3.0.x. The PromQL syntax used in the plan's MCP tools is compatible with both. (Confidence: MEDIUM)

**Spring Boot OTel auto-configuration:** Spring Boot 3.2+ added `spring-boot-actuator` auto-configuration for OTel. Spring Boot 4.0 extends this further. The plan's `application.yml` snippet is structurally correct for Spring Boot 4 — verify the exact property key `management.otlp.tracing.endpoint` against the actual Spring Boot version in use, as property names changed between 3.2 and 3.4. (Confidence: MEDIUM)

### Service Mesh & API Gateway

| Technology | Recommended Version | Plan Version | Status | Confidence |
|------------|--------------------|-----------|----|------------|
| Istio | 1.23.x or 1.24.x | unspecified | SPECIFY VERSION | MEDIUM |
| Kong Gateway | 3.7.x or 3.8.x | unspecified | SPECIFY VERSION | MEDIUM |
| Keycloak | 25.x or 26.x | unspecified | SPECIFY VERSION | MEDIUM |
| Kiali | 2.x | unspecified | SPECIFY VERSION | LOW |

**Istio version and kind compatibility:** Istio releases roughly every 3 months. As of mid-2025, Istio 1.22 and 1.23 were current. Each Istio version has specific Kubernetes version support matrix — verify that your kind cluster's Kubernetes version is within the support matrix for the Istio version chosen. A common pitfall: kind defaults to the latest K8s patch but Istio's support matrix lags by 1-2 minor versions. (Confidence: MEDIUM)

**Istio sidecar vs ambient mode:** Istio 1.21+ made "ambient mode" (ztunnel-based, no sidecar) production-ready. For this project, stick with **sidecar mode** — it is better documented, Kiali has fuller support for it, and the plan's VirtualService / DestinationRule examples all assume sidecar mode. Ambient mode is the future but sidecar is the safer learning path. (Confidence: HIGH as a recommendation)

**Kong Gateway deployment on K8s:** The plan uses Kong as an ingress controller. Two deployment patterns exist:
1. Kong Ingress Controller (KIC) — uses Kubernetes Ingress resources + Kong plugins; recommended
2. Kong Gateway Operator — newer, uses GatewayAPI; not yet universally adopted

Recommend Kong Ingress Controller (KIC) 3.x with the `konghq/kong` image. The plan's `ingress.yaml` and `plugins.yaml` structure aligns with KIC. (Confidence: MEDIUM)

**Keycloak 25/26:** Keycloak switched to Quarkus-based distribution in version 17. Anything below 17 is the old WildFly-based version — do not use it. The plan uses OAuth2 + OIDC + JWT which is fully supported. The `realm.json` export/import approach in the plan is correct. Keycloak's Admin REST API is stable and the `realm.json` bootstrap pattern is standard. (Confidence: HIGH)

**Kong JWT plugin vs OIDC plugin:** The plan uses the built-in `jwt` plugin, which validates JWT signatures locally (requires key pre-loading). For Keycloak integration, consider the `openid-connect` plugin (part of Kong Enterprise) or `jwt-keycloak` community plugin, which introspects against Keycloak dynamically. The basic `jwt` plugin requires synchronising Keycloak's public key to Kong — adds operational complexity. For a learning project, the `jwt` plugin is fine. For production: use OIDC introspection. (Confidence: HIGH)

### Infrastructure & Containers

| Technology | Recommended Version | Plan Version | Status | Confidence |
|------------|--------------------|-----------|----|------------|
| Podman | 4.x or 5.x | "Podman" (unversioned) | OK — pin version | MEDIUM |
| kind | 0.24.x or latest | unspecified | SPECIFY VERSION | MEDIUM |
| Podman Compose | 1.x | (implied) | FLAG: use compose.yml | LOW |

**Podman vs Docker for kind:** kind (Kubernetes IN Docker) was built for Docker but supports Podman with caveats:
- `KIND_EXPERIMENTAL_PROVIDER=podman` environment variable is required
- Rootless Podman has known issues with kind networking (specifically: container-to-container DNS and multi-node clusters)
- The plan targets a local dev environment only (single-node kind) — this is achievable with Podman but expect networking quirks
- If Podman rootless causes pain with kind, the fallback is rootful Podman (which works more reliably with kind)

**This is a HIGH RISK item.** Podman rootless + kind + Istio is a combination with known friction. Document the workaround steps before Phase 3 begins. Consider testing kind connectivity early rather than discovering issues when adding Istio. (Confidence: HIGH on the risk; MEDIUM on the specific workarounds)

**Podman Compose vs Docker Compose compatibility:** Podman 4.x introduced native `podman compose` (wrapping the Docker Compose v2 spec). The `compose.yml` filename (not `docker-compose.yml`) is the current convention. Most Docker Compose v2 syntax works with Podman Compose, but some features (profiles, depends_on healthcheck conditions) have partial support. Test Phase 1 compose early. (Confidence: MEDIUM)

### AI Agent / MCP Integration

| Technology | Recommended Version | Plan Version | Status | Confidence |
|------------|--------------------|-----------|----|------------|
| Python | 3.12 or 3.13 | (unversified) | SPECIFY | MEDIUM |
| anthropic-mcp (Python SDK) | latest (0.9.x+) | "Python MCP server" | VERIFY SDK NAME | MEDIUM |
| httpx | 0.27.x | (implied) | Use over requests | MEDIUM |
| neo4j Python driver | 5.x | (implied) | OK | HIGH |
| prometheus-api-client | 0.5.x | (implied) | Consider alternatives | LOW |
| opentelemetry-sdk (Python) | 1.26.x+ | (not in plan) | ADD FOR MCP TRACING | LOW |

**MCP SDK verification required:** The MCP specification (Model Context Protocol) was open-sourced by Anthropic in late 2024. The Python SDK (`mcp` package on PyPI) has been evolving rapidly. The plan references a "Python MCP server" but does not specify the SDK name or version. Before Phase 5, verify:
- Package name: likely `mcp` on PyPI (not `anthropic-mcp`)
- Current version: was 0.9.x-1.0.x range as of mid-2025
- Transport: stdio (for Claude Desktop) vs SSE/HTTP (for remote agents) — the plan uses Claude Desktop integration, so stdio transport is correct

(Confidence: MEDIUM — MCP SDK is young and API surface was still evolving at my knowledge cutoff)

**MCP tool list review:** The plan lists `place_order()` as an MCP tool — this does not match the banking domain (no orders). This appears to be copy-paste from a different project. The actual tools listed (check_balance, track_transfer, find_slow_services, root_cause_analysis, query_service_graph, query_metrics, get_jaeger_trace) are correct. Remove `place_order()`. (Confidence: HIGH — this is a straightforward plan.md inconsistency)

**Python for MCP vs Java:** The plan justifies Python MCP server by "lighter footprint" and "mature Python MCP SDK." Both points are correct. Additionally, the AI tooling ecosystem (LangChain, LlamaIndex, future RCA tooling) is predominantly Python-first. The MCP server does not need to be high performance — it orchestrates calls to other systems. Python is the right choice. (Confidence: HIGH)

**Prometheus querying from Python:** The `prometheus-api-client` library is a thin wrapper around the Prometheus HTTP API. It works but is lightly maintained. Alternative: query Prometheus directly via `httpx` using the `/api/v1/query` endpoint — more transparent and no extra dependency. For this project, either approach is fine. (Confidence: MEDIUM)

---

## Alternatives Considered

| Category | Recommended | Alternative Considered | Why Not |
|----------|------------|----------------------|---------|
| Java framework | Spring Boot | Quarkus | Cold-start advantage irrelevant for long-running K8s pods; Spring has better Debezium/Outbox ecosystem |
| Java framework | Spring Boot | Micronaut | Same reasoning as Quarkus; smaller community, fewer examples for banking patterns |
| Messaging | Kafka (KRaft) | Kafka + ZooKeeper | ZooKeeper support removed in Kafka 3.8+; adds operational overhead for no benefit |
| Messaging | Kafka | RabbitMQ | AMQP is fine for pub/sub but Kafka's log-based retention is essential for CDC/Outbox replay |
| Messaging | Kafka | AWS EventBridge | Cloud-specific; violates local-only constraint |
| Log aggregation | Loki | Elasticsearch | Elasticsearch is operationally expensive (JVM heap, index management); Loki is label-based and cheaper for structured logs |
| Log shipping | Promtail (or Alloy) | Filebeat | Grafana ecosystem consistency; Filebeat targets Elastic stack |
| Tracing | Jaeger | Grafana Tempo | Both are valid; Jaeger is more standalone-documented; Tempo requires Grafana as query frontend |
| Service mesh | Istio | Linkerd | Linkerd is lighter but has fewer features (no traffic management DSL); Istio's VirtualService model is what the plan needs |
| Service mesh | Istio | Cilium | Cilium is eBPF-based, excellent for production but complex; Istio has better learning resources |
| Graph DB | Neo4j | JanusGraph | Neo4j has far better tooling (Browser, Bloom, APOC); JanusGraph is for scale, not learning |
| Graph DB | Neo4j | TigerGraph | Proprietary; licensing cost; no clear advantage for this use case |
| API gateway | Kong | Nginx + Lua | More operational complexity, less feature-rich gateway semantics |
| API gateway | Kong | Traefik | Traefik lacks Kong's plugin ecosystem; JWT + rate limiting require plugins |
| Auth | Keycloak | Auth0 | Cloud SaaS; violates local-only constraint |
| Auth | Keycloak | Dex | Dex is simpler but lacks Keycloak's banking-relevant features (realm isolation, session management) |
| Containers | Podman | Docker Desktop | Docker Desktop requires license for commercial use; Podman rootless aligns with APRA-style security posture |
| K8s local | kind | minikube | Both work; kind is faster to start, uses less memory, and is the de-facto standard for CI testing |
| K8s local | kind | k3d | k3d (k3s) is lighter but uses a different distribution that may behave differently from production EKS/GKE |
| State machine | Spring State Machine | Axon Framework | Axon brings full CQRS/ES as a package deal; far more than needed for 6-state FSM |

---

## Version Pinning Recommendations

For each major component, pin to a specific version in your Compose and K8s manifests — not `latest`. This is especially important for Kafka, Debezium, Istio, and Keycloak where minor version differences can break the connector protocol.

```yaml
# compose.yml image pinning examples (verify actual latest before writing)
postgres: postgres:17-alpine
redis: redis:7.2-alpine
kafka: apache/kafka:3.8.0          # KRaft mode, no ZooKeeper
debezium: debezium/connect:3.0.0   # verify with debezium.io/releases
neo4j: neo4j:5.22-community
jaeger: jaegertracing/jaeger:1.55  # unified binary, verify exact version
prometheus: prom/prometheus:v2.54.0
loki: grafana/loki:3.0.0
promtail: grafana/promtail:3.0.0
grafana: grafana/grafana:11.0.0
keycloak: quay.io/keycloak/keycloak:26.0
```

**ALL versions above need verification against current release pages before use.** These are directional estimates from training data.

---

## Plan.md Flags — Issues Requiring Action

### FLAG 1 (CRITICAL): Spring Boot 4.0.5 — Verify GA status
- **Risk:** Building on an RC/milestone Spring Boot would mean debugging framework bugs alongside pattern complexity
- **Action:** Check https://spring.io/projects/spring-boot before writing a single line of code
- **Fallback:** Spring Boot 3.4.x — all plan features are available (virtual threads, OTel, structured logging, ECS format)

### FLAG 2 (CRITICAL): Podman rootless + kind compatibility
- **Risk:** Podman rootless has known networking issues with kind (CNI plugins, DNS, port forwarding)
- **Action:** Prototype kind cluster startup with Podman in Week 1 before any service development
- **Fallback:** Use rootful Podman OR use minikube with Podman driver (better tested combination)

### FLAG 3 (HIGH): Kafka version and ZooKeeper not specified
- **Risk:** Defaulting to an older Kafka image that requires ZooKeeper adds unnecessary complexity
- **Action:** Explicitly use KRaft-mode Kafka (3.7+). Set `KAFKA_PROCESS_ROLES=broker,controller` in compose.yml
- **Impact:** The compose.yml ZooKeeper removal saves one service and eliminates a class of startup ordering bugs

### FLAG 4 (HIGH): `place_order()` in MCP tool list
- **Risk:** Not a real issue but suggests the MCP tool list was copied from a non-banking project
- **Action:** Remove `place_order()`, add banking-relevant tools: `list_accounts()`, `get_transfer_history()`, `get_audit_trail()`

### FLAG 5 (MEDIUM): Neo4j Bloom licensing
- **Risk:** Neo4j Bloom is not freely available in the Community Server container image
- **Action:** Use Neo4j Browser (built-in, free) for all cypher query UIs; revisit Bloom only if using Neo4j Desktop locally
- **Impact:** Zero functionality loss — Browser supports all Cypher the plan needs

### FLAG 6 (MEDIUM): OTel Collector not in tech stack table
- **Risk:** The plan's `application.yml` sends OTel to `otel-collector:4317` but the collector is never mentioned as a component to deploy
- **Action:** Add `otelcol/opentelemetry-collector-contrib` to the compose.yml and K8s manifests
- **Config:** Collector receives on 4317 (gRPC) / 4318 (HTTP), exports to Jaeger on 14250

### FLAG 7 (MEDIUM): Promtail is in maintenance mode
- **Risk:** Not an immediate problem but Grafana Alloy is the successor
- **Action:** Accept Promtail for this project; note that production systems should migrate to Alloy
- **No immediate action required**

### FLAG 8 (MEDIUM): Kong JWT plugin vs Keycloak dynamic key sync
- **Risk:** The built-in `jwt` plugin requires Keycloak's public key to be pre-loaded into Kong's config; if Keycloak rotates keys, Kong breaks
- **Action:** For Phase 1-2 local dev, the `jwt` plugin is acceptable. For Phase 3+, consider adding the `openid-connect` community plugin or manually managing key rotation
- **Mitigation:** Set long key rotation intervals in Keycloak for local dev

### FLAG 9 (LOW): PostgreSQL version
- **Risk:** Plan specifies PostgreSQL 15; 16 and 17 have Debezium-relevant improvements
- **Action:** Upgrade to PostgreSQL 16 or 17; no code changes required, only the image version

### FLAG 10 (LOW): Spring State Machine not in plan
- **Risk:** The plan shows a 6-state transfer FSM but does not specify the implementation library
- **Action:** Decide before Phase 1 coding: Spring State Machine library vs hand-rolled enum FSM
- **Recommendation:** Spring State Machine for a project of this complexity

---

## Installation Reference

```xml
<!-- pom.xml — parent (verify Spring Boot version before using 4.0.x) -->
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.4.4</version> <!-- or 4.0.x if GA confirmed -->
</parent>

<!-- Core dependencies -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-jpa</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.kafka</groupId>
    <artifactId>spring-kafka</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.statemachine</groupId>
    <artifactId>spring-statemachine-core</artifactId>
    <version>4.0.0</version> <!-- verify compatibility with chosen SB version -->
</dependency>

<!-- OTel — Spring Boot 3.2+ auto-configures this when actuator is present -->
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-tracing-bridge-otel</artifactId>
</dependency>
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-exporter-otlp</artifactId>
</dependency>

<!-- Database -->
<dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
</dependency>
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-core</artifactId>
</dependency>

<!-- Redis -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>

<!-- Resilience4j -->
<dependency>
    <groupId>io.github.resilience4j</groupId>
    <artifactId>resilience4j-spring-boot3</artifactId> <!-- or spring-boot3 vs spring-boot for SB 4 -->
    <version>2.2.0</version>
</dependency>
```

```python
# requirements.txt — MCP server
mcp>=1.0.0                          # verify package name and version on PyPI
httpx>=0.27.0
neo4j>=5.0.0
python-jose[cryptography]>=3.3.0   # JWT handling
pydantic>=2.0.0
```

---

## Sources

**Note:** All external verification was blocked (WebSearch, WebFetch, curl/Bash all denied). The following sources should be consulted manually to verify versions before implementation:

| Source | What to Verify |
|--------|----------------|
| https://spring.io/projects/spring-boot | Spring Boot 4.0.x GA status and exact current version |
| https://github.com/spring-projects/spring-boot/releases | Release history and changelog |
| https://kafka.apache.org/downloads | Current Kafka stable version; KRaft setup docs |
| https://debezium.io/releases/ | Current Debezium stable version |
| https://istio.io/latest/docs/releases/supported-releases/ | Istio version / K8s compatibility matrix |
| https://konghq.com/install/ | Current Kong Gateway version |
| https://www.keycloak.org/downloads | Current Keycloak version |
| https://github.com/modelcontextprotocol/python-sdk | MCP Python SDK current version and API |
| https://pypi.org/project/mcp/ | MCP package on PyPI |
| https://neo4j.com/download-center/ | Neo4j 5.x community image tag |
| https://grafana.com/grafana/download | Grafana, Loki, Promtail/Alloy current versions |

**Confidence summary:** Version numbers in this document are directional estimates from training data (cutoff August 2025). Treat them as "approximately right" starting points, not production-safe specifications. All version numbers MUST be verified before Phase 1 begins.
