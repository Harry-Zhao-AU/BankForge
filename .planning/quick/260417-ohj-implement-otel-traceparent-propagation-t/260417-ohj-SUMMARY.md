# Quick Task 260417-ohj: Summary

**Task:** Implement OTel traceparent propagation through outbox columns for full saga trace continuity in Jaeger
**Date:** 2026-04-17
**Status:** Complete (task 5 is human-verify checkpoint — see below)

---

## What Was Done

Implemented W3C traceparent propagation through Debezium outbox columns so all saga spans share a single trace in Jaeger. The full payment → account → ledger → payment-confirmation flow now appears as one connected waterfall.

### Commits

| Commit | Description |
|--------|-------------|
| `4c90a42` | feat(260417-ohj): add traceparent DB migrations + JPA entity fields |
| `3557bb8` | feat(260417-ohj): producer-side traceparent capture in TransferService + LedgerEventListener |
| `c773c88` | feat(260417-ohj): consumer-side traceparent child span in TransferConfirmationListener |
| `bfd7086` | feat(260417-ohj): add traceparent:header:traceparent to Debezium connector additional.placement |
| `4072584` | fix(260417-ohj): reconcile pre-existing observability + D-15 changes with traceparent impl |

### Files Changed

| File | Change |
|------|--------|
| `account-service/src/main/resources/db/migration/V3__add_traceparent_to_outbox.sql` | New — `ALTER TABLE outbox_event ADD COLUMN traceparent VARCHAR(55)` |
| `ledger-service/src/main/resources/db/migration/V4__add_traceparent_to_ledger_outbox.sql` | New — `ALTER TABLE ledger_outbox_event ADD COLUMN traceparent VARCHAR(55)` |
| `account-service/.../entity/OutboxEvent.java` | Added `@Column(length=55) traceparent` field |
| `ledger-service/.../entity/LedgerOutboxEvent.java` | Added `@Column(length=55) traceparent` field |
| `account-service/.../service/TransferService.java` | Captures `Span.current().getSpanContext()`, writes W3C traceparent string to outbox row; also D-15 baggage-based transferId |
| `ledger-service/.../kafka/LedgerEventListener.java` | `@Header traceparent` param, child span via `W3CTraceContextPropagator`, captures outgoing traceparent for ledger outbox row |
| `payment-service/.../kafka/TransferConfirmationListener.java` | `@Header traceparent` param, child span wrapping business logic |
| `infra/debezium/outbox-connector.json` | `additional.placement` with `aggregateid:header:transaction-id,traceparent:header:traceparent` |
| `infra/debezium/ledger-outbox-connector.json` | `traceparent:header:traceparent` appended to existing placement |
| `ledger-service/.../config/KafkaConfig.java` | Added `setObservationEnabled(true)` to preserve OTel span context in custom factory |
| `ledger-service/.../resources/application.yml` | Added `listener.observation-enabled: true`, `template.observation-enabled: true`, `repair-on-migrate: true` |
| `payment-service/.../resources/application.yml` | Added `listener.observation-enabled: true`, `template.observation-enabled: true` |
| `compose.yml` | Added kafka health-check dependency and `SPRING_KAFKA_BOOTSTRAP_SERVERS` for payment-service |

### Deviations from Plan

- **[Blocker resolved]** Added `TransferState.FAILED` + two `TransferRepository` derived query methods to fix pre-existing `HungTransferDetector` compile errors (unblocked builds)
- **[Bug fixed]** Rewired `LedgerEventListener` from direct `KafkaTemplate.send()` to `LedgerOutboxEventRepository` CDC outbox pattern — required for traceparent to appear on the ledger outbox row as the design specifies
- **[Reconciled]** Pre-existing D-15 baggage-based transferId propagation in `TransferService` retained alongside traceparent capture (both serve different purposes)

---

## Task 5: Human Verification Checkpoint

Before considering this fully complete, verify end-to-end in Jaeger:

1. Start the full stack: `podman compose up -d`
2. Register both Debezium connectors (or `PUT /connectors/{name}/config` with the updated `additional.placement` if already registered)
3. POST a transfer via `payment-service`: `POST /api/payments/transfers`
4. Open Jaeger UI → search `payment-service` → find the trace
5. **Expected:** Single trace showing all 4 spans in a waterfall:
   - `payment-service` HTTP span (root)
   - `account-service` HTTP span (child)
   - `ledger-service` Kafka consumer span (child of account span)
   - `payment-service` Kafka consumer span (child of ledger span)
6. **Also check:** `banking.transaction.id` attribute is consistent across all 4 spans

If Jaeger shows separate root traces per service, check:
- Debezium connectors have `traceparent:header:traceparent` in `additional.placement`
- `LedgerEventListener` and `TransferConfirmationListener` have the `@Header(value="traceparent", required=false)` parameter
- `observation-enabled: true` is set in both `ledger-service` and `payment-service` application.yml
