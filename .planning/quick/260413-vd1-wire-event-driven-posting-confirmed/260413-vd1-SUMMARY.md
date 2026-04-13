---
quick_id: 260413-vd1
phase: quick
subsystem: saga
tags: [kafka, saga, event-driven, state-machine, testcontainers]
key-files:
  created:
    - payment-service/src/main/java/au/com/bankforge/payment/kafka/TransferConfirmationListener.java
    - payment-service/src/test/java/au/com/bankforge/payment/kafka/TransferConfirmationListenerIT.java
  modified:
    - ledger-service/src/main/java/au/com/bankforge/ledger/kafka/LedgerEventListener.java
    - ledger-service/src/test/java/au/com/bankforge/ledger/kafka/LedgerEventListenerIT.java
    - payment-service/pom.xml
    - payment-service/src/main/resources/application.yml
    - payment-service/src/main/java/au/com/bankforge/payment/service/TransferStateService.java
    - payment-service/src/main/java/au/com/bankforge/payment/service/PaymentService.java
    - payment-service/src/test/java/au/com/bankforge/payment/service/PaymentServiceTest.java
    - payment-service/src/test/java/au/com/bankforge/payment/controller/PaymentControllerIT.java
decisions:
  - "advanceToPosting() + confirm() replaces complete() — state machine split at POSTING boundary to make Saga real"
  - "spring.kafka.bootstrap-servers suppressed to localhost:29092 in PaymentServiceTest and PaymentControllerIT — no real Kafka needed for sync tests"
  - "TransferConfirmationListener uses REQUIRES_NEW via TransferStateService.confirm() — DLT captures unresolvable messages"
metrics:
  completed_date: "2026-04-13"
  tasks: 3
  files: 10
---

# Quick Task 260413-vd1: Wire Event-Driven POSTING→CONFIRMED via banking.transfer.confirmed

**One-liner:** Replaced inline auto-confirm stub with a real Saga loop — ledger-service publishes `banking.transfer.confirmed` after writing the double-entry pair; payment-service `@KafkaListener` consumes it and transitions `POSTING → CONFIRMED` asynchronously.

## What Was Implemented

### Task 1 — ledger-service: publish confirmation event

`LedgerEventListener` now injects `KafkaTemplate<String, String>` (via `@RequiredArgsConstructor`) and publishes to `banking.transfer.confirmed` after both ledger entries are saved. Key is the `transferId` UUID; payload is `{"transferId":"...","status":"CONFIRMED"}`.

`LedgerEventListenerIT` extended with a raw `KafkaConsumer` poll block after the existing entry-count assertion. Verifies the confirmation event arrives on `banking.transfer.confirmed` with the correct `transferId` key within 15 seconds.

### Task 2 — payment-service: Kafka dependency, state service split, listener

- `pom.xml`: added `spring-boot-starter-kafka` (main), `spring-kafka-test` + `testcontainers:kafka` + `awaitility` (test)
- `application.yml`: added `spring.kafka` consumer block — bootstrap-servers via `${SPRING_KAFKA_BOOTSTRAP_SERVERS:localhost:29092}`
- `TransferStateService.complete()` removed and replaced with:
  - `advanceToPosting()` — `PAYMENT_PROCESSING → PAYMENT_DONE → POSTING` (two transitions, two saves)
  - `confirm()` — `POSTING → CONFIRMED` (single transition, `REQUIRES_NEW`)
- `PaymentService.initiateTransfer()` updated: calls `advanceToPosting()`, tags metric with `POSTING`, stub comment block removed
- `TransferConfirmationListener` created in `au.com.bankforge.payment.kafka` package: `@KafkaListener` on `banking.transfer.confirmed`, `@RetryableTopic` with 4 attempts + exponential backoff + DLT, delegates to `TransferStateService.confirm()`. Uses `tools.jackson.databind.ObjectMapper` (Jackson 3).

### Task 3 — test updates

- `PaymentServiceTest`: added `spring.kafka.bootstrap-servers` suppression, renamed `initiateTransfer_happyPath_finalStateIsConfirmed` → `initiateTransfer_happyPath_finalStateIsPosting`, changed all `CONFIRMED` assertions to `POSTING`
- `PaymentControllerIT`: added Kafka suppression, changed both `"CONFIRMED"` string assertions to `"POSTING"`
- `TransferConfirmationListenerIT` (new): full end-to-end test with real `KafkaContainer`, `PostgreSQLContainer`, and Redis. Initiates transfer (asserts `POSTING`), then manually publishes `banking.transfer.confirmed`, then polls DB via Awaitility until state reaches `CONFIRMED`.

## Commits

| Hash | Message |
|------|---------|
| 82e7500 | feat(saga/260413-vd1): ledger-service publishes banking.transfer.confirmed after writing entries |
| 3a4bfa8 | feat(saga/260413-vd1): payment-service consumes banking.transfer.confirmed, splits state service |
| 3d539b8 | test(saga/260413-vd1): update sync tests to POSTING, add TransferConfirmationListenerIT |

## Deviations from Plan

None — plan executed exactly as written. The deprecation warning on `@RetryableTopic` annotation attributes is a Spring Kafka internal API note, identical to the pre-existing pattern in `LedgerEventListener`, and does not affect compilation or runtime.

## Known Stubs

None. The `banking.transfer.confirmed` consumer is fully wired — no placeholder data paths remain.

## Threat Surface Scan

No new external entry points introduced. `banking.transfer.confirmed` is an internal Kafka topic, not exposed externally. Threat mitigations from plan's threat model all present:
- T-vd1-01 (Tampering): `parseTransferId()` validates UUID format; missing field throws `IllegalArgumentException`; `@RetryableTopic` routes poison messages to DLT
- T-vd1-02 (Repudiation): `updatedAt` timestamp + INFO log on every `confirm()` call
- T-vd1-03 (DoS): 4 attempts, maxDelay=10s, DLT prevents infinite retry
- T-vd1-04 (EoP): no external entry point to `confirm()`; Kafka topic not externally exposed

## Self-Check: PASSED

- FOUND: payment-service/src/main/java/au/com/bankforge/payment/kafka/TransferConfirmationListener.java
- FOUND: payment-service/src/test/java/au/com/bankforge/payment/kafka/TransferConfirmationListenerIT.java
- FOUND: ledger-service/src/main/java/au/com/bankforge/ledger/kafka/LedgerEventListener.java
- FOUND commit 82e7500: feat(saga/260413-vd1): ledger-service publishes banking.transfer.confirmed
- FOUND commit 3a4bfa8: feat(saga/260413-vd1): payment-service consumes banking.transfer.confirmed
- FOUND commit 3d539b8: test(saga/260413-vd1): update sync tests to POSTING, add TransferConfirmationListenerIT
