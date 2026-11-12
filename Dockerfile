# Official multi-stage images. Refresh/review tags before public production rollout.
FROM node:24-bookworm-slim AS frontend
WORKDIR /build/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --ignore-scripts
COPY frontend/ ./
RUN npm run build

FROM maven:3.10.0-eclipse-temurin-17 AS backend
WORKDIR /build/backend
COPY backend/ ./
COPY --from=frontend /build/frontend/dist /build/frontend/dist
RUN test -f ../frontend/dist/index.html && test -f ../frontend/dist/sw.js && mvn -B -Pweb clean package

FROM maven:3.10.0-eclipse-temurin-17 AS readonly-helper
WORKDIR /build/readonly-helper
COPY readonly-helper/ ./
RUN mvn -B clean package

FROM eclipse-temurin:17-jre-jammy AS runtime
RUN groupadd --system bridgego && useradd --system --gid bridgego --home-dir /app bridgego
WORKDIR /app
COPY --from=backend --chown=bridgego:bridgego /build/backend/target/bridgego-server.jar ./bridgego-server.jar
COPY --from=readonly-helper --chown=bridgego:bridgego /build/readonly-helper/target/bridgego-production-readonly-startup.jar ./bridgego-production-readonly-startup.jar
COPY --chown=bridgego:bridgego ops/start-managed.sh ./start-managed.sh
RUN chmod 0555 /app/start-managed.sh && command -v timeout && command -v bash
ENV BRIDGE_READONLY_STARTUP_ENABLED="false"
# Leave native/thread/direct-buffer overhead within a 512 MB service; verify real load before launch.
ENV JAVA_TOOL_OPTIONS="-Xms32m -Xmx256m -XX:MaxMetaspaceSize=128m -XX:+ExitOnOutOfMemoryError"
ENV SPRING_PROFILES_ACTIVE=prod
USER bridgego
EXPOSE 8080
ENTRYPOINT ["/app/start-managed.sh"]
