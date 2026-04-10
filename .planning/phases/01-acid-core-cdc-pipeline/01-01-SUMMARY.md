---
phase: 01-acid-core-cdc-pipeline
plan: "01"
subsystem: infra
tags: [spring-boot, maven, java21, postgresql, redis, podman-compose, flyway, mapstruct, lombok, statemachine, bsb-validation]

# Dependency graph
requires: []
provides:
  - Maven multi-module project (bankforge-parent) with Spring Boot 4.0.5 + Java 21
  - common module: TransferState/TransferEvent enums, TransferStateMachine @Component, BsbValidator, DTOs
  - 4 service @SpringBootApplication stubs (account, payment, ledger, notification)
  - compose.yml: 4x postgres:17-alpine with wal_level=logical + Redis 7.2-alpine
  - Dockerfiles using eclipse-temurin:21-jre-alpine for all 4 services
  - application.yml for all 4 services with virtual threads, Flyway, JPA config
  - Flyway db/migration directories stubbed for Plan 02
affects:
  - 01-02 (account service and payment service depend on common module + Flyway dirs)
  - 01-03 (payment service idempotency uses Redis config from compose.yml)
  - 01-04 (Compose stack + WAL config is tested live in this plan)
  - 01.1 (Debezium CDC needs wal_level=logical set from this plan)

# Tech tracking
tech-stack:
  added:
    - Spring Boot 4.0.5 (Spring Framework 7.0.6, Hibernate 7, Flyway 11.14.1, Jackson 3)
    - Java 21 JBR via IntelliJ Community Edition 2025.2.4
    - Maven 3.9.9 (bundled in IntelliJ)
    - MapStruct 1.6.3
    - Lombok 1.18.44 (via Spring Boot BOM)
    - Resilience4j 2.4.0 (resilience4j-spring-boot4)
    - Testcontainers BOM 1.20.4
    - postgres:17-alpine
    - redis:7.2-alpine
    - eclipse-temurin:21-jre-alpine
  patterns:
    - Enum-based FSM (TransferStateMachine with immutable static transition table)
    - Jakarta Bean Validation custom constraint (@ValidBsb / BsbValidator)
    - Maven multi-module: common=jar library (no spring-boot-maven-plugin), services=fat JAR
    - Lombok-first annotation processor ordering (lombok → lombok-mapstruct-binding → mapstruct-processor)
    - PostgreSQL containers with wal_level=logical + max_replication_slots=5 (Debezium-ready)
    - service_healthy depends_on for infrastructure ordering in Compose

key-files:
  created:
    - pom.xml (parent POM with Spring Boot 4.0.5, dependency management)
    - common/pom.xml (jar packaging, NO spring-boot-maven-plugin)
    - account-service/pom.xml, payment-service/pom.xml, ledger-service/pom.xml, notification-service/pom.xml
    - common/src/main/java/au/com/bankforge/common/enums/TransferState.java
    - common/src/main/java/au/com/bankforge/common/enums/TransferEvent.java
    - common/src/main/java/au/com/bankforge/common/statemachine/TransferStateMachine.java
    - common/src/main/java/au/com/bankforge/common/statemachine/InvalidStateTransitionException.java
    - common/src/main/java/au/com/bankforge/common/validation/ValidBsb.java
    - common/src/main/java/au/com/bankforge/common/validation/BsbValidator.java
    - common/src/main/java/au/com/bankforge/common/dto/TransferCreatedEvent.java
    - common/src/main/java/au/com/bankforge/common/dto/AccountCreatedEvent.java
    - common/src/test/java/au/com/bankforge/common/statemachine/TransferStateMachineTest.java
    - common/src/test/java/au/com/bankforge/common/validation/BsbValidatorTest.java
    - compose.yml
    - account-service/Dockerfile, payment-service/Dockerfile, ledger-service/Dockerfile, notification-service/Dockerfile
    - account-service/src/main/resources/application.yml (+ payment, ledger, notification)
    - account-service/src/main/java/au/com/bankforge/account/AccountServiceApplication.java (+ payment, ledger, notification)
  modified: []

key-decisions:
  - "Hand-rolled enum FSM used instead of Spring State Machine (incompatible with Spring Boot 4 / Spring Framework 7)"
  - "PAYMENT_DONE + FAIL transition added to handle exception in PaymentService catch block between PAYMENT_COMPLETE and POST"
  - "notification-service has no DB dependency in Phase 1 — stub only; Kafka consumer wired in Phase 1.1"
  - "Java 21 and Maven 3.9.9 sourced from IntelliJ Community Edition 2025.2.4 bundled JBR and Maven plugin"
  - ".gitignore added to exclude Maven target/ directories from version control"

patterns-established:
  - "Pattern: Enum FSM — TransferStateMachine.transition(state, event) throws InvalidStateTransitionException on illegal transitions"
  - "Pattern: BSB validation — @ValidBsb annotation applied to all BSB fields; null rejection included"
  - "Pattern: BigDecimal monetary fields — TransferCreatedEvent.amount is BigDecimal, not double/float"
  - "Pattern: Flyway via starter — spring-boot-starter-flyway + flyway-database-postgresql (runtime) required for SB4 auto-config"
  - "Pattern: WAL configuration — wal_level=logical via command: args in compose.yml (cannot be set via POSTGRES_INITDB_ARGS)"

requirements-completed: [CORE-01, CORE-02, CORE-03, TXNS-04]

# Metrics
duration: 13min
completed: "2026-04-10"
---

# Phase 01 Plan 01: Maven Scaffold + Common Module + Compose Infrastructure Summary

**Maven 5-module project with Spring Boot 4.0.5, enum-based 7-state transfer FSM, BSB validation (26 tests passing), and Podman Compose stack with 4x PostgreSQL 17 (wal_level=logical) + Redis 7.2**

## Performance

- **Duration:** ~13 min
- **Started:** 2026-04-10T06:00:57Z
- **Completed:** 2026-04-10T06:14:06Z
- **Tasks:** 3
- **Files created:** 29

## Accomplishments

- Maven multi-module project compiles end-to-end with Spring Boot 4.0.5 (`mvn compile -q` exits 0 across all 5 modules)
- Common module unit tests pass: 9 TransferStateMachineTest + 17 BsbValidatorTest (26 total, `mvn test -pl common -q` exits 0)
- All 7 transfer states correctly modelled (PENDING → PAYMENT_PROCESSING → PAYMENT_DONE → POSTING → CONFIRMED; COMPENSATING → CANCELLED)
- PAYMENT_DONE + FAIL transition explicitly added for compensation edge case (exception in catch block between PAYMENT_COMPLETE and POST)
- compose.yml defines 4 isolated PostgreSQL databases with `wal_level=logical` and Redis — Debezium-ready without DB restart in Phase 1.1

## Task Commits

Each task was committed atomically:

1. **Task 1: Maven multi-module scaffold** - `33d6c7a` (feat)
2. **Task 2: Common module — FSM, validation, DTOs, tests** - `2947701` (feat)
3. **Task 3: Compose, Dockerfiles, application.yml, app stubs** - `c5bc9c1` (feat)

## Files Created/Modified

- `pom.xml` — Parent POM: Spring Boot 4.0.5, MapStruct 1.6.3, Resilience4j 2.4.0, Testcontainers BOM, Lombok annotation processor ordering
- `common/pom.xml` — Library jar, NO spring-boot-maven-plugin
- `account-service/pom.xml`, `payment-service/pom.xml`, `ledger-service/pom.xml`, `notification-service/pom.xml` — Service fat JAR POMs
- `common/src/.../enums/TransferState.java` — 7-state enum (banking terminology per D-06)
- `common/src/.../enums/TransferEvent.java` — 7-event enum
- `common/src/.../statemachine/TransferStateMachine.java` — Immutable transition table, @Component
- `common/src/.../statemachine/InvalidStateTransitionException.java` — RuntimeException with clear message
- `common/src/.../validation/ValidBsb.java` — Jakarta @Constraint annotation
- `common/src/.../validation/BsbValidator.java` — Regex `^\d{3}-\d{3}$`, null rejection
- `common/src/.../dto/TransferCreatedEvent.java` — Java record with BigDecimal amount
- `common/src/.../dto/AccountCreatedEvent.java` — Java record
- `common/src/test/.../TransferStateMachineTest.java` — 9 JUnit 5 tests
- `common/src/test/.../BsbValidatorTest.java` — 17 JUnit 5 tests (valid + invalid + null)
- `compose.yml` — 4x postgres:17-alpine + redis:7.2-alpine + 4 service build contexts
- `account-service/Dockerfile` et al. — eclipse-temurin:21-jre-alpine
- `account-service/src/main/resources/application.yml` et al. — virtual threads, Flyway, JPA
- `*/src/main/java/.../Application.java` — @SpringBootApplication stubs with scanBasePackages

## Decisions Made

- **Enum FSM over Spring State Machine:** Spring State Machine 4.0.1 (latest) targets Spring Framework 6.2.x / Spring Boot 3.5.x only — incompatible with Spring Boot 4. Enum FSM is the idiomatic substitute per Research.
- **PAYMENT_DONE + FAIL transition:** Critical for PaymentService catch-block compensation. Without this, an exception thrown after PAYMENT_COMPLETE but before POST would crash the compensation path with InvalidStateTransitionException.
- **Java/Maven from IntelliJ bundled tools:** JBR 21.0.8 + Maven 3.9.9 found in IntelliJ Community Edition 2025.2.4 installation. No separate JDK/Maven installation needed.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 2 - Missing Critical] Added .gitignore**
- **Found during:** Task 3 (after `mvn compile` generated target/ directories)
- **Issue:** No .gitignore existed; Maven target/ directories would pollute git status
- **Fix:** Created .gitignore with Maven target/, IDE, and log exclusions
- **Files modified:** `.gitignore`
- **Verification:** `git status --short` no longer shows target/ directories
- **Committed in:** `c5bc9c1` (Task 3 commit)

---

**Total deviations:** 1 auto-fixed (1 missing critical)
**Impact on plan:** Minor housekeeping. No scope creep.

## Issues Encountered

- **Java/Maven not in PATH:** Neither Java nor Maven were on the system PATH. Resolved by discovering IntelliJ Community Edition 2025.2.4 bundled JBR (21.0.8) and Maven 3.9.9 in the IntelliJ plugins directory. All subsequent commands used explicit JAVA_HOME and MAVEN_HOME exports.

## Known Stubs

- `account-service/src/main/resources/db/migration/.gitkeep` — Flyway migration directory placeholder; actual V1__ migration created in Plan 02
- `payment-service/src/main/resources/db/migration/.gitkeep` — Same as above
- `ledger-service/src/main/resources/db/migration/.gitkeep` — Same as above
- `*/Application.java` — @SpringBootApplication stubs; actual controllers/services added in Plans 02-03

These stubs are intentional. All Plans 02+ depend on these stubs being present; actual content added in subsequent plans.

## User Setup Required

None — no external service configuration required. `podman-compose up` is tested in Plan 04 after services are fully built. Confirm `pip install podman-compose` if running compose manually.

## Next Phase Readiness

**Ready for Plan 02 (account-service implementation):**
- Common module available as a dependency: `au.com.bankforge:common:0.1.0-SNAPSHOT`
- TransferStateMachine bean available for injection via `@Autowired`
- Flyway migration directories exist and are on classpath
- compose.yml account-db ready (wal_level=logical, health check, port 5432)
- application.yml configured for account-service (ddl-auto=validate, Flyway enabled)

**Concerns:**
- Flyway requires at least one migration script before any service can start (ddl-auto=validate will fail with no tables). Plan 02 must add V1 migrations before integration tests can run.

---
*Phase: 01-acid-core-cdc-pipeline*
*Completed: 2026-04-10*
