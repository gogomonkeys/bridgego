# Temporary independent maintenance image only. Original game tree remains untouched.
FROM maven:3.9.11-eclipse-temurin-17@sha256:e4a7ace3dc0d645ed97f8d9ad0b0d3f0b14fa8d150138f27f116d7105a639b82 AS maintenance-build
WORKDIR /build
COPY maintenance-c/pom.xml ./pom.xml
COPY maintenance-c/src/main ./src/main
RUN mvn -B -ntp -DskipTests package

# Reviewed official image-index digest. Record actual selected platform/image at deployment.
FROM eclipse-temurin:17.0.21_9-jre-jammy@sha256:aa6381384f8db99762fa7d0d3c2b5de58874e5f12c96df4c13a1455376ae0ed1
WORKDIR /opt/bridgego-maintenance
COPY --from=maintenance-build /build/target/maintenance-c-1.0.jar target/maintenance-c-1.0.jar
COPY maintenance-c/start-maintenance.sh ./start-maintenance.sh
RUN chmod 0555 start-maintenance.sh && chmod 0444 target/maintenance-c-1.0.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["/bin/sh", "/opt/bridgego-maintenance/start-maintenance.sh"]
