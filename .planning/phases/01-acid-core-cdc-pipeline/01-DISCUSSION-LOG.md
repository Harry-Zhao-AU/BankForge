# Phase 1 Discussion Log

**Date:** 2026-04-10
**Areas discussed:** Spring Boot version, Transfer API entry point, State machine approach, Build organisation

---

## Spring Boot Version

**Q:** Which Spring Boot version should Phase 1 target?
**Options:** Spring Boot 4.0.x / Spring Boot 3.4.x / You decide based on GA status
**Answer:** Spring Boot 4.0.x
**Note:** Researcher must verify exact GA patch before writing pom.xml. Fall back to 3.4.x if 4.0.x is not stable.

---

## Transfer API Entry Point

**Q:** Where does a transfer request enter the system?
**Options:** account-service owns transfers / payment-service as entry point / Both — payment-service delegates
**Answer:** account-service owns the ACID TX (debit+credit+outbox)

**Q:** What does payment-service own in Phase 1?
**Options:** Transfer orchestration + idempotency / NPP simulation stub only / Same as account-service
**Answer:** Transfer orchestration + idempotency (Redis, state machine)

**Q:** Actual call sequence?
**Options:** Client → payment-service → account-service / Client → account-service directly
**Answer:** Client → payment-service → account-service
**Note:** account-service is internal-only. payment-service is the external-facing API.

---

## State Machine Approach

**Q:** FSM implementation?
**Options:** Spring State Machine library / Hand-rolled enum FSM
**Answer:** Spring State Machine library

**Q:** What replaces STOCK_RESERVING?
**User clarification requested:** "I do not understand this, explain to me"
**Explanation given:** STOCK_RESERVING is e-commerce terminology. In banking, after payment is done, ledger-service records double-entry bookkeeping — that act is called "posting". Real-world Australian banking states: PENDING → PAYMENT_PROCESSING → PAYMENT_DONE → POSTING → CONFIRMED.
**User:** "prefer real-world state name"
**Answer:** POSTING

---

## Build Organisation

**Q:** How should the 4 services be organised as Maven projects?
**Options:** Maven multi-module / Independent Maven projects
**Answer:** Maven multi-module (one parent POM, 4 service child modules)

**Q:** Should there be a shared `common` module?
**Options:** Yes — common module for shared types / No — each service owns its own types
**Answer:** Yes — common module for Kafka event DTOs, TransferState enum, shared validation

**User clarification:** "Does the Maven multi-module support deploy the 4 isolation services like microservice?"
**Explanation given:** Multi-module is build-time only. Each service produces its own fat JAR and runs as an independent container. The `common` module is embedded in each fat JAR at build time — not a runtime service. Full deployment isolation is maintained.
**User confirmed:** Yes, ready to create context.

---

*Log generated: 2026-04-10*
