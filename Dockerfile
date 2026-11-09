# Temporary isolated synthetic rehearsal. Normal game is restored after supervised testing.
FROM maven:3.10.0-eclipse-temurin-17 AS backend
WORKDIR /build/backend
COPY backend/ ./
RUN mvn -B -Psynthetic-cleanup-supervised clean package

FROM eclipse-temurin:17-jre-jammy AS runtime
RUN groupadd --system bridgego && useradd --system --gid bridgego --home-dir /app bridgego
WORKDIR /app
COPY --from=backend --chown=bridgego:bridgego /build/backend/target/bridgego-synthetic-supervised.jar ./supervised.jar
ENV JAVA_TOOL_OPTIONS="-Xms32m -Xmx192m -XX:MaxMetaspaceSize=128m -XX:+ExitOnOutOfMemoryError"
USER bridgego
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/supervised.jar"]
