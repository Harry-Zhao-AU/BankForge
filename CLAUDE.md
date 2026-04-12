<!-- GSD:project-start source:PROJECT.md -->
## Project

**BankForge**

A realistic Australian core banking platform demonstrating enterprise microservices patterns — 4 Java/Spring Boot services with local ACID transactions, Saga/Outbox/CDC messaging, full observability (Jaeger + Prometheus + Loki + Grafana), an Istio service mesh on Kubernetes, and a Claude MCP server that queries the live system. Built as a learning sandbox and long-term foundation for an AI-driven root cause analysis system.

**Core Value:** A running, end-to-end system where every enterprise pattern (ACID, Saga, Outbox, mTLS, distributed tracing) is implemented and queryable via AI agent — proving the patterns work together, not just in theory.

### Constraints

- **Tech stack:** Java 21 LTS + Spring Boot 4.0.5 — locked for virtual threads and built-in OTel
- **Container runtime:** Podman (daemonless, rootless) — not Docker
- **Local only:** kind cluster, no cloud provider dependencies
- **Database isolation:** Each service owns its own PostgreSQL schema (no shared DB)
<!-- GSD:project-end -->

<!-- GSD:stack-start source:research/STACK.md -->
## Technology Stack

## CRITICAL FLAG: Spring Boot 4.0.5 Version Risk
- Spring Boot 3.3.x / 3.4.x were the stable production releases
- Spring Boot 4.0 was announced targeting Q4 2025 / Q1 2026, requiring Spring Framework 7.0
- Spring Framework 7.0 was itself in milestone phase as of mid-2025
- A specific patch version like `4.0.5` suggests months of post-release stabilisation that may not have happened yet
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
- Micronaut and Quarkus both compete on cold-start time (relevant for Lambda/FaaS, not relevant for long-running K8s services)
- Spring Boot has the most mature Debezium + Outbox + Saga tooling in the Java ecosystem
- Spring Boot 4.0 / 3.4 both ship OTel auto-configuration out of the box — the plan's key "no manual TracerProvider" requirement is met by both
- Quarkus is worth knowing but switching mid-project from Spring Boot would be a significant cost with no banking-relevant benefit
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
### Database
| Technology | Recommended Version | Plan Version | Status | Confidence |
|------------|--------------------|-----------|----|------------|
| PostgreSQL | 16 or 17 | 15 | UPDATE RECOMMENDED | MEDIUM |
| Redis | 7.2.x | 7 | OK (pin to 7.2) | HIGH |
| Neo4j | 5.x Community | 5 | OK | HIGH |
- PostgreSQL 15 is still receiving security updates but entered "minor releases only" mode in Q4 2024
- PostgreSQL 16 added parallel query improvements and logical replication improvements (relevant for Debezium)
- PostgreSQL 17 (released September 2024) is the current major version with further logical replication refinements
- **Recommendation: Use PostgreSQL 16 or 17.** The Debezium CDC connector benefits from PostgreSQL 16+'s improved logical replication slot stability. Using 15 is not wrong but misses small Debezium reliability improvements.
- The outbox table design in plan.md is compatible with all three versions.
### Messaging & Event Streaming
| Technology | Recommended Version | Plan Version | Status | Confidence |
|------------|--------------------|-----------|----|------------|
| Apache Kafka | 3.8.x or 3.9.x | unspecified | SPECIFY VERSION | HIGH |
| Confluent Schema Registry | 7.7.x | (not in plan) | CONSIDER ADDING | MEDIUM |
| Debezium | 3.0.x | unspecified | SPECIFY VERSION | MEDIUM |
| Kafka UI (Provectus) | latest | (not in plan) | RECOMMEND ADDING | LOW |
- Native KRaft support (important given the KRaft recommendation above)
- Improved PostgreSQL logical replication slot management
- Debezium Server (standalone JAR) vs Kafka Connect deployment — for a local kind cluster, Debezium running inside Kafka Connect (as a connector plugin) is the standard pattern and is what the plan's `connector.json` implies
- The outbox routing SMT (Single Message Transform) `io.debezium.transforms.outbox.EventRouter` is what converts the outbox table rows into domain events on correct Kafka topics — ensure this is configured in `connector.json`
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
### Service Mesh & API Gateway
| Technology | Recommended Version | Plan Version | Status | Confidence |
|------------|--------------------|-----------|----|------------|
| Istio | 1.23.x or 1.24.x | unspecified | SPECIFY VERSION | MEDIUM |
| Kong Gateway | 3.7.x or 3.8.x | unspecified | SPECIFY VERSION | MEDIUM |
| Keycloak | 25.x or 26.x | unspecified | SPECIFY VERSION | MEDIUM |
| Kiali | 2.x | unspecified | SPECIFY VERSION | LOW |
### Infrastructure & Containers
| Technology | Recommended Version | Plan Version | Status | Confidence |
|------------|--------------------|-----------|----|------------|
| Podman | 4.x or 5.x | "Podman" (unversioned) | OK — pin version | MEDIUM |
| kind | 0.24.x or latest | unspecified | SPECIFY VERSION | MEDIUM |
| Podman Compose | 1.x | (implied) | FLAG: use compose.yml | LOW |
- `KIND_EXPERIMENTAL_PROVIDER=podman` environment variable is required
- Rootless Podman has known issues with kind networking (specifically: container-to-container DNS and multi-node clusters)
- The plan targets a local dev environment only (single-node kind) — this is achievable with Podman but expect networking quirks
- If Podman rootless causes pain with kind, the fallback is rootful Podman (which works more reliably with kind)
### AI Agent / MCP Integration
| Technology | Recommended Version | Plan Version | Status | Confidence |
|------------|--------------------|-----------|----|------------|
| Python | 3.12 or 3.13 | (unversified) | SPECIFY | MEDIUM |
| anthropic-mcp (Python SDK) | latest (0.9.x+) | "Python MCP server" | VERIFY SDK NAME | MEDIUM |
| httpx | 0.27.x | (implied) | Use over requests | MEDIUM |
| neo4j Python driver | 5.x | (implied) | OK | HIGH |
| prometheus-api-client | 0.5.x | (implied) | Consider alternatives | LOW |
| opentelemetry-sdk (Python) | 1.26.x+ | (not in plan) | ADD FOR MCP TRACING | LOW |
- Package name: likely `mcp` on PyPI (not `anthropic-mcp`)
- Current version: was 0.9.x-1.0.x range as of mid-2025
- Transport: stdio (for Claude Desktop) vs SSE/HTTP (for remote agents) — the plan uses Claude Desktop integration, so stdio transport is correct
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
## Version Pinning Recommendations
# compose.yml image pinning examples (verify actual latest before writing)
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
## Installation Reference
# requirements.txt — MCP server
## Sources
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
<!-- GSD:stack-end -->

<!-- GSD:conventions-start source:CONVENTIONS.md -->
## Conventions

### Container / Build Commands

- **Always use `podman` and `podman compose`** — never `docker` or `docker-compose`. Docker Desktop is not installed; the container runtime is Podman (rootful, WSL2 backend).
- **Maven skip flag for CI builds:** Use `-Dmaven.test.skip=true` to skip both test compilation and execution. `-DskipTests` only skips execution — tests still compile, which fails if test dependencies are missing.
- **JAR file lock recovery:** If `mvn clean` fails with "file used by another process", run `podman machine stop`, delete the locked `target/` directory, then `podman machine start` before rebuilding. The Podman WSL2 machine holds file handles on mounted JARs even after `podman compose down`.
- **Maven binary:** Use IntelliJ bundled Maven at `"C:/Program Files/JetBrains/IntelliJ IDEA Community Edition 2025.2.4/plugins/maven/lib/maven3/bin/mvn"` with `JAVA_HOME="C:/Program Files/JetBrains/IntelliJ IDEA Community Edition 2025.2.4/jbr"`.
<!-- GSD:conventions-end -->

<!-- GSD:architecture-start source:ARCHITECTURE.md -->
## Architecture

Architecture not yet mapped. Follow existing patterns found in the codebase.
<!-- GSD:architecture-end -->

<!-- GSD:skills-start source:skills/ -->
## Project Skills

No project skills found. Add skills to any of: `.claude/skills/`, `.agents/skills/`, `.cursor/skills/`, or `.github/skills/` with a `SKILL.md` index file.
<!-- GSD:skills-end -->

<!-- GSD:workflow-start source:GSD defaults -->
## GSD Workflow Enforcement

Before using Edit, Write, or other file-changing tools, start work through a GSD command so planning artifacts and execution context stay in sync.

Use these entry points:
- `/gsd-quick` for small fixes, doc updates, and ad-hoc tasks
- `/gsd-debug` for investigation and bug fixing
- `/gsd-execute-phase` for planned phase work

Do not make direct repo edits outside a GSD workflow unless the user explicitly asks to bypass it.
<!-- GSD:workflow-end -->



<!-- GSD:profile-start -->
## Developer Profile

> Profile not yet configured. Run `/gsd-profile-user` to generate your developer profile.
> This section is managed by `generate-claude-profile` -- do not edit manually.
<!-- GSD:profile-end -->
