---
phase: quick
plan: 260416-u6y
subsystem: payment-service
tags: [metrics, micrometer, prometheus, observability]
dependency_graph:
  requires: []
  provides: [transfer_initiated_total CONFIRMED, transfer_initiated_total FAILED, transfer_initiated_total CANCELLED]
  affects: [payment-service observability, Grafana transfer funnel dashboard]
tech_stack:
  added: []
  patterns: [MeterRegistry constructor injection, Counter.builder pattern]
key_files:
  modified:
    - payment-service/src/main/java/au/com/bankforge/payment/kafka/TransferConfirmationListener.java
    - payment-service/src/main/java/au/com/bankforge/payment/detector/HungTransferDetector.java
    - payment-service/src/test/java/au/com/bankforge/payment/detector/HungTransferDetectorTest.java
decisions:
  - MeterRegistry injected via Lombok @RequiredArgsConstructor (same pattern as PaymentService) — no explicit @Bean wiring needed
  - SimpleMeterRegistry used in unit test (not @Mock) so counter.count() assertions work against real in-memory registry
  - CANCELLED metric placed after cancel() and before kafkaTemplate.send() so it is not incremented on exception path
metrics:
  duration: ~5 min
  completed: 2026-04-16
  tasks: 2
  files: 3
---

# Quick 260416-u6y: Add transfer_initiated_total Metrics for All Transfer States

MeterRegistry injected into TransferConfirmationListener and HungTransferDetector; counter increments added for CONFIRMED, FAILED, and CANCELLED states so the full transfer funnel is observable in Prometheus/Grafana.

## What Was Done

### Task 1: Add CONFIRMED/FAILED/CANCELLED metric increments to production classes

**TransferConfirmationListener.java:**
- Added `import io.micrometer.core.instrument.Counter` and `import io.micrometer.core.instrument.MeterRegistry`
- Added `import au.com.bankforge.common.enums.TransferState`
- Added `private final MeterRegistry meterRegistry` field (Lombok @RequiredArgsConstructor handles injection)
- Added `incrementTransferInitiated(TransferState state)` helper (identical pattern to PaymentService)
- Called `incrementTransferInitiated(TransferState.CONFIRMED)` after `transferStateService.confirm(transferId)`

**HungTransferDetector.java:**
- Added `import io.micrometer.core.instrument.Counter` and `import io.micrometer.core.instrument.MeterRegistry`
- Added `private final MeterRegistry meterRegistry` field
- Added `incrementTransferInitiated(TransferState state)` helper
- Called `incrementTransferInitiated(TransferState.CANCELLED)` after `transferStateService.cancel()` in `resolveHungPaymentProcessing()`
- Called `incrementTransferInitiated(TransferState.FAILED)` after `transferRepository.save(transfer)` in `resolveHungPosting()`

### Task 2: Update HungTransferDetectorTest with MeterRegistry and metric assertions

- Added `import io.micrometer.core.instrument.MeterRegistry` and `import io.micrometer.core.instrument.simple.SimpleMeterRegistry`
- Added `private MeterRegistry meterRegistry` field (non-mock, real SimpleMeterRegistry)
- setUp(): `meterRegistry = new SimpleMeterRegistry()` before constructor call
- Constructor updated to 4-arg: `new HungTransferDetector(transferRepository, transferStateService, kafkaTemplate, meterRegistry)`
- `shouldCancelHungPaymentProcessingTransfers()`: asserts `state=CANCELLED` counter = 1.0
- `shouldMarkHungPostingAsFailed()`: asserts `state=FAILED` counter = 1.0
- `shouldDoNothingWhenNoHungTransfers()`: asserts `transfer_initiated_total` counters collection is empty

All 3 tests pass.

## Commits

| Hash | Message |
|------|---------|
| 7995b13 | feat(quick-260416-u6y): add transfer_initiated_total counters for CONFIRMED, FAILED, CANCELLED states |

## Deviations from Plan

**1. [Rule 3 - Blocking] Reinstalled common module to Maven local repo**
- **Found during:** Task 1 compilation
- **Issue:** `TransferState.FAILED` compiled as "cannot find symbol" because the installed `common-0.1.0-SNAPSHOT.jar` in `~/.m2` predated the FAILED enum value added in a prior quick task
- **Fix:** Ran `mvn install` on common module to refresh the local repo jar
- **Files modified:** None (build only)

## Self-Check

- [x] `TransferConfirmationListener.java` modified with MeterRegistry field + incrementTransferInitiated helper + CONFIRMED increment
- [x] `HungTransferDetector.java` modified with MeterRegistry field + incrementTransferInitiated helper + CANCELLED and FAILED increments
- [x] `HungTransferDetectorTest.java` updated with SimpleMeterRegistry, 4-arg constructor, metric assertions
- [x] Commit 7995b13 exists
- [x] All 3 HungTransferDetectorTest methods pass (Tests run: 3, Failures: 0, Errors: 0)

## Self-Check: PASSED
