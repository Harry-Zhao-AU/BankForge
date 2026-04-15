# Multi-stage build: Maven+JDK 21 builder + JRE 21 runtime
# Build context must be repo root (docker build -f account-service/Dockerfile .)

# ---- Build stage ----
FROM maven:3.9-eclipse-temurin-21-jammy AS builder
WORKDIR /build

# Copy POMs first for dependency layer caching
# All sibling POMs needed so Maven reactor validation passes (parent POM lists all modules)
COPY pom.xml ./
COPY common/pom.xml common/
COPY account-service/pom.xml account-service/
COPY payment-service/pom.xml payment-service/
COPY ledger-service/pom.xml ledger-service/
COPY notification-service/pom.xml notification-service/

# Pre-fetch dependencies (cached until pom.xml changes)
RUN mvn -B dependency:go-offline -pl account-service -am -Dmaven.test.skip=true || true

# Copy source
COPY common/src common/src
COPY account-service/src account-service/src

# Package (tests run in CI job, not in docker build)
RUN mvn -B package -pl account-service -am -Dmaven.test.skip=true

# ---- Runtime stage ----
FROM eclipse-temurin:21-jre-jammy AS runtime
WORKDIR /app

RUN addgroup --system appgroup && adduser --system --ingroup appgroup appuser

COPY --from=builder /build/account-service/target/*.jar app.jar
RUN chown appuser:appgroup app.jar

USER appuser
EXPOSE 8080

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
