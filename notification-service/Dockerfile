# Multi-stage build: Maven+JDK 21 builder + JRE 21 runtime
# Build context must be repo root (docker build -f notification-service/Dockerfile .)

# ---- Build stage ----
FROM maven:3.9-eclipse-temurin-21-jammy AS builder
WORKDIR /build

# Copy POMs first for dependency layer caching
COPY pom.xml ./
COPY common/pom.xml common/
COPY notification-service/pom.xml notification-service/

# Pre-fetch dependencies (cached until pom.xml changes)
RUN mvn -B dependency:go-offline -pl notification-service -am -Dmaven.test.skip=true || true

# Copy source
COPY common/src common/src
COPY notification-service/src notification-service/src

# Package (tests run in CI job, not in docker build)
RUN mvn -B package -pl notification-service -am -Dmaven.test.skip=true

# ---- Runtime stage ----
FROM eclipse-temurin:21-jre-jammy AS runtime
WORKDIR /app

RUN addgroup --system appgroup && adduser --system --ingroup appgroup appuser

COPY --from=builder /build/notification-service/target/*.jar app.jar
RUN chown appuser:appgroup app.jar

USER appuser
EXPOSE 8080

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
