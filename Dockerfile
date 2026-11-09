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

FROM eclipse-temurin:17-jre-jammy AS runtime
RUN groupadd --system bridgego && useradd --system --gid bridgego --home-dir /app bridgego
WORKDIR /app
COPY --from=backend --chown=bridgego:bridgego /build/backend/target/bridgego-server.jar ./bridgego-server.jar
# Leave native/thread/direct-buffer overhead within a 512 MB service; verify real load before launch.
ENV JAVA_TOOL_OPTIONS="-Xms32m -Xmx256m -XX:MaxMetaspaceSize=128m -XX:+ExitOnOutOfMemoryError"
ENV SPRING_PROFILES_ACTIVE=prod
USER bridgego
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/bridgego-server.jar"]
