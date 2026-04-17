# OTel Trace Context Propagation Through Outbox Columns

**Date:** 2026-04-17  
**Status:** Approved  
**Scope:** account-service, ledger-service, payment-service, Debezium connector configs

---

## Problem

Debezium CDC sits between every service boundary in the BankForge saga. Because Debezium (not Spring Kafka) produces the Kafka messages, no W3C `traceparent` header is injected. Each service starts a new root trace in Jaeger, and the only cross-trace link is the `banking.transaction.id` span attribute — useful for searching but requires querying each service separately.

**Goal:** Merge all saga spans into a single continuous trace in Jaeger so the full flow (payment HTTP → account HTTP → ledger Kafka consumer → payment confirmation Kafka consumer) appears as one connected waterfall.

---

## Approach

Store the W3C `traceparent` string in a dedicated column on each outbox table. Debezium's Outbox Event Router SMT lifts that column into a Kafka message **header** via `table.fields.additional.placement`. The Kafka consumer reads the header, reconstructs the remote `SpanContext` using `W3CTraceContextPropagator`, and creates a child span with `setParent(remoteCtx)` — making it part of the originating trace.

This keeps the business payload (`TransferCreatedEvent`) clean of observability concerns and places trace context exactly where it belongs: in transport headers.

---

## Data Flow

```
payment-service: HTTP POST /api/payments/transfers  [trace A, span S1]
  └── account-service: HTTP POST /api/internal/transfers  [trace A, span S2]
        └── account outbox_event.traceparent = "00-<traceA>-<S2>-01"
              └── Debezium CDC → kafka header: traceparent
                    └── ledger-service: child span under trace A  [span S3]
                          └── ledger outbox_event.traceparent = "00-<traceA>-<S3>-01"
                                └── Debezium CDC → kafka header: traceparent
                                      └── payment-service: child span under trace A  [span S4]
```

All four spans share trace A. One trace in Jaeger, full saga waterfall.

---

## Components

### 1. Database Migrations (Flyway)

**account-service** — new migration `V3__add_traceparent_to_outbox.sql`:
```sql
ALTER TABLE outbox_event ADD COLUMN traceparent VARCHAR(55);
```

**ledger-service** — new migration `V3__add_traceparent_to_ledger_outbox.sql`:
```sql
ALTER TABLE ledger_outbox_event ADD COLUMN traceparent VARCHAR(55);
```

Column is nullable — existing rows and events from services that don't set it degrade gracefully (null header → no parent reconstruction → new root trace, same as today).

### 2. OutboxEvent Entities

Add `traceparent String` field (nullable) to:
- `account-service` `OutboxEvent.java`
- `ledger-service` `LedgerOutboxEvent.java`

### 3. account-service TransferService

When writing the outbox row, capture the current span context and format it as a W3C traceparent string:

```java
SpanContext ctx = Span.current().getSpanContext();
String traceparent = ctx.isValid()
    ? "00-" + ctx.getTraceId() + "-" + ctx.getSpanId() + "-01"
    : null;

OutboxEvent outbox = OutboxEvent.builder()
    .aggregatetype("transfer")
    .aggregateid(transferId.toString())
    .type("TransferInitiated")
    .payload(serializePayload(...))
    .traceparent(traceparent)          // new
    .build();
```

### 4. Debezium Connector Configs

Update `additional.placement` in **both** connectors to lift the `traceparent` column into a Kafka header:

**bankforge-outbox-connector** (account → ledger):
```
transforms.outbox.table.fields.additional.placement=aggregateid:header:transaction-id,traceparent:header:traceparent
```

**bankforge-ledger-outbox-connector** (ledger → payment):
```
transforms.outbox.table.fields.additional.placement=aggregateid:header:transaction-id,traceparent:header:traceparent
```

Connectors are updated via Debezium Connect REST API (`PUT /connectors/{name}/config`) — no restart required.

### 5. ledger-service LedgerEventListener

Add a `@Header` parameter for `traceparent`, create a child span before processing:

```java
@KafkaListener(...)
public void onTransferEvent(
        @Payload String payload,
        @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
        @Header(KafkaHeaders.OFFSET) long offset,
        @Header(value = "traceparent", required = false) String traceparent) {

    Span processingSpan = buildChildSpan("banking.transfer.events process", traceparent);
    try (Scope scope = processingSpan.makeCurrent()) {
        // ... existing business logic unchanged ...
        // When writing ledger_outbox_event, set traceparent from current span
    } finally {
        processingSpan.end();
    }
}

private Span buildChildSpan(String name, String traceparent) {
    SpanBuilder builder = tracer.spanBuilder(name);
    if (traceparent != null) {
        Context remoteCtx = W3CTraceContextPropagator.getInstance()
            .extract(Context.root(), Map.of("traceparent", traceparent), MapTextMapGetter.INSTANCE);
        builder.setParent(remoteCtx);
    }
    return builder.startSpan();
}
```

`MapTextMapGetter` is a small private static inner class implementing `TextMapGetter<Map<String,String>>`. `tracer` is injected as a Spring bean (`@Autowired Tracer tracer`) or obtained via `GlobalOpenTelemetry.getTracer("ledger-service")` in the constructor.

When writing the `ledger_outbox_event` confirmation row, set `traceparent` from the **current span** (which is now the child span under trace A — ensuring the chain continues).

### 6. payment-service TransferConfirmationListener

Same pattern — add `@Header(value = "traceparent", required = false)` and wrap business logic in a child span:

```java
@KafkaListener(topics = "banking.transfer.confirmed", groupId = "payment-service")
public void onTransferConfirmed(
        @Payload String payload,
        @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
        @Header(KafkaHeaders.OFFSET) long offset,
        @Header(value = "traceparent", required = false) String traceparent) {

    Span processingSpan = buildChildSpan("banking.transfer.confirmed process", traceparent);
    try (Scope scope = processingSpan.makeCurrent()) {
        UUID transferId = parseTransferId(payload);
        Span.current().setAttribute("banking.transaction.id", transferId.toString());
        transferStateService.confirm(transferId);
        incrementTransferInitiated(TransferState.CONFIRMED);
    } finally {
        processingSpan.end();
    }
}
```

`payment-service` needs its own `Tracer` bean injected or obtained via `GlobalOpenTelemetry.getTracer("payment-service")`.

---

## Auto-Observation Span Coexistence

`observationEnabled = true` continues to create a short-lived auto-observation span per Kafka message (for metrics and retry instrumentation). This span becomes a separate root trace. The manually created child span is the one that carries the saga's trace context.

In Jaeger, each Kafka consumer event shows two spans:
- A small auto-observation root span (ledger-service / payment-service service name, short duration)
- A child span nested under the originating payment-service trace (full duration)

This is acceptable. If the auto-observation spans become noise, `observationEnabled` can be scoped to the DLT handler only and the retry logic managed manually — that's a future concern.

---

## Error Handling

- **Null traceparent** (header missing or column null): `buildChildSpan` falls back to creating a new root span — same behaviour as today, no regression.
- **Malformed traceparent**: `W3CTraceContextPropagator.extract` returns an empty context on parse failure; `setParent` of an invalid context produces a root span. Same safe fallback.
- **Idempotent replay**: On replay, the stored `traceparent` still points to the original trace. The replayed consumer span becomes a child of the original trace — useful for debugging replays.

---

## Files Changed

| File | Change |
|---|---|
| `account-service/src/main/resources/db/migration/V3__add_traceparent_to_outbox.sql` | New — `ALTER TABLE outbox_event ADD COLUMN traceparent VARCHAR(55)` |
| `ledger-service/src/main/resources/db/migration/V3__add_traceparent_to_ledger_outbox.sql` | New — `ALTER TABLE ledger_outbox_event ADD COLUMN traceparent VARCHAR(55)` |
| `account-service/.../entity/OutboxEvent.java` | Add `traceparent` field |
| `ledger-service/.../entity/LedgerOutboxEvent.java` | Add `traceparent` field |
| `account-service/.../service/TransferService.java` | Set `traceparent` on outbox row |
| `ledger-service/.../kafka/LedgerEventListener.java` | Add `@Header traceparent`, child span, set on ledger outbox row |
| `payment-service/.../kafka/TransferConfirmationListener.java` | Add `@Header traceparent`, child span |
| Debezium connector configs (via REST API) | Add `traceparent:header:traceparent` to `additional.placement` |

---

## Out of Scope

- Propagating trace context through the notification-service path
- Suppressing auto-observation spans
- Persisting `traceparent` through idempotency replay paths in payment-service
