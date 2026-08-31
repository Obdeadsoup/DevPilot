FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /workspace

COPY pom.xml ./
COPY devpilot-framework/pom.xml devpilot-framework/pom.xml
COPY devpilot-identity/pom.xml devpilot-identity/pom.xml
COPY devpilot-project/pom.xml devpilot-project/pom.xml
COPY devpilot-outbox/pom.xml devpilot-outbox/pom.xml
COPY devpilot-task/pom.xml devpilot-task/pom.xml
COPY devpilot-github/pom.xml devpilot-github/pom.xml
COPY devpilot-notification/pom.xml devpilot-notification/pom.xml
COPY devpilot-audit/pom.xml devpilot-audit/pom.xml
COPY devpilot-agent/pom.xml devpilot-agent/pom.xml
COPY devpilot-gateway/pom.xml devpilot-gateway/pom.xml
COPY devpilot-boot/pom.xml devpilot-boot/pom.xml
RUN mvn -B -ntp -pl devpilot-gateway -am dependency:go-offline

COPY devpilot-framework/src devpilot-framework/src
COPY devpilot-gateway/src devpilot-gateway/src
RUN mvn -B -ntp -pl devpilot-gateway -am package -DskipTests

FROM eclipse-temurin:21-jre-noble AS runtime
RUN apt-get update \
    && apt-get install --no-install-recommends -y curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system devpilot \
    && useradd --system --gid devpilot --home-dir /app --shell /usr/sbin/nologin devpilot
WORKDIR /app
COPY --from=build --chown=devpilot:devpilot /workspace/devpilot-gateway/target/devpilot-gateway-0.0.1-SNAPSHOT.jar app.jar
USER devpilot
EXPOSE 8081
HEALTHCHECK --interval=15s --timeout=5s --start-period=30s --retries=10 \
  CMD curl --fail --silent http://127.0.0.1:8081/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

