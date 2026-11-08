# Phase 1 read-only service only. Building this file does not authorize deployment.
FROM maven:3.10.0-eclipse-temurin-17 AS backend
WORKDIR /build/backend
COPY backend/ ./
RUN mvn -B -Psynthetic-cleanup-preflight clean package

FROM eclipse-temurin:17-jre-jammy AS runtime
RUN groupadd --system bridgego && useradd --system --gid bridgego --home-dir /app bridgego
WORKDIR /app
COPY --from=backend --chown=bridgego:bridgego /build/backend/target/bridgego-synthetic-preflight.jar ./preflight.jar
ENV JAVA_TOOL_OPTIONS="-Xms32m -Xmx192m -XX:MaxMetaspaceSize=128m -XX:+ExitOnOutOfMemoryError"
USER bridgego
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/preflight.jar"]
