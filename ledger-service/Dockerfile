# Multi-stage build: JDK 21 builder + JRE 21 runtime
# Build context must be repo root (docker build -f ledger-service/Dockerfile .)

# ---- Build stage ----
FROM eclipse-temurin:21-jdk-jammy AS builder
WORKDIR /build

# Copy POMs first for dependency layer caching
COPY pom.xml ./
COPY common/pom.xml common/
COPY ledger-service/pom.xml ledger-service/

# Pre-fetch dependencies (cached until pom.xml changes)
RUN mvn -B dependency:go-offline -pl ledger-service -am -Dmaven.test.skip=true || true

# Copy source
COPY common/src common/src
COPY ledger-service/src ledger-service/src

# Package (tests run in CI job, not in docker build)
RUN mvn -B package -pl ledger-service -am -Dmaven.test.skip=true

# ---- Runtime stage ----
FROM eclipse-temurin:21-jre-jammy AS runtime
WORKDIR /app

RUN addgroup --system appgroup && adduser --system --ingroup appgroup appuser

COPY --from=builder /build/ledger-service/target/*.jar app.jar
RUN chown appuser:appgroup app.jar

USER appuser
EXPOSE 8080

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
