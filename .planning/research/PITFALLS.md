# Domain Pitfalls: BankForge Core Banking Microservices

**Domain:** Banking microservices — Java 21 + Spring Boot 4 + Kafka + Debezium + Kubernetes + Istio + Kong + Keycloak + Neo4j + Python MCP
**Researched:** 2026-04-10
**Confidence note:** WebSearch and WebFetch were unavailable. All findings are from training data (knowledge cutoff August 2025), cross-validated against known official documentation behaviour, GitHub issue patterns, and production post-mortems known at training time. Confidence levels reflect this limitation.

---

## Layer Index

1. [Local ACID Transactions](#1-local-acid-transactions)
2. [Outbox Pattern + Debezium CDC](#2-outbox-pattern--debezium-cdc)
3. [Saga Pattern + Kafka](#3-saga-pattern--kafka)
4. [Kubernetes + Istio Service Mesh](#4-kubernetes--istio-service-mesh)
5. [Kong + Keycloak JWT Auth Chain](#5-kong--keycloak-jwt-auth-chain)
6. [Neo4j ETL from Prometheus](#6-neo4j-etl-from-prometheus)
7. [Python MCP Server for AI Agents](#7-python-mcp-server-for-ai-agents)
8. [Java 21 Virtual Threads + Spring Boot 4](#8-java-21-virtual-threads--spring-boot-4)
9. [Cross-Cutting: Podman on Windows](#9-cross-cutting-podman-on-windows)
10. [Phase-Specific Warning Matrix](#10-phase-specific-warning-matrix)

---

## 1. Local ACID Transactions

### Pitfall 1.1 — Mixing ACID scope with Saga scope (Critical)

**What goes wrong:** The transfer is designed as local ACID (debit + credit + outbox in one TX), but developers add a Kafka publish call *inside* the same transaction boundary, effectively creating a dual-write. The publish either (a) fails and silently loses the event, or (b) succeeds before the DB commits and produces an event for a transaction that later rolls back.

**Why it happens:** Misreading the architecture intent. The outbox row write IS the event commitment — Debezium picks it up after commit. Publishing to Kafka inside the TX violates this invariant.

**Warning signs:**
- `KafkaTemplate.send()` appears anywhere inside a `@Transactional` method alongside outbox inserts
- No Debezium connector defined but Kafka messages still flow

**Prevention:**
- Outbox row insert is the ONLY side effect inside the TX. Kafka publish happens exclusively via Debezium CDC.
- Code review rule: `KafkaTemplate` must not be injected into any class that also has `@Transactional` outbox logic.
- Integration test: Kill Debezium mid-run — the event must survive (it's in the outbox) and be delivered after Debezium restarts.

**Phase:** Phase 1

**Confidence:** HIGH — fundamental pattern invariant, not version-sensitive.

---

### Pitfall 1.2 — DECIMAL precision loss for monetary amounts (Critical)

**What goes wrong:** Using `DECIMAL(15,2)` in PostgreSQL is correct, but mapping it to Java `double` or `float` in the entity causes IEEE 754 rounding. A balance of `1000.10` becomes `1000.0999999999999` in Java.

**Why it happens:** JPA default mapping for `DECIMAL` columns is frequently `double`. Developers don't notice until a balance assertion test fails or an audit report shows fractional cent discrepancies.

**Warning signs:**
- `@Column` on a balance field with no `columnDefinition` specified
- Entity field declared as `double balance` instead of `BigDecimal balance`
- Balance arithmetic done with `+` operator on primitives

**Prevention:**
- All monetary fields: `BigDecimal` in Java, `DECIMAL(15,4)` in PostgreSQL (4dp for internal precision, 2dp for display).
- Use `RoundingMode.HALF_EVEN` (banker's rounding) for all divisions.
- Add a unit test: `assert transfer(1000.10 + 999.90) == 2000.00` using exact BigDecimal comparison.

**Phase:** Phase 1

**Confidence:** HIGH — well-established Java monetary handling requirement.

---

### Pitfall 1.3 — Balance read-modify-write race without SELECT FOR UPDATE (Critical)

**What goes wrong:** The debit logic reads the balance, checks sufficiency, then updates. Without `SELECT FOR UPDATE` (pessimistic lock) or optimistic locking (`@Version`), two concurrent transfers from the same account both read the same balance, both pass the sufficiency check, and both debit — producing a negative balance.

**Why it happens:** Spring Data `@Transactional` provides isolation but the default PostgreSQL isolation level (Read Committed) does NOT prevent two transactions from reading the same committed balance concurrently. Only `REPEATABLE READ` + `FOR UPDATE` or optimistic locking prevents the race.

**Warning signs:**
- `findById(accountId)` followed by `save(account)` with no lock annotation
- No `@Lock(LockModeType.PESSIMISTIC_WRITE)` or `@Version` field on Account entity
- No integration test with concurrent transfer requests to the same account

**Prevention:**
- Use `@Lock(LockModeType.PESSIMISTIC_WRITE)` on the balance query repository method.
- Alternative: optimistic locking with `@Version long version` — but this causes retries under contention, which is worse UX for a banking scenario.
- Integration test: fire 10 concurrent transfers of $100 from a $500 account — exactly 5 should succeed, 5 should fail.

**Phase:** Phase 1

**Confidence:** HIGH.

---

### Pitfall 1.4 — State machine transitions not enforced at DB level (High)

**What goes wrong:** The `TransferState` state machine is enforced in Java code but not in the database. Direct DB updates (migration scripts, manual fixes, future admin tools) can set a transfer to an invalid state (e.g., `CONFIRMED → PENDING`). The system then behaves unpredictably.

**Why it happens:** Developers trust the application layer and skip DB-level constraints.

**Warning signs:**
- No `CHECK` constraint on the `status` column limiting it to valid enum values
- No DB trigger or constraint preventing backwards state transitions

**Prevention:**
- Add `CHECK (status IN ('PENDING', 'PAYMENT_PROCESSING', 'PAYMENT_DONE', 'STOCK_RESERVING', 'CONFIRMED', 'COMPENSATING', 'CANCELLED'))` to the transfers table.
- Consider a DB trigger that raises an error on illegal backwards transitions.
- Spring state machine: ensure `StateMachineConfig` specifies transitions exhaustively — no wildcard `from(*)` paths.

**Phase:** Phase 1

**Confidence:** HIGH.

---

## 2. Outbox Pattern + Debezium CDC

### Pitfall 2.1 — PostgreSQL WAL level not set to `logical` (Critical)

**What goes wrong:** Debezium PostgreSQL connector requires `wal_level = logical` in `postgresql.conf`. The default is `replica`. If not set, the Debezium connector starts, connects, then fails with a cryptic error: `ERROR: logical replication not enabled` or silently produces no events.

**Why it happens:** The PostgreSQL Docker/Podman image starts with default config. Most compose examples forget to set this via environment variable or config mount.

**Warning signs:**
- Debezium connector status is `RUNNING` but no events appear in Kafka
- PostgreSQL logs show: `FATAL: logical replication not enabled`
- `SHOW wal_level;` returns `replica` instead of `logical`

**Prevention:**
- In compose.yml, set PostgreSQL command args: `command: ["postgres", "-c", "wal_level=logical", "-c", "max_replication_slots=5", "-c", "max_wal_senders=5"]`
- Verify immediately after container start: `psql -c "SHOW wal_level;"` — must return `logical`.
- Add this as a smoke test in Phase 1 setup script.

**Phase:** Phase 1

**Confidence:** HIGH — this is the most common Debezium PostgreSQL setup failure.

---

### Pitfall 2.2 — Replication slot leak causing WAL disk exhaustion (Critical)

**What goes wrong:** Debezium creates a PostgreSQL logical replication slot. If the Debezium connector is stopped without cleanly dropping the slot, the slot remains. PostgreSQL cannot purge WAL segments that the slot has not consumed, causing WAL to grow unboundedly until disk is full. On a local dev machine with limited SSD space, this can silently kill the entire kind cluster.

**Why it happens:** During development, developers frequently `podman-compose down` without running `SELECT pg_drop_replication_slot('debezium')` first. Restarting creates a new slot, orphaning the old one.

**Warning signs:**
- `SELECT * FROM pg_replication_slots;` shows slots with `active = false`
- `pg_wal` directory growing rapidly
- PostgreSQL stops accepting connections (disk full)
- `pg_current_wal_lsn()` diverges wildly from slot's `confirmed_flush_lsn`

**Prevention:**
- Set `slot.drop.on.stop=true` in the Debezium connector configuration (if using Debezium 2.x+, this may be `slot.drop.on.stop` — verify exact property name against connector version).
- Add a `make clean` target that explicitly drops replication slots before destroying containers.
- Set `max_replication_slots=3` (not unlimited) so orphaned slots fail loudly rather than silently accumulating.
- Monitor slot lag: alert if `pg_replication_slots.confirmed_flush_lsn` lags the current WAL position by more than 100MB.

**Phase:** Phase 1 (setup), Phase 3 (after K8s migration)

**Confidence:** HIGH — well-documented operational hazard.

---

### Pitfall 2.3 — Tombstone events breaking consumer idempotency (High)

**What goes wrong:** The Debezium Outbox Event Router by default emits a tombstone (null-value) event after each outbox row event, to enable log compaction. If the Kafka topic is configured for compaction (which is correct long-term), these tombstones delete earlier events for the same key. Consumers that replay the log after the tombstone are missing events. Additionally, some consumer frameworks fail on null payloads.

**Why it happens:** The outbox router's `tombstones.on.delete` config defaults to `true` for good reason (compaction), but the interaction with consumer replays is not understood.

**Warning signs:**
- Kafka topic is configured as `cleanup.policy=compact` AND `tombstones.on.delete=true`
- Consumer framework throws NPE or deserialization error on null message value
- Idempotency replay in a new consumer group sees incomplete event history

**Prevention:**
- For the outbox topic, use `cleanup.policy=delete` (time-based retention, e.g., 7 days) rather than compaction. Outbox events are fire-and-forget — compaction offers no benefit here.
- Set `tombstones.on.delete=false` in the Debezium outbox router SMT configuration.
- Consumer code must explicitly handle null payloads defensively even with the above, as a safety net.

**Phase:** Phase 1

**Confidence:** HIGH.

---

### Pitfall 2.4 — Outbox table not excluded from CDC capture (High)

**What goes wrong:** Debezium is configured to capture all tables in the schema. The outbox table itself gets CDC'd, producing a second stream of `outbox` row-change events on the internal Debezium change topic — separate from the routed outbox events. This creates duplicate event processing if consumers listen to the wrong topic.

**Why it happens:** Default Debezium configuration captures all tables. Developers don't add a `table.include.list` filter.

**Warning signs:**
- Kafka shows topics like `banking.public.outbox` AND `banking.transfers` — both containing transfer events
- Consumers receive duplicate events

**Prevention:**
- Set `table.include.list=public.outbox` in the Debezium connector config (only capture the outbox table for the Outbox Router to process).
- OR if capturing other tables for other purposes, explicitly set `table.exclude.list` to exclude the outbox table from raw capture while still routing via the SMT.
- Verify topic list after connector start: only expected topics should exist.

**Phase:** Phase 1

**Confidence:** HIGH.

---

### Pitfall 2.5 — Schema evolution breaking the Debezium connector (High)

**What goes wrong:** Adding a `NOT NULL` column to the `outbox` table (or any captured table) without a default value causes Debezium to fail on the schema change event. The connector enters a `FAILED` state and stops processing.

**Why it happens:** Liquibase/Flyway migrations run against the live DB, but Debezium is streaming the WAL which includes DDL events. A new non-nullable column with no default cannot be reconstructed for historical rows.

**Warning signs:**
- Debezium connector drops to `FAILED` state immediately after a DB migration runs
- Error: `Schema 'public.outbox' field 'new_column' is NOT NULL but has no default`

**Prevention:**
- All outbox table schema changes must be: (a) add column as NULLABLE first, (b) backfill, (c) optionally add NOT NULL constraint.
- Never use `Flyway`/`Liquibase` `addNotNullConstraint` without a `defaultValue` on any Debezium-captured table.
- Test schema migrations in isolation against a running Debezium connector before applying to the main dev environment.

**Phase:** Phase 1, ongoing

**Confidence:** HIGH.

---

### Pitfall 2.6 — The `published` column polling anti-pattern (Medium)

**What goes wrong:** The project plan shows an outbox table with a `published BOOLEAN DEFAULT FALSE` column. This implies an alternative polling pattern (a scheduled job marks rows as published). If BOTH Debezium CDC AND a polling scheduler are active, events are published twice. If only polling is used (Debezium omitted), events can be delayed by the poll interval and missed if the scheduler crashes.

**Why it happens:** The `published` column is from the polling outbox variant; Debezium CDC-based outbox does not need this column — Debezium reads the WAL stream directly and the row can be deleted after emission.

**Warning signs:**
- `published` column exists AND Debezium connector is also configured
- A `@Scheduled` method queries `WHERE published = false`

**Prevention:**
- Decision: use CDC-only outbox (no `published` column, no scheduler). Debezium reads the WAL. After successful delivery, the Outbox Router can delete the row (configure `delete.handling.mode=rewrite` or handle cleanup separately).
- OR: use polling-only outbox (with `published` column, no Debezium). But this forgoes the CDC benefits.
- Do NOT mix both. Pick one and document the decision explicitly.

**Phase:** Phase 1

**Confidence:** HIGH.

---

## 3. Saga Pattern + Kafka

### Pitfall 3.1 — No dead letter queue for failed Saga steps (Critical)

**What goes wrong:** A Saga consumer (e.g., `ledger-service`) receives a Kafka message and throws an unhandled exception. Without a DLQ, the consumer either: (a) retries forever, blocking partition progress for all subsequent messages, or (b) skips the message after max retries and silently loses it. Either way, the Saga is stuck or an event is lost — both are unacceptable in banking.

**Why it happens:** Spring Kafka's `@KafkaListener` default error handler does not redirect to a DLQ. Developers assume exceptions are logged and retried cleanly.

**Prevention:**
- Configure `DeadLetterPublishingRecoverer` + `DefaultErrorHandler` with exponential backoff.
- DLQ topic naming convention: `{topic-name}.DLT` (e.g., `banking.transfers.DLT`).
- DLQ consumer must: (a) alert, (b) log the full payload, (c) NOT retry automatically — require manual intervention for financial events.
- Add the DLQ consumer skeleton in Phase 1 even if it just logs — don't defer this.

**Warning signs:**
- `spring.kafka.consumer.max-poll-records` set high with no error handler configured
- No `.DLT` topics defined in `topics.yml`

**Phase:** Phase 1

**Confidence:** HIGH.

---

### Pitfall 3.2 — Saga compensation not idempotent (Critical)

**What goes wrong:** The COMPENSATING state triggers a compensating transaction (e.g., reverse a debit). If the compensation message is delivered twice (Kafka at-least-once), the account is debited twice — corrupting the balance.

**Why it happens:** Developers implement happy-path idempotency (Redis key on initial payment) but forget that compensation events also need idempotency protection.

**Warning signs:**
- Compensation handlers have no idempotency check
- `TransferState` allows `COMPENSATING → COMPENSATING` (re-entry)

**Prevention:**
- Each compensation action must check: has this saga step already been compensated? Use a `saga_log` table with `(saga_id, step, status)` — idempotency by saga_id + step.
- State machine must reject re-entry into COMPENSATING if already in CANCELLED.
- Test: send the same compensation event 3 times — account balance must be unchanged after the first.

**Phase:** Phase 1

**Confidence:** HIGH.

---

### Pitfall 3.3 — Kafka consumer group rebalance during long Saga steps (High)

**What goes wrong:** A Saga step takes longer than `max.poll.interval.ms` (default 5 minutes). Kafka considers the consumer dead, triggers a rebalance, reassigns the partition to another instance, which re-processes the same message. Now two instances are running the same Saga step concurrently.

**Why it happens:** Banking operations (fraud checks, external API calls) can legitimately take minutes. The default poll interval is generous but not infinite.

**Warning signs:**
- Logs show `Consumer group rebalance` during active Saga processing
- Duplicate Saga step execution logs for the same transfer ID

**Prevention:**
- For long-running steps, immediately commit the Kafka offset and track Saga state in the DB (`saga_log` table). The Saga step checks state before processing.
- Set `max.poll.interval.ms=600000` (10 min) for Saga consumers that may be slow, paired with `heartbeat.interval.ms` appropriately.
- Use async processing: read the Kafka message, persist intent to DB, return quickly to Kafka, process async. This is the correct pattern for banking Sagas.

**Phase:** Phase 1

**Confidence:** HIGH.

---

### Pitfall 3.4 — No Kafka topic partition ordering guarantee across services (Medium)

**What goes wrong:** Transfer events are produced to a Kafka topic. Two events for the same transfer are produced in order, but the consumer receives them out of order because they ended up on different partitions (partition key not set to transfer ID).

**Why it happens:** Kafka only guarantees ordering within a partition. Without setting `key = transferId`, events are round-robin distributed across partitions.

**Warning signs:**
- Kafka producer does not set a message key
- State machine receives `PAYMENT_DONE` before `PAYMENT_PROCESSING` for the same transfer

**Prevention:**
- Always produce outbox events with `aggregate_id` (transfer ID) as the Kafka message key. Debezium Outbox Router uses `aggregate_id` as the key by default — verify this is configured.
- Saga consumers must be idempotent and tolerate out-of-order delivery as a defensive measure (check DB state, not just message order).

**Phase:** Phase 1

**Confidence:** HIGH.

---

## 4. Kubernetes + Istio Service Mesh

### Pitfall 4.1 — Sidecar injection not enabled at namespace level (Critical)

**What goes wrong:** Pods are deployed but Istio sidecar (`istio-proxy`) is not injected because the `banking` namespace lacks the `istio-injection=enabled` label. Services appear to work (plain HTTP between pods) but mTLS is silently absent, Kiali shows no graph, and all the Istio features (circuit breaking, retries, timeouts) are inactive.

**Why it happens:** The namespace manifest is applied but the `labels:` block omits `istio-injection: enabled`, or the label is added after pods are deployed (injection happens at pod creation, not retroactively).

**Warning signs:**
- `kubectl describe pod <name>` shows 1 container (not 2 — the sidecar adds `istio-proxy`)
- Kiali service graph is empty
- `istioctl analyze` reports warnings

**Prevention:**
- Namespace YAML must include: `labels: { istio-injection: enabled }`
- After deploying, verify: `kubectl get pods -n banking` — every pod should show `2/2 READY` (app + istio-proxy).
- Add `istioctl analyze -n banking` to the Phase 3 verification checklist.
- If pods were deployed before labeling the namespace, do a rolling restart: `kubectl rollout restart deployment -n banking`.

**Phase:** Phase 3

**Confidence:** HIGH.

---

### Pitfall 4.2 — mTLS PERMISSIVE mode left in production-like config (High)

**What goes wrong:** Istio's default PeerAuthentication mode is `PERMISSIVE` — it accepts both plaintext and mTLS traffic. This means mTLS is not actually enforced: a service that fails to present a certificate (e.g., a misconfigured sidecar) still communicates in plaintext. The system appears to work, mTLS is claimed, but it is not verified.

**Why it happens:** `PERMISSIVE` is the migration-friendly default. Developers never switch to `STRICT` because "it works."

**Warning signs:**
- No `PeerAuthentication` manifest in the `istio/` directory
- `kubectl get peerauthentication -n banking` returns nothing
- Kiali shows some edges as "plaintext" in the security view

**Prevention:**
- After Phase 3 is stable, add a `PeerAuthentication` manifest with `mtls.mode: STRICT` for the `banking` namespace.
- Verify: `istioctl x check-inject -n banking` and Kiali security view should show all internal traffic as mTLS.
- Test that a plain HTTP call from outside the mesh to an internal service is rejected (not just unauthenticated — actively refused).

**Phase:** Phase 3

**Confidence:** HIGH.

---

### Pitfall 4.3 — Istio sidecar init container race with application startup (High)

**What goes wrong:** When a pod starts, the `istio-init` container configures iptables to intercept all traffic through the sidecar. However, the application container starts concurrently and may attempt outbound connections before `istio-proxy` is ready. The connection is intercepted but `istio-proxy` isn't listening yet, causing `Connection refused` errors during startup.

**Why it happens:** Kubernetes starts all init containers first, then all containers concurrently. `istio-proxy` has its own startup time.

**Warning signs:**
- Service logs show `Connection refused` to other services in the first few seconds after startup
- Health checks fail transiently on startup
- `READY 0/2` briefly before stabilizing

**Prevention:**
- Set `holdApplicationUntilProxyStarts: true` in the Istio `MeshConfig` (or `proxy.holdApplicationUntilProxyStarts` IstioOperator option). This causes the `istio-proxy` to delay the application container start until the proxy is ready.
- In Spring Boot, set a startup delay or use `readinessProbe` with appropriate `initialDelaySeconds` to tolerate the race.
- Add retry logic on startup HTTP client calls (Spring Retry or Resilience4j `@Retry`).

**Phase:** Phase 3

**Confidence:** HIGH.

---

### Pitfall 4.4 — VirtualService timeout shorter than application timeout (Medium)

**What goes wrong:** The plan shows Istio VirtualService `timeout: 10s` and `perTryTimeout: 3s` with `attempts: 3`. Total possible time = 3 attempts × 3s = 9s, which fits within 10s — but only barely. If a retry is triggered at 9s, the outer timeout fires before the retry completes, returning a 504 to the client. The retry logic provides no benefit.

**Why it happens:** Timeout values are chosen independently at the Istio layer without considering the application's own `RestTemplate`/HTTP client timeout, which may be longer (e.g., 30s default).

**Warning signs:**
- 504 errors in the gateway after exactly 10 seconds
- Retry count in Istio metrics shows retries firing but no successful completions

**Prevention:**
- Timeout hierarchy: `application HTTP client timeout` > `Istio perTryTimeout` > `Istio global timeout`.
- For banking: set VirtualService `timeout: 30s`, `perTryTimeout: 8s`, `attempts: 3` (total max: 24s, within outer timeout).
- Set Spring Boot `RestClient`/`HttpClient` timeouts to 25s so the application fails before Istio cancels.
- Explicitly test timeout behaviour by adding an artificial delay endpoint.

**Phase:** Phase 3

**Confidence:** HIGH.

---

### Pitfall 4.5 — kind cluster resource exhaustion with full observability stack (High)

**What goes wrong:** Running Kafka + Zookeeper + Debezium + 4 Spring Boot services + PostgreSQL × 4 + Redis + Istio (control plane + sidecars × N pods) + Prometheus + Jaeger + Loki + Grafana + Neo4j + Kong + Keycloak on a local kind cluster exceeds available RAM. Pods start OOMKilling each other. The cluster becomes unstable.

**Why it happens:** Each Istio sidecar consumes ~50-100MB RAM. With 10+ pods, that's 1GB in sidecars alone. Full observability stack adds another 2-4GB. Spring Boot services with JVM overhead add 512MB-1GB each.

**Warning signs:**
- `kubectl top nodes` shows memory > 85% usage
- Pods randomly restart with `OOMKilled` reason
- kind cluster node becomes `NotReady`

**Prevention:**
- Set strict resource requests and limits on all pods: `requests.memory: 256Mi`, `limits.memory: 512Mi` for Spring Boot services (virtual threads help here).
- Configure JVM: `-XX:MaxRAMPercentage=70` inside containers.
- Phase the deployment: don't run full stack in Phase 3. Bring up services incrementally.
- kind cluster config: allocate at least 12GB RAM to the kind node (`extraMounts` or kind config `memory: 12Gi`).
- On Windows with Podman (WSL2 backend), set `.wslconfig` to allocate sufficient memory.

**Phase:** Phase 3

**Confidence:** HIGH.

---

## 5. Kong + Keycloak JWT Auth Chain

### Pitfall 5.1 — Kong JWT plugin configured with wrong algorithm (Critical)

**What goes wrong:** Keycloak issues RS256 JWTs by default. The Kong JWT plugin must be configured with the Keycloak realm's RS256 public key. If Kong is configured with a symmetric HS256 secret instead, JWT validation fails with a cryptic `401 Unauthorized` and no useful error message. Alternatively, if the public key is not rotated in Kong when Keycloak rotates keys, all JWTs suddenly fail.

**Why it happens:** Kong JWT plugin documentation shows HS256 examples. Developers copy-paste and don't switch to RS256 config.

**Warning signs:**
- All API calls return `401` after Keycloak realm creation
- Kong logs: `[jwt] signature verification failed`
- Keycloak token introspection succeeds but Kong rejects the same token

**Prevention:**
- Configure Kong JWT consumer with `algorithm: RS256` and the Keycloak realm's RSA public key (fetch from `{keycloak-host}/realms/{realm}/protocol/openid-connect/certs`).
- Use Kong's JWKS support if available (Kong 2.4+): point Kong at the Keycloak JWKS endpoint so key rotation is automatic.
- Test the auth chain end-to-end in isolation before wiring in services: obtain Keycloak token → send to Kong → verify it reaches the service with `X-Consumer-Username` header set.

**Phase:** Phase 3

**Confidence:** HIGH.

---

### Pitfall 5.2 — Services trusting `X-User-Id` header from external requests (Critical)

**What goes wrong:** The architecture plan shows services "just read X-User-Id header — no auth code." This is safe only if Kong strips or rejects any incoming `X-User-Id` header from external clients before forwarding. If Kong passes through arbitrary headers, a malicious client can forge their identity by setting `X-User-Id: admin`.

**Why it happens:** Kong header injection (adding `X-User-Id` from the JWT claim) is configured, but stripping the same header from incoming requests is forgotten.

**Warning signs:**
- `curl -H "X-User-Id: admin" http://api-gateway/transfer` reaches the payment-service with the forged header
- Kong plugin config does not include `header_names: [X-User-Id]` in the strip-request-headers plugin

**Prevention:**
- Configure Kong `request-transformer` plugin to remove `X-User-Id` from incoming requests BEFORE the JWT plugin runs (or confirm the JWT plugin rewrites the header after validation).
- Alternatively: services should validate that `X-User-Id` corresponds to a claim in a forwarded JWT (forwarded as `Authorization: Bearer` by Kong). This adds defense-in-depth.
- Integration test: forge the header — verify the service rejects or ignores the forged identity.

**Phase:** Phase 3

**Confidence:** HIGH.

---

### Pitfall 5.3 — Keycloak token expiry not handled in MCP server (High)

**What goes wrong:** The MCP server authenticates with an API Key (per the plan), but if any MCP tool calls internal services that require a Keycloak token, those tokens expire (default 5 minutes for Keycloak access tokens). The MCP server holds a token, it expires mid-session, and subsequent tool calls silently fail or return 401.

**Why it happens:** Token refresh is often not implemented when the MCP server first authenticates — "it works during testing" because tests finish within 5 minutes.

**Warning signs:**
- MCP tool calls succeed for the first 5 minutes then start returning 401
- No token refresh logic in `main.py` or the HTTP client module

**Prevention:**
- If MCP server calls internal services requiring Keycloak tokens: implement client credentials flow with automatic token refresh (refresh 60s before expiry).
- Use the `python-keycloak` or `httpx` + `authlib` library for token lifecycle management.
- For the learning context: MCP server uses Kong API Key (as planned) — ensure ALL routes the MCP server calls are behind the API Key plugin, not the JWT plugin. This sidesteps token refresh entirely.

**Phase:** Phase 5

**Confidence:** HIGH.

---

### Pitfall 5.4 — Keycloak realm export/import losing client secrets (Medium)

**What goes wrong:** The `realm.json` export includes client configurations but client secrets are masked (Keycloak exports them as `**********` or empty). After importing the realm in a fresh container, clients have no secret — any service configured with the old secret will fail authentication with a 401.

**Why it happens:** Keycloak's export mechanism deliberately omits secrets for security. Developers don't notice until the environment is rebuilt.

**Warning signs:**
- Kong or services configured with a Keycloak client secret that is hardcoded in config
- After `podman-compose down && up`, auth suddenly fails

**Prevention:**
- Document the manual step: after realm import, reset client secrets and update all configs.
- Use deterministic secrets via environment variables: configure Keycloak client secrets through Keycloak's admin API at startup (use an init container or startup script).
- Store secrets in a `.env` file (gitignored) and inject via compose/K8s secrets.

**Phase:** Phase 3

**Confidence:** HIGH.

---

## 6. Neo4j ETL from Prometheus

### Pitfall 6.1 — ETL service overwrites edge metrics instead of accumulating (High)

**What goes wrong:** The Cypher in the plan uses `SET r.avg_latency_ms = $latency` — this overwrites the metric on each ETL run. If the ETL runs every 30 seconds, the graph shows only the instantaneous snapshot, not a meaningful average. More critically, `error_count` being overwritten means error spikes are lost between runs.

**Why it happens:** The `MERGE` + `SET` pattern is natural for "upsert," but for time-series-derived metrics, accumulation or proper averaging is needed.

**Prevention:**
- Use a time-windowed average: query Prometheus for the rate over the last 5 minutes (`rate(metric[5m])`) rather than the instant value.
- For `error_count`: store cumulative counts from Prometheus counters (which are monotonically increasing) rather than instantaneous rates.
- Add a `last_updated` timestamp to each edge — Neo4j queries can then filter out stale edges.
- Consider storing historical `OBSERVED_CALL_SNAPSHOT` nodes with a timestamp instead of overwriting, enabling trend analysis.

**Phase:** Phase 4

**Confidence:** HIGH.

---

### Pitfall 6.2 — PromQL metric names not matching actual Spring Boot metrics (High)

**What goes wrong:** The ETL service is written to query specific Prometheus metric names (e.g., `http_server_requests_seconds`), but Spring Boot 4 with Micrometer may emit slightly different metric names, or the labels (`uri`, `method`, `status`) differ from what the ETL expects. The ETL silently gets empty results and writes nothing to Neo4j.

**Why it happens:** Metric naming conventions changed between Spring Boot 2, 3, and 4. Documentation for Spring Boot 4 + Micrometer 1.13+ may differ from examples written for Spring Boot 2.

**Warning signs:**
- Neo4j graph shows no edges or all metrics are zero
- Prometheus UI shows metrics exist but ETL Cypher queries return empty
- `curl prometheus:9090/api/v1/label/__name__/values` shows different metric names than expected

**Prevention:**
- BEFORE writing the ETL: query Prometheus directly for actual metric names from running Spring Boot 4 services.
- Standard Micrometer HTTP metrics in Spring Boot 3+: `http.server.requests` (Micrometer format) → `http_server_requests_seconds` (Prometheus format). Verify the actual Prometheus label values.
- Write the ETL to discover metric names dynamically (query `/api/v1/metadata`) rather than hardcoding.

**Phase:** Phase 4

**Confidence:** MEDIUM — Spring Boot 4 metric naming is consistent with Boot 3 conventions per my training data, but verify against running system.

---

### Pitfall 6.3 — ETL runs inside the cluster but Prometheus scrapes are delayed (Medium)

**What goes wrong:** The ETL queries Prometheus for metrics, but Prometheus scrapes on a 15-second interval. The ETL may query between scrapes and get stale data. Additionally, if the ETL runs too frequently (e.g., every 5 seconds), it adds load to Prometheus and generates redundant Neo4j writes.

**Prevention:**
- Align ETL interval with Prometheus scrape interval: if scrape is every 15s, ETL runs every 30s or 60s.
- Use Prometheus `step` parameter in range queries to get consistent resolution.
- Add jitter to the ETL schedule to avoid synchronized spikes.

**Phase:** Phase 4

**Confidence:** HIGH.

---

### Pitfall 6.4 — Neo4j Community Edition limitations for production-like usage (Medium)

**What goes wrong:** Neo4j 5 Community Edition lacks: (a) role-based access control, (b) online backups, (c) clustering. For a learning sandbox this is fine, but the project plan says "Phase 4-5 are the seeds" for a more advanced AI system. Building patterns against Community that rely on Enterprise features causes a painful migration later.

**Warning signs:**
- Using `neo4j-admin database copy` expecting online hot backup (Enterprise only)
- Writing Cypher that uses `CALL dbms.security.createRole` (Enterprise only)

**Prevention:**
- For the learning context: Community Edition is correct. Document the limitations explicitly so Phase 4-5 evolution decisions are made with clear eyes.
- All Cypher queries should be written against the labeled property graph model (not Enterprise-specific procedures).
- Backup strategy: periodic `neo4j-admin database dump` (offline, available in Community).

**Phase:** Phase 4

**Confidence:** HIGH.

---

## 7. Python MCP Server for AI Agents

### Pitfall 7.1 — MCP tool errors not returning structured error context (Critical)

**What goes wrong:** A tool like `root_cause_analysis(service_name)` raises an exception (e.g., Neo4j connection refused, PromQL query timeout). The MCP server propagates the Python exception as a raw traceback. Claude receives an unstructured error string and cannot distinguish between "service is down" (retryable) and "invalid service name" (user error) — it either halts or gives a confusing response.

**Why it happens:** Python exception handling defaults to stack traces. MCP SDK tool error handling requires explicit structured error responses.

**Warning signs:**
- Tool function bodies have bare `except Exception as e: return str(e)`
- No distinction between tool errors and tool results in the MCP response schema

**Prevention:**
- Every MCP tool must return a structured result: `{"success": bool, "data": ..., "error": {"code": str, "message": str, "retryable": bool}}`.
- Use MCP SDK's `CallToolResult` with `isError=True` for failure cases so Claude can distinguish errors from results.
- Define an error taxonomy: `CONNECTION_ERROR`, `NOT_FOUND`, `TIMEOUT`, `INVALID_QUERY` — Claude's system prompt can reference these.
- Integration test: deliberately fail each dependency (Neo4j down, Prometheus down) — verify Claude receives actionable error context.

**Phase:** Phase 5

**Confidence:** HIGH.

---

### Pitfall 7.2 — Dangerous MCP tools with no confirmation gate (Critical)

**What goes wrong:** The plan includes `publish_event(topic, event_type, payload)` as an MCP tool. This allows Claude to publish arbitrary Kafka events into the live banking system. In an agentic RCA workflow, Claude might publish a compensating event based on incorrect analysis, corrupting transfer state.

**Why it happens:** All tools are treated equally. Read-only diagnostic tools (safe) and write/action tools (dangerous) are indistinguishable to Claude without explicit differentiation.

**Warning signs:**
- `publish_event` and `query_metrics` are implemented with the same safety profile
- No tool annotation distinguishing read-only from write tools

**Prevention:**
- Clearly annotate write tools in their MCP tool description: `"WARNING: This tool modifies banking system state. Only use when explicitly instructed and the operation is reversible."`
- Consider a `dry_run: bool = True` parameter on all write tools — default to dry_run unless explicitly overridden.
- For the learning context: consider making `publish_event` read-only initially (log only, don't actually publish) until the RCA system is mature.
- Implement audit logging on every write tool invocation (who called it, when, what arguments).

**Phase:** Phase 5

**Confidence:** HIGH.

---

### Pitfall 7.3 — MCP tool descriptions too vague for effective AI usage (High)

**What goes wrong:** Claude chooses tools based on their description. If `root_cause_analysis(service_name)` is described as "performs root cause analysis," Claude doesn't know what it returns, what inputs are valid, or how to chain it with `find_slow_services`. Claude makes poor tool choices or calls tools in the wrong order.

**Why it happens:** Tool descriptions are written from the developer's perspective ("this calls Neo4j") rather than the AI agent's perspective ("when to use this and what to expect").

**Prevention:**
- Write tool descriptions from Claude's perspective: "Use this when you need to identify which upstream dependency is causing latency for `service_name`. Returns a list of (dependency, avg_latency_ms, error_rate) tuples ordered by impact. Call `find_slow_services` first to identify candidates."
- Include example inputs and output shapes in the description.
- Chain hints: "Typically used after `get_jaeger_trace` when a trace shows high latency in a specific service."

**Phase:** Phase 5

**Confidence:** HIGH.

---

### Pitfall 7.4 — MCP server blocks on synchronous calls to slow backends (High)

**What goes wrong:** The Python MCP server makes synchronous calls to Neo4j, Prometheus, and Jaeger. If any backend is slow (Neo4j query runs 10 seconds), the entire MCP server thread blocks. Claude's tool call times out. For a single-threaded Python process, this blocks ALL tool calls.

**Why it happens:** Python MCP SDK examples often use synchronous HTTP clients (requests, py2neo). Async is not the default.

**Warning signs:**
- MCP server written as a single-file synchronous script
- `import requests` (sync) rather than `import httpx` with async/await
- No timeouts configured on backend calls

**Prevention:**
- Use `async def` for all MCP tool handlers.
- Use `httpx.AsyncClient` for Prometheus, Jaeger, and HTTP calls.
- Use `neo4j`'s async driver (`AsyncGraphDatabase.driver()`).
- Set explicit timeouts on all backend calls: `httpx.AsyncClient(timeout=10.0)`.
- Wrap in `asyncio.wait_for()` with a hard timeout and return a structured timeout error.

**Phase:** Phase 5

**Confidence:** HIGH.

---

### Pitfall 7.5 — Claude Desktop claude_desktop_config.json path errors on Windows (Medium)

**What goes wrong:** The MCP server is a Python process. `claude_desktop_config.json` on Windows must use Windows path format for the `command` field, but the MCP server might be designed assuming Unix paths. WSL2 paths, backslash escaping in JSON, and virtual environment activation are common failure points.

**Why it happens:** Most MCP examples are written for macOS/Linux. Windows + WSL2 + Podman adds path complexity.

**Warning signs:**
- Claude Desktop shows MCP server as "disconnected" on startup
- MCP server works when launched manually from terminal but not from Claude Desktop
- Path contains spaces (e.g., `C:\Program Files\...`) without quoting

**Prevention:**
- Use absolute Windows paths in `claude_desktop_config.json`: `"command": "C:\\path\\to\\venv\\Scripts\\python.exe"`.
- If running in WSL2: use `wsl.exe -e python ...` as the command with the WSL path.
- Test MCP server startup independently: `python main.py` should start without errors before wiring into Claude Desktop.
- Include a `start-mcp.bat` script in the repo that reproduces the exact startup command used in `claude_desktop_config.json`.

**Phase:** Phase 5

**Confidence:** HIGH (specific to Windows + Podman environment stated in project constraints).

---

## 8. Java 21 Virtual Threads + Spring Boot 4

### Pitfall 8.1 — Virtual threads + synchronized blocks causing pinning (High)

**What goes wrong:** Java 21 virtual threads are "pinned" to a platform thread when inside a `synchronized` block or method. If a synchronized block makes a blocking I/O call (e.g., JDBC query, Redis call), the platform thread is blocked — eliminating the scalability benefit of virtual threads. With many concurrent requests, platform threads are exhausted.

**Why it happens:** Legacy synchronization in third-party libraries (JDBC drivers, some Redis clients) uses `synchronized`. Setting `spring.threads.virtual.enabled=true` activates virtual threads without auditing for pinning.

**Warning signs:**
- JVM diagnostic: `jcmd <pid> Thread.print` shows many virtual threads in `PINNED` state
- Enable JVM flag `-Djdk.tracePinnedThreads=full` — output shows pinning locations
- Throughput does not improve relative to platform threads despite high concurrency

**Prevention:**
- Enable pinning diagnostics early: `-Djdk.tracePinnedThreads=short` in dev.
- Verify PostgreSQL JDBC driver version supports virtual threads without pinning (PgJDBC 42.7.0+ rewrote synchronization for VT compatibility).
- Redis client: use Lettuce (not Jedis) — Lettuce uses Netty and is VT-compatible.
- Spring Boot 4 + Hibernate 6.5+ should handle VT-compatible JDBC access — verify version compatibility.

**Phase:** Phase 1

**Confidence:** MEDIUM — virtual thread pinning behaviour is well-documented for Java 21, but specific library versions' VT compatibility may have changed between my knowledge cutoff and now.

---

### Pitfall 8.2 — Spring Boot 4 OTel auto-configuration conflicts with manual TracerProvider (Medium)

**What goes wrong:** Spring Boot 4 includes built-in OTel auto-configuration. If any code also manually creates a `TracerProvider` bean (from OTel Java SDK examples), Spring creates two providers. Spans are duplicated or dropped depending on which provider the instrumentation uses.

**Why it happens:** Developers follow OTel Java SDK documentation (which requires manual setup) without realizing Spring Boot 4 auto-configures everything via `spring.factories`.

**Warning signs:**
- Jaeger shows duplicate spans for the same operation
- `spring.autoconfigure.report` shows both `OtlpAutoConfiguration` and a manually registered bean conflicting

**Prevention:**
- Do NOT add `OpenTelemetry` or `TracerProvider` beans manually. Use only `application.yml` configuration.
- Verify: `management.otlp.tracing.endpoint` is set, sampling is configured, and traces appear in Jaeger — no manual code needed.
- If a custom span is needed, inject `io.micrometer.tracing.Tracer` (the Micrometer abstraction) rather than the raw OTel `Tracer`.

**Phase:** Phase 2

**Confidence:** HIGH.

---

### Pitfall 8.3 — Structured logging format breaking Loki log parsing (Medium)

**What goes wrong:** The plan configures `logging.structured.format.console: ecs` (ECS format). Loki is configured to parse JSON logs. If ECS format produces nested JSON (it does), but the Loki Promtail pipeline expects flat JSON, log labels are not extracted correctly. Grafana queries by label (`{service="account-service"}`) return no results.

**Why it happens:** ECS format and Logstash format are both JSON but have different top-level structures. Promtail pipeline stages must match the actual format.

**Warning signs:**
- Loki `{app="account-service"}` label selector returns no logs
- Promtail shows logs being ingested but label extraction pipeline produces no structured labels
- Grafana LogQL queries return results only with `{}` (no label filter)

**Prevention:**
- Verify ECS field names: `service.name`, `log.level`, `message` — Promtail `json` stage must reference these exact paths.
- Promtail config example for ECS:
  ```yaml
  pipeline_stages:
    - json:
        expressions:
          service: service.name
          level: log.level
    - labels:
        service:
        level:
  ```
- Integration test: after Phase 2 setup, verify a specific `trace_id` can be found in Loki via LogQL.

**Phase:** Phase 2

**Confidence:** MEDIUM — ECS format from Spring Boot 4 structured logging is relatively new (Boot 3.4+); confirm exact field names against running output.

---

## 9. Cross-Cutting: Podman on Windows

### Pitfall 9.1 — Podman Machine WSL2 networking vs kind cluster networking conflict (High)

**What goes wrong:** Podman on Windows uses a WSL2 VM (Podman machine). kind creates a Kubernetes cluster inside that VM. Istio's CNI plugin, Kong ingress, and Keycloak's JWKS endpoint may be accessible from inside the cluster but NOT from the Windows host (where Claude Desktop runs the MCP server). The MCP server cannot reach `http://keycloak:8080` because that hostname resolves inside WSL2, not on Windows.

**Why it happens:** Two network namespaces: Windows host ↔ WSL2 VM ↔ kind cluster internal. Each hop requires explicit port-forward or hostname mapping.

**Warning signs:**
- `curl http://localhost:8080` from Windows PowerShell times out
- `kubectl port-forward` works but the MCP server can't reach forwarded ports when started from Claude Desktop (different process context)

**Prevention:**
- Use `kubectl port-forward` for each service the MCP server needs to reach, and document which ports are forwarded.
- Alternatively: deploy MCP server inside the kind cluster as a pod (sidecar approach) — it then uses cluster-internal DNS.
- For Windows Claude Desktop: MCP server must be on Windows (not WSL2). It calls `localhost:{forwarded-port}` — maintain a port-forward script that keeps all necessary ports open.
- Create a `start-cluster.sh` script that sets up all port-forwards as background processes.

**Phase:** Phase 3, Phase 5

**Confidence:** HIGH (specific to Windows + Podman + kind environment).

---

### Pitfall 9.2 — kind image pull failures behind corporate proxy or slow network (Medium)

**What goes wrong:** kind pulls Kubernetes node images (multi-GB) and all service images. On a slow connection or behind a corporate proxy, pulls time out or fail. The kind cluster may appear to start but have nodes in `NotReady` state due to missing CNI images.

**Prevention:**
- Pre-pull all images before creating the kind cluster: `podman pull kindest/node:v1.32.0`.
- Use a local image registry (kind supports loading images directly: `kind load docker-image`).
- Set proxy environment variables in Podman machine if behind corporate proxy.

**Phase:** Phase 3

**Confidence:** MEDIUM.

---

## 10. Phase-Specific Warning Matrix

| Phase | Topic | Most Likely Pitfall | Severity | Mitigation Summary |
|-------|-------|---------------------|----------|--------------------|
| Phase 1 | Outbox setup | WAL level not set to `logical` | Critical | Set in compose command args, verify with `SHOW wal_level` |
| Phase 1 | Outbox + Debezium | Replication slot leak | Critical | `slot.drop.on.stop=true`, `make clean` drops slots |
| Phase 1 | ACID transfers | No `SELECT FOR UPDATE` on balance read | Critical | `@Lock(PESSIMISTIC_WRITE)` on balance query |
| Phase 1 | ACID transfers | Monetary type as `double` | Critical | `BigDecimal` + `DECIMAL(15,4)` always |
| Phase 1 | Outbox design | `published` column + Debezium both active | High | Pick CDC-only or polling-only, not both |
| Phase 1 | Saga | No DLQ configured | Critical | `DeadLetterPublishingRecoverer` from day one |
| Phase 1 | Saga | Compensation not idempotent | Critical | `saga_log` table as idempotency store |
| Phase 1 | Kafka | No partition key on outbox events | High | `aggregate_id` as Kafka message key |
| Phase 1 | Tombstones | Tombstone events on compact topic | High | Use `delete` cleanup policy for outbox topic |
| Phase 2 | Observability | Manual `TracerProvider` conflicts with Boot 4 auto-config | Medium | Config only, no manual OTel beans |
| Phase 2 | Logs | ECS format fields mismatch in Promtail pipeline | Medium | Verify ECS field paths, test LogQL label extraction |
| Phase 3 | Istio | Namespace missing `istio-injection: enabled` label | Critical | Label before deploying pods, verify `2/2 READY` |
| Phase 3 | Istio | mTLS left in PERMISSIVE mode | High | Add `PeerAuthentication` STRICT after stable |
| Phase 3 | Istio | Sidecar init container race | High | `holdApplicationUntilProxyStarts: true` |
| Phase 3 | Istio | Timeout hierarchy misconfiguration | Medium | VirtualService > perTryTimeout > app client timeout |
| Phase 3 | Kong | RS256 vs HS256 JWT algorithm mismatch | Critical | RS256 + JWKS endpoint, test auth chain first |
| Phase 3 | Kong | `X-User-Id` header forgeable | Critical | Strip incoming header before JWT plugin rewrites it |
| Phase 3 | Resources | OOM on kind cluster with full stack | High | Resource limits on all pods, 12GB+ allocated to kind |
| Phase 3 | Keycloak | Realm export loses client secrets | Medium | Document manual secret reset, use env-injected secrets |
| Phase 3 | Networking | MCP server can't reach cluster from Windows | High | Port-forward script, document all forwarded ports |
| Phase 4 | Neo4j ETL | ETL overwrites edge metrics (no accumulation) | High | Use Prometheus rate queries, add `last_updated` to edge |
| Phase 4 | Neo4j ETL | PromQL metric names don't match Boot 4 output | High | Verify actual metric names before writing ETL |
| Phase 5 | MCP | Write tools (publish_event) with no confirmation gate | Critical | `dry_run=True` default, annotate dangerous tools clearly |
| Phase 5 | MCP | Unstructured error responses from tools | Critical | Structured error schema with `retryable` flag |
| Phase 5 | MCP | Synchronous MCP server blocks on slow backends | High | Async tool handlers, `httpx.AsyncClient`, explicit timeouts |
| Phase 5 | MCP | Vague tool descriptions | High | Write descriptions from Claude's perspective with examples |
| Phase 5 | MCP | Windows path issues in `claude_desktop_config.json` | Medium | Absolute Windows paths, test startup independently |
| Phase 5 | Auth | Keycloak token expiry in MCP session | High | Use Kong API Key for MCP; avoid token refresh complexity |

---

## Confidence Summary

| Area | Confidence | Reason |
|------|------------|--------|
| Outbox + Debezium CDC | HIGH | Debezium PostgreSQL connector pitfalls are extensively documented and stable across versions |
| Saga + Kafka patterns | HIGH | Well-established distributed systems patterns, not version-sensitive |
| Istio sidecar/mTLS | HIGH | Common Istio misconfigurations are well-documented in Istio's own ops guides |
| Kong + Keycloak JWT | HIGH | RS256/HS256 confusion and header injection are known, documented issues |
| ACID transaction safety | HIGH | PostgreSQL locking and BigDecimal are fundamental, not version-sensitive |
| Virtual threads pinning | MEDIUM | Java 21 VT pinning is documented; library-specific VT compatibility may have evolved |
| Spring Boot 4 OTel | HIGH | Spring Boot 4 auto-configuration design is well-understood |
| Structured logging (ECS) | MEDIUM | Spring Boot 4 ECS format is relatively new; verify actual field names against running system |
| Neo4j ETL metric names | MEDIUM | Micrometer naming is stable but Boot 4 specifics should be verified against running instance |
| MCP server design | HIGH | Python MCP SDK patterns, async requirements, and tool design are well-established |
| Windows/Podman networking | HIGH | WSL2 + kind networking behaviour is a known constraint in the Podman-on-Windows ecosystem |

---

## Sources

All findings from training data (knowledge cutoff August 2025). External verification was unavailable during this research session (WebSearch and WebFetch denied). Key authoritative sources for manual verification:

- Debezium Outbox Event Router: https://debezium.io/documentation/reference/stable/transformations/outbox-event-router.html
- Debezium PostgreSQL connector: https://debezium.io/documentation/reference/stable/connectors/postgresql.html
- Istio common problems: https://istio.io/latest/docs/ops/common-problems/network-issues/
- Istio PeerAuthentication: https://istio.io/latest/docs/reference/config/security/peer_authentication/
- Kong JWT plugin: https://docs.konghq.com/hub/kong-inc/jwt/
- Spring Boot 4 actuator/OTel: https://docs.spring.io/spring-boot/docs/current/reference/html/actuator.html
- Java 21 virtual threads pinning: https://openjdk.org/jeps/444
- Python MCP SDK: https://github.com/modelcontextprotocol/python-sdk
- Neo4j 5 Community vs Enterprise: https://neo4j.com/docs/operations-manual/current/
