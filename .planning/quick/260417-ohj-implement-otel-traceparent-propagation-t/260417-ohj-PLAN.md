---
phase: 260417-ohj
plan: 01
type: execute
wave: 1
depends_on: []
files_modified:
  - account-service/src/main/resources/db/migration/V3__add_traceparent_to_outbox.sql
  - ledger-service/src/main/resources/db/migration/V4__add_traceparent_to_ledger_outbox.sql
  - account-service/src/main/java/au/com/bankforge/account/entity/OutboxEvent.java
  - ledger-service/src/main/java/au/com/bankforge/ledger/entity/LedgerOutboxEvent.java
  - account-service/src/main/java/au/com/bankforge/account/service/TransferService.java
  - ledger-service/src/main/java/au/com/bankforge/ledger/kafka/LedgerEventListener.java
  - payment-service/src/main/java/au/com/bankforge/payment/kafka/TransferConfirmationListener.java
  - infra/debezium/outbox-connector.json
  - infra/debezium/ledger-outbox-connector.json
autonomous: false
requirements:
  - OBS-TRACE-CONTINUITY
user_setup: []

must_haves:
  truths:
    - "A POST /api/payments/transfers call produces ONE Jaeger trace containing payment HTTP span, account HTTP span, ledger Kafka consumer span, and payment confirmation Kafka consumer span."
    - "outbox_event rows created by account-service have a non-null traceparent column matching the active trace during executeTransfer."
    - "ledger_outbox_event rows created by ledger-service have a non-null traceparent column matching the trace of the consumer span that wrote them."
    - "Kafka messages on banking.transfer.events carry a traceparent header sourced from the outbox row."
    - "Kafka messages on banking.transfer.confirmed carry a traceparent header sourced from the ledger outbox row."
    - "Null traceparent (missing header or column) degrades gracefully — consumer still processes the message, just as a new root trace (no regression)."
  artifacts:
    - path: "account-service/src/main/resources/db/migration/V3__add_traceparent_to_outbox.sql"
      provides: "Nullable traceparent column on outbox_event"
      contains: "ALTER TABLE outbox_event ADD COLUMN traceparent VARCHAR(55)"
    - path: "ledger-service/src/main/resources/db/migration/V4__add_traceparent_to_ledger_outbox.sql"
      provides: "Nullable traceparent column on ledger_outbox_event"
      contains: "ALTER TABLE ledger_outbox_event ADD COLUMN traceparent VARCHAR(55)"
    - path: "account-service/src/main/java/au/com/bankforge/account/entity/OutboxEvent.java"
      provides: "traceparent JPA field on OutboxEvent"
      contains: "private String traceparent"
    - path: "ledger-service/src/main/java/au/com/bankforge/ledger/entity/LedgerOutboxEvent.java"
      provides: "traceparent JPA field on LedgerOutboxEvent"
      contains: "private String traceparent"
    - path: "account-service/src/main/java/au/com/bankforge/account/service/TransferService.java"
      provides: "Capture Span.current() context and write traceparent to outbox row"
      contains: "Span.current().getSpanContext()"
    - path: "ledger-service/src/main/java/au/com/bankforge/ledger/kafka/LedgerEventListener.java"
      provides: "Read traceparent header, create child span under originating trace, write traceparent to ledger outbox row"
      contains: "W3CTraceContextPropagator"
    - path: "payment-service/src/main/java/au/com/bankforge/payment/kafka/TransferConfirmationListener.java"
      provides: "Read traceparent header, create child span under originating trace"
      contains: "W3CTraceContextPropagator"
    - path: "infra/debezium/outbox-connector.json"
      provides: "EventRouter additional.placement lifts traceparent column to Kafka header"
      contains: "traceparent:header:traceparent"
    - path: "infra/debezium/ledger-outbox-connector.json"
      provides: "EventRouter additional.placement lifts traceparent column to Kafka header"
      contains: "traceparent:header:traceparent"
  key_links:
    - from: "account-service TransferService.executeTransfer"
      to: "OutboxEvent.traceparent column"
      via: "Span.current().getSpanContext() formatted as W3C traceparent string"
      pattern: "\\.traceparent\\("
    - from: "Debezium outbox-connector"
      to: "Kafka header `traceparent` on banking.transfer.events"
      via: "transforms.outbox.table.fields.additional.placement"
      pattern: "traceparent:header:traceparent"
    - from: "ledger-service LedgerEventListener.onTransferEvent"
      to: "Child span under originating trace"
      via: "W3CTraceContextPropagator.extract(...) + spanBuilder.setParent(remoteCtx)"
      pattern: "W3CTraceContextPropagator\\.getInstance\\(\\)\\.extract"
    - from: "ledger-service LedgerEventListener"
      to: "LedgerOutboxEvent.traceparent column"
      via: "Span.current().getSpanContext() formatted as W3C traceparent string (inside child span scope)"
      pattern: "\\.traceparent\\("
    - from: "Debezium ledger-outbox-connector"
      to: "Kafka header `traceparent` on banking.transfer.confirmed"
      via: "transforms.outbox.table.fields.additional.placement"
      pattern: "traceparent:header:traceparent"
    - from: "payment-service TransferConfirmationListener.onTransferConfirmed"
      to: "Child span under originating trace"
      via: "W3CTraceContextPropagator.extract(...) + spanBuilder.setParent(remoteCtx)"
      pattern: "W3CTraceContextPropagator\\.getInstance\\(\\)\\.extract"
---

<objective>
Implement W3C `traceparent` propagation through Debezium outbox columns so the full saga (payment HTTP → account HTTP → ledger Kafka consumer → payment confirmation Kafka consumer) appears as a single connected trace in Jaeger.

Purpose: Today every service boundary that crosses Debezium starts a NEW root trace. The only cross-service link is the `banking.transaction.id` span attribute — searchable but not a single waterfall. This change stores the W3C traceparent in a dedicated outbox column, lifts it to a Kafka header via Debezium's Outbox Event Router SMT, and reconstructs a child span in each Kafka consumer using `W3CTraceContextPropagator`. Result: one trace per saga in Jaeger.

Output:
- Two Flyway migrations adding nullable `traceparent VARCHAR(55)` columns
- Two JPA entities with `traceparent` field
- Three services (account, ledger, payment) writing and/or reading `traceparent`
- Two Debezium connector JSON configs updated with `additional.placement`
- Human-verified end-to-end: one continuous trace visible in Jaeger
</objective>

<execution_context>
@$HOME/.claude/get-shit-done/workflows/execute-plan.md
@$HOME/.claude/get-shit-done/templates/summary.md
</execution_context>

<context>
@.planning/STATE.md
@docs/superpowers/specs/2026-04-17-otel-traceparent-outbox-propagation-design.md

@account-service/src/main/java/au/com/bankforge/account/entity/OutboxEvent.java
@account-service/src/main/java/au/com/bankforge/account/service/TransferService.java
@account-service/src/main/resources/db/migration/V2__create_outbox_table.sql
@ledger-service/src/main/java/au/com/bankforge/ledger/entity/LedgerOutboxEvent.java
@ledger-service/src/main/java/au/com/bankforge/ledger/kafka/LedgerEventListener.java
@ledger-service/src/main/resources/db/migration/V3__add_ledger_outbox.sql
@payment-service/src/main/java/au/com/bankforge/payment/kafka/TransferConfirmationListener.java
@infra/debezium/outbox-connector.json
@infra/debezium/ledger-outbox-connector.json

<interfaces>
<!-- Already-present OTel types the executor will consume. Use these directly — no exploration needed. -->

From io.opentelemetry.api.trace:
```java
public interface Span { static Span current(); SpanContext getSpanContext(); Span setAttribute(...); void end(); }
public interface SpanContext { String getTraceId(); String getSpanId(); boolean isValid(); TraceFlags getTraceFlags(); }
public interface Tracer { SpanBuilder spanBuilder(String name); }
public interface SpanBuilder { SpanBuilder setParent(Context ctx); Span startSpan(); }
```

From io.opentelemetry.api:
```java
public final class GlobalOpenTelemetry {
    public static Tracer getTracer(String instrumentationScopeName);
}
```

From io.opentelemetry.api.trace.propagation:
```java
public final class W3CTraceContextPropagator {
    public static W3CTraceContextPropagator getInstance();
    public <C> Context extract(Context ctx, C carrier, TextMapGetter<C> getter);
}
```

From io.opentelemetry.context:
```java
public interface Context { static Context root(); }
public interface Scope extends AutoCloseable { void close(); }
```

From io.opentelemetry.context.propagation:
```java
public interface TextMapGetter<C> {
    Iterable<String> keys(C carrier);
    String get(C carrier, String key);
}
```

W3C traceparent string format (55 chars fixed):
```
00-<32-hex-traceid>-<16-hex-spanid>-<2-hex-traceflags>
e.g. "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"
```
Use `"01"` (sampled) as the trace-flags byte per the design spec (literal `"01"`, not `ctx.getTraceFlags().asHex()` — spec is explicit).

Current `OutboxEvent` fields: `id, aggregatetype, aggregateid, type, payload, createdAt` (Lombok @Builder). Add `traceparent` nullable String, length 55.

Current `LedgerOutboxEvent` fields: same shape — add `traceparent` nullable String, length 55.

Existing Debezium connector status:
- `infra/debezium/outbox-connector.json` has **no** `additional.placement` line yet. Add:
  `"transforms.outbox.table.fields.additional.placement": "aggregateid:header:transaction-id,traceparent:header:traceparent"`
- `infra/debezium/ledger-outbox-connector.json` already has `aggregateid:header:transaction-id`. Change to:
  `"transforms.outbox.table.fields.additional.placement": "aggregateid:header:transaction-id,traceparent:header:traceparent"`

Note: STATE.md records "Debezium connector must be re-registered after every stack restart" — updating the JSON files on disk is the correct persistence point; the human-verify step will re-register.
</interfaces>
</context>

<tasks>

<task type="auto">
  <name>Task 1: DB migrations + entity fields for traceparent</name>
  <files>
    account-service/src/main/resources/db/migration/V3__add_traceparent_to_outbox.sql,
    ledger-service/src/main/resources/db/migration/V4__add_traceparent_to_ledger_outbox.sql,
    account-service/src/main/java/au/com/bankforge/account/entity/OutboxEvent.java,
    ledger-service/src/main/java/au/com/bankforge/ledger/entity/LedgerOutboxEvent.java
  </files>
  <action>
Create two Flyway migrations and add matching JPA fields.

1. **Create `account-service/src/main/resources/db/migration/V3__add_traceparent_to_outbox.sql`** — single statement:
```sql
ALTER TABLE outbox_event ADD COLUMN traceparent VARCHAR(55);
```
Add a brief header comment explaining this propagates W3C trace context via Debezium EventRouter `additional.placement` → Kafka header.

2. **Create `ledger-service/src/main/resources/db/migration/V4__add_traceparent_to_ledger_outbox.sql`** — NOTE: ledger-service already has V3 (`V3__add_ledger_outbox.sql`), so this must be V4 (not V3 as the design spec suggests — V3 is taken). Single statement:
```sql
ALTER TABLE ledger_outbox_event ADD COLUMN traceparent VARCHAR(55);
```
Add the same header comment.

3. **Update `account-service/.../entity/OutboxEvent.java`** — add a nullable `traceparent` field after `payload`, before `createdAt`:
```java
@Column(length = 55)
private String traceparent;
```
Keep all existing annotations (`@Getter`, `@Setter`, `@NoArgsConstructor`, `@AllArgsConstructor`, `@Builder`). Lombok `@Builder` will automatically generate `.traceparent(...)` builder method.

4. **Update `ledger-service/.../entity/LedgerOutboxEvent.java`** — same field, same placement:
```java
@Column(length = 55)
private String traceparent;
```

Do NOT add any other columns. Do NOT modify existing column definitions.
  </action>
  <verify>
    <automated>cd C:/SideProject/BankForge && "C:/Program Files/JetBrains/IntelliJ IDEA Community Edition 2025.2.4/plugins/maven/lib/maven3/bin/mvn" -pl account-service,ledger-service -am compile -DskipTests -Dmaven.test.skip=true -q</automated>
  </verify>
  <done>
- Both migration files exist with `ALTER TABLE ... ADD COLUMN traceparent VARCHAR(55)` statements
- `OutboxEvent` and `LedgerOutboxEvent` both declare `private String traceparent` with `@Column(length = 55)` (nullable by default)
- `mvn compile` succeeds for both account-service and ledger-service modules
- Lombok-generated builders still compile (existing call sites in `TransferService` and `LedgerEventListener` still work without touching traceparent)
  </done>
</task>

<task type="auto">
  <name>Task 2: Producer-side — write traceparent on both outbox rows</name>
  <files>
    account-service/src/main/java/au/com/bankforge/account/service/TransferService.java,
    ledger-service/src/main/java/au/com/bankforge/ledger/kafka/LedgerEventListener.java
  </files>
  <action>
Capture the current OTel span context and write a W3C traceparent string onto the outbox row in both services. This establishes the producer half of the propagation chain.

**1. Update `account-service/.../service/TransferService.java`:**

Inside `executeTransfer(...)`, immediately after the line `Span.current().setAttribute("banking.transaction.id", transferId.toString());` and before building the `OutboxEvent`, capture the traceparent:

```java
io.opentelemetry.api.trace.SpanContext ctx = Span.current().getSpanContext();
String traceparent = ctx.isValid()
        ? "00-" + ctx.getTraceId() + "-" + ctx.getSpanId() + "-01"
        : null;
```

Then chain `.traceparent(traceparent)` into the existing builder:

```java
OutboxEvent outbox = OutboxEvent.builder()
        .aggregatetype("transfer")
        .aggregateid(transferId.toString())
        .type("TransferInitiated")
        .payload(serializePayload(transferId, fromId, toId, amount))
        .traceparent(traceparent)         // NEW
        .build();
```

Add the import: `import io.opentelemetry.api.trace.SpanContext;` (or use fully-qualified name if preferred). The existing `import io.opentelemetry.api.trace.Span;` stays.

Use the literal string `"-01"` (sampled flag) per the approved design spec — do NOT derive from `ctx.getTraceFlags()`.

**2. Update `ledger-service/.../kafka/LedgerEventListener.java`:**

The existing listener is `@Transactional` and writes `LedgerOutboxEvent` inside the baggage scope. Inside that same scope (where `Span.current()` reflects the active processing span — Task 3 will make this the child span under the remote trace), capture the traceparent immediately before `LedgerOutboxEvent.builder()`:

```java
io.opentelemetry.api.trace.SpanContext outCtx = Span.current().getSpanContext();
String outTraceparent = outCtx.isValid()
        ? "00-" + outCtx.getTraceId() + "-" + outCtx.getSpanId() + "-01"
        : null;

LedgerOutboxEvent outboxRow = LedgerOutboxEvent.builder()
        .aggregatetype("transfer-confirmation")
        .aggregateid(transferId.toString())
        .type("TransferConfirmed")
        .payload("{\"transferId\":\"" + transferId + "\",\"status\":\"CONFIRMED\"}")
        .traceparent(outTraceparent)      // NEW
        .build();
```

Add `import io.opentelemetry.api.trace.SpanContext;`.

Do NOT change transactional boundaries, idempotency guard, or baggage setup. Do NOT touch `TransferConfirmationListener` yet — Task 3 handles that along with the child-span reconstruction logic for both listeners.
  </action>
  <verify>
    <automated>cd C:/SideProject/BankForge && "C:/Program Files/JetBrains/IntelliJ IDEA Community Edition 2025.2.4/plugins/maven/lib/maven3/bin/mvn" -pl account-service,ledger-service -am compile -DskipTests -Dmaven.test.skip=true -q</automated>
  </verify>
  <done>
- `TransferService.executeTransfer` captures `Span.current().getSpanContext()` and passes formatted traceparent to `OutboxEvent.builder().traceparent(...)`
- `LedgerEventListener.onTransferEvent` captures `Span.current().getSpanContext()` inside the baggage scope and passes formatted traceparent to `LedgerOutboxEvent.builder().traceparent(...)`
- Both use literal `"-01"` trace-flags suffix
- Both handle `isValid()==false` by passing `null` (graceful degradation)
- Both modules compile cleanly
  </done>
</task>

<task type="auto">
  <name>Task 3: Consumer-side — read traceparent header, create child span in ledger + payment listeners</name>
  <files>
    ledger-service/src/main/java/au/com/bankforge/ledger/kafka/LedgerEventListener.java,
    payment-service/src/main/java/au/com/bankforge/payment/kafka/TransferConfirmationListener.java
  </files>
  <action>
Reconstruct the remote parent context from the `traceparent` Kafka header and wrap existing business logic in a child span. Identical pattern in both listeners, but each service gets its own `Tracer` (service-scoped).

**Shared helper pattern** (replicate in BOTH files as private methods + a private static inner class — do NOT create a shared utility module; this is a quick task and both copies are ~15 lines):

```java
private Span buildChildSpan(Tracer tracer, String name, String traceparent) {
    io.opentelemetry.api.trace.SpanBuilder builder = tracer.spanBuilder(name);
    if (traceparent != null) {
        io.opentelemetry.context.Context remoteCtx =
                io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator.getInstance()
                        .extract(
                                io.opentelemetry.context.Context.root(),
                                java.util.Map.of("traceparent", traceparent),
                                MapTextMapGetter.INSTANCE);
        builder.setParent(remoteCtx);
    }
    return builder.startSpan();
}

private static final class MapTextMapGetter
        implements io.opentelemetry.context.propagation.TextMapGetter<java.util.Map<String, String>> {
    static final MapTextMapGetter INSTANCE = new MapTextMapGetter();
    @Override public Iterable<String> keys(java.util.Map<String, String> carrier) { return carrier.keySet(); }
    @Override public String get(java.util.Map<String, String> carrier, String key) {
        return carrier == null ? null : carrier.get(key);
    }
}
```

Feel free to clean up the wildcard-prefixed class names by adding explicit imports — the fully-qualified form above is just to make the contract unambiguous.

**1. Update `ledger-service/.../kafka/LedgerEventListener.java`:**

- Obtain a `Tracer` via `GlobalOpenTelemetry.getTracer("ledger-service")`. Preferred approach: store as a `private static final Tracer TRACER = io.opentelemetry.api.GlobalOpenTelemetry.getTracer("ledger-service");` constant to avoid DI-order concerns during app startup and to keep the class autowire footprint unchanged. (Both `Autowired Tracer` and `GlobalOpenTelemetry.getTracer(...)` are acceptable per the spec — pick the constant approach for simplicity.)
- Add `@Header(value = "traceparent", required = false) String traceparent` to the `onTransferEvent` method signature.
- At the top of the method body (BEFORE `parsePayload`), create the processing span:
  ```java
  Span processingSpan = buildChildSpan(TRACER, "banking.transfer.events process", traceparent);
  try (Scope scope = processingSpan.makeCurrent()) {
      // ... ALL existing business logic moves inside this block ...
  } finally {
      processingSpan.end();
  }
  ```
- The existing `@Transactional` annotation stays on the method. The existing idempotency check, baggage scope, ledger writes, and outbox write (with `traceparent` from Task 2) all sit INSIDE the `try-with-resources` block.
- Result: `Span.current()` inside the baggage scope is now the child span under the remote trace. Task 2's `Span.current().getSpanContext()` capture therefore propagates the ORIGINAL trace id onto the ledger outbox row.
- Add the private `buildChildSpan(...)` method and `MapTextMapGetter` inner class at the bottom of the class.

**2. Update `payment-service/.../kafka/TransferConfirmationListener.java`:**

- Add `private static final Tracer TRACER = io.opentelemetry.api.GlobalOpenTelemetry.getTracer("payment-service");`.
- Add `@Header(value = "traceparent", required = false) String traceparent` to the `onTransferConfirmed` method signature.
- Wrap the existing body (from `UUID transferId = parseTransferId(payload);` through `incrementTransferInitiated(TransferState.CONFIRMED);`) in the `try (Scope scope = processingSpan.makeCurrent()) { ... } finally { processingSpan.end(); }` block, constructing the span with:
  ```java
  Span processingSpan = buildChildSpan(TRACER, "banking.transfer.confirmed process", traceparent);
  ```
- Keep the existing `Span.current().setAttribute("banking.transaction.id", transferId.toString());` call — it now tags the child span under the originating trace (exactly what we want).
- Do NOT modify `@RetryableTopic` or `@DltHandler` — auto-observation spans coexisting with manual child spans is explicitly acceptable per the design spec ("Auto-Observation Span Coexistence" section).
- Add the private `buildChildSpan(...)` method and `MapTextMapGetter` inner class at the bottom of the class.

**Graceful-degradation constraint (both listeners):** If `traceparent == null` (header missing, e.g., during rolling deploy with old producer), `buildChildSpan` falls through with no `setParent` call → a new root span is started. Business logic runs unchanged. No exceptions on the happy or degraded path.

Do NOT introduce any new Spring beans. Do NOT refactor the ObjectMapper / MeterRegistry injections. Do NOT move logic out of these two files.
  </action>
  <verify>
    <automated>cd C:/SideProject/BankForge && "C:/Program Files/JetBrains/IntelliJ IDEA Community Edition 2025.2.4/plugins/maven/lib/maven3/bin/mvn" -pl ledger-service,payment-service -am compile -DskipTests -Dmaven.test.skip=true -q</automated>
  </verify>
  <done>
- `LedgerEventListener.onTransferEvent` accepts `@Header(value="traceparent", required=false) String traceparent`
- `TransferConfirmationListener.onTransferConfirmed` accepts the same header parameter
- Both listeners construct a child span via `buildChildSpan(TRACER, <span-name>, traceparent)` and wrap business logic in `try-with-resources` on `scope = processingSpan.makeCurrent()`, ending the span in `finally`
- Both contain a private `MapTextMapGetter` inner class
- Null traceparent path does not throw — handled via `if (traceparent != null)` guard inside `buildChildSpan`
- ledger-service and payment-service modules compile cleanly
- `Span.current()` inside ledger's baggage scope is the child span, so Task 2's traceparent capture writes the ORIGINAL trace id onto the ledger outbox row
  </done>
</task>

<task type="auto">
  <name>Task 4: Debezium connector configs — add traceparent:header:traceparent</name>
  <files>
    infra/debezium/outbox-connector.json,
    infra/debezium/ledger-outbox-connector.json
  </files>
  <action>
Update both Debezium connector JSON files so the EventRouter SMT lifts the new `traceparent` column into the Kafka message headers.

**1. `infra/debezium/outbox-connector.json`:**

This connector currently has NO `additional.placement` entry. Add ONE new line inside the `"config"` object, after `"transforms.outbox.route.topic.replacement"` and before `"key.converter"`:

```json
"transforms.outbox.table.fields.additional.placement": "aggregateid:header:transaction-id,traceparent:header:traceparent",
```

Note: this ALSO adds `aggregateid:header:transaction-id` for consistency with the ledger connector (the ledger consumer doesn't currently require it, but matching the two connectors prevents asymmetry surprises). Keep all other config fields untouched.

**2. `infra/debezium/ledger-outbox-connector.json`:**

This connector already has `"transforms.outbox.table.fields.additional.placement": "aggregateid:header:transaction-id"`. Replace that single line with:

```json
"transforms.outbox.table.fields.additional.placement": "aggregateid:header:transaction-id,traceparent:header:traceparent",
```

Do NOT change any other config fields, topic prefixes, slot names, publication names, or converter settings.

**Formatting requirements:** Keep existing JSON indentation (2 spaces). Preserve trailing commas / no-trailing-commas pattern of the original files (JSON has no trailing commas — make sure the new line has a comma because another field follows it).

The per-STATE.md convention is that connectors must be RE-REGISTERED after every stack restart via:
```
curl -X POST http://localhost:8085/connectors -d @infra/debezium/outbox-connector.json
curl -X POST http://localhost:8085/connectors -d @infra/debezium/ledger-outbox-connector.json
```
(or `PUT /connectors/<name>/config` with the inner `config` object for a hot-update — done in the verification task, not here). The disk change is the durable source of truth.
  </action>
  <verify>
    <automated>cd C:/SideProject/BankForge && node -e "['infra/debezium/outbox-connector.json','infra/debezium/ledger-outbox-connector.json'].forEach(f=>{const c=JSON.parse(require('fs').readFileSync(f,'utf8'));const p=c.config['transforms.outbox.table.fields.additional.placement']||'';if(!p.includes('traceparent:header:traceparent'))throw new Error(f+' missing traceparent placement: '+p);if(!p.includes('aggregateid:header:transaction-id'))throw new Error(f+' missing transaction-id placement: '+p);console.log('OK '+f+' -> '+p);});"</automated>
  </verify>
  <done>
- `outbox-connector.json` contains `"transforms.outbox.table.fields.additional.placement": "aggregateid:header:transaction-id,traceparent:header:traceparent"`
- `ledger-outbox-connector.json` contains the same placement string (with traceparent added to the existing transaction-id mapping)
- Both files are valid JSON (parse successfully)
- No other connector config fields mutated
  </done>
</task>

<task type="checkpoint:human-verify" gate="blocking">
  <name>Task 5: Human verify — one continuous trace in Jaeger</name>
  <what-built>
End-to-end OTel trace continuity:
1. `traceparent VARCHAR(55)` columns on `outbox_event` (account-db) and `ledger_outbox_event` (ledger-db)
2. account-service and ledger-service producers writing W3C traceparent strings onto outbox rows
3. Debezium connectors lifting the column to a `traceparent` Kafka header on both `banking.transfer.events` and `banking.transfer.confirmed`
4. ledger-service and payment-service Kafka listeners reconstructing the remote parent context and wrapping business logic in a child span under the originating payment-service trace

Expected observable outcome: ONE continuous Jaeger trace showing the full saga waterfall.
  </what-built>
  <how-to-verify>
1. **Build all services:**
   ```bash
   cd C:/SideProject/BankForge
   "C:/Program Files/JetBrains/IntelliJ IDEA Community Edition 2025.2.4/plugins/maven/lib/maven3/bin/mvn" -pl account-service,ledger-service,payment-service -am package -Dmaven.test.skip=true
   ```

2. **Restart the full stack** (migrations will run on service startup):
   ```bash
   podman compose down
   podman compose up -d --build
   ```
   Wait ~30s for all services to become healthy. Tail service logs to confirm Flyway applied `V3__add_traceparent_to_outbox` (account) and `V4__add_traceparent_to_ledger_outbox` (ledger).

3. **Re-register both Debezium connectors** (per STATE.md: connectors are NOT auto-registered on stack restart):
   ```bash
   curl -X DELETE http://localhost:8085/connectors/bankforge-outbox-connector 2>/dev/null
   curl -X DELETE http://localhost:8085/connectors/bankforge-ledger-outbox-connector 2>/dev/null
   curl -X POST -H "Content-Type: application/json" http://localhost:8085/connectors -d @infra/debezium/outbox-connector.json
   curl -X POST -H "Content-Type: application/json" http://localhost:8085/connectors -d @infra/debezium/ledger-outbox-connector.json
   curl -s http://localhost:8085/connectors/bankforge-outbox-connector/status | head -c 200
   curl -s http://localhost:8085/connectors/bankforge-ledger-outbox-connector/status | head -c 200
   ```
   Both should report `"state":"RUNNING"`.

4. **Create two accounts and issue a transfer:**
   ```bash
   SRC=$(curl -s -X POST http://localhost:8081/api/accounts -H "Content-Type: application/json" -d '{"accountNumber":"TRACE-SRC","ownerName":"Trace Src","initialBalance":1000.00,"currency":"AUD"}' | tee /dev/stderr | grep -oE '"id":"[0-9a-f-]+"' | head -1 | cut -d'"' -f4)
   DST=$(curl -s -X POST http://localhost:8081/api/accounts -H "Content-Type: application/json" -d '{"accountNumber":"TRACE-DST","ownerName":"Trace Dst","initialBalance":0.00,"currency":"AUD"}' | grep -oE '"id":"[0-9a-f-]+"' | head -1 | cut -d'"' -f4)
   curl -s -X POST http://localhost:8082/api/payments/transfers -H "Content-Type: application/json" -d "{\"fromAccountId\":\"$SRC\",\"toAccountId\":\"$DST\",\"amount\":10.00,\"currency\":\"AUD\"}"
   ```

5. **Verify outbox rows carry traceparent (should be 55-char W3C strings):**
   ```bash
   podman exec -it account-db psql -U account -d accountdb -c "SELECT aggregateid, LEFT(traceparent, 55) FROM outbox_event ORDER BY created_at DESC LIMIT 1;"
   podman exec -it ledger-db psql -U ledger -d ledgerdb -c "SELECT aggregateid, LEFT(traceparent, 55) FROM ledger_outbox_event ORDER BY created_at DESC LIMIT 1;"
   ```
   Both rows must show a non-null traceparent. Extract the 32-hex trace id (chars 4..36 of the string, i.e. between the first and second hyphens) from BOTH rows — **they must be identical** (same trace).

6. **Open Jaeger UI:** http://localhost:16686
   - Service dropdown: `payment-service`
   - Operation: `POST /api/payments/transfers` (or equivalent)
   - Find the trace matching the transferId returned by step 4
   - Expected tree (one trace, 4+ spans):
     - `payment-service` — POST /api/payments/transfers (root)
       - `account-service` — POST /api/internal/transfers (child via HTTP)
       - `ledger-service` — banking.transfer.events process (child via traceparent header)
       - `payment-service` — banking.transfer.confirmed process (child via traceparent header)
   - The auto-observation spans from Spring Kafka will appear as separate short-lived roots — this is expected and documented in the design spec.

7. **Confirm graceful degradation** (optional spot-check): the listeners handle missing `traceparent` header without error. The V3/V4 migrations are nullable, so any in-flight rows from before the producer change (if present) process as new root traces.

Report PASS when: (a) both DB rows show non-null traceparent with the SAME 32-hex trace id, (b) Jaeger shows ONE trace containing the payment HTTP span, account HTTP span, ledger Kafka consumer span, AND payment confirmation Kafka consumer span as a single waterfall.
  </how-to-verify>
  <resume-signal>Type "approved" once Jaeger shows the continuous trace, or describe which span is missing / still appears as a separate root trace.</resume-signal>
</task>

</tasks>

<verification>
- `outbox_event` and `ledger_outbox_event` both have `traceparent VARCHAR(55)` column (nullable)
- Fresh transfer: outbox rows on BOTH DBs carry a W3C traceparent whose 32-hex trace id is identical across both rows
- Kafka headers: `traceparent` present on `banking.transfer.events` and `banking.transfer.confirmed` (verifiable via `podman exec kafka kafka-console-consumer --property print.headers=true ...` if desired)
- Jaeger UI: one trace per transfer containing all four expected spans nested under the payment-service HTTP root
- No regression: existing integration tests and the Grafana `transfer_initiated_total` counters still work (traceparent column is nullable, header is `required=false`)
</verification>

<success_criteria>
Single-trace saga visible in Jaeger. Specifically: issuing a POST to `/api/payments/transfers` produces exactly one trace in Jaeger whose root is the payment-service HTTP span and which contains — as descendants — the account-service HTTP span, the ledger-service Kafka consumer processing span, and the payment-service Kafka confirmation processing span. Both outbox tables' most recent row shows a non-null traceparent with the matching 32-hex trace id. All four services still compile, start, and process a transfer end-to-end without errors.
</success_criteria>

<output>
After completion, create `.planning/quick/260417-ohj-implement-otel-traceparent-propagation-t/260417-ohj-SUMMARY.md` capturing:
- Migration versions used (V3 account, V4 ledger — note V4 because ledger's V3 was already taken by ledger-outbox migration)
- Decision: literal `"-01"` sampled trace-flags byte per spec
- Decision: duplicated `buildChildSpan` + `MapTextMapGetter` across two listeners instead of a shared util module (quick-task scope)
- Any surprises in Jaeger waterfall rendering (especially how auto-observation spans appear alongside the child spans)
- Confirmed traceparent trace id match between account-db and ledger-db rows for a sample transfer
</output>
