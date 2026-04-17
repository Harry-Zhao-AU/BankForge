-- V4: Add traceparent column to ledger_outbox_event for W3C trace context propagation.
--
-- The W3C traceparent string (format: 00-<32-hex-traceId>-<16-hex-spanId>-<2-hex-flags>)
-- is written by ledger-service's LedgerEventListener at the point the outbox row is created.
-- Debezium's Outbox Event Router SMT lifts this column into a Kafka message header via
-- transforms.outbox.table.fields.additional.placement=traceparent:header:traceparent.
-- Payment-service reads that header to reconstruct the remote SpanContext and create a
-- child span under the originating payment-service trace (one continuous trace in Jaeger).
--
-- Column is nullable: existing rows and events published before this migration
-- degrade gracefully (null header -> consumer starts a new root trace, no regression).
--
-- NOTE: ledger-service V3 was already taken by V3__add_ledger_outbox.sql -- this is V4.

ALTER TABLE ledger_outbox_event ADD COLUMN traceparent VARCHAR(55);
