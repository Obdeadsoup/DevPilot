FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /workspace

# Reactor 构建只复制 Maven 元数据与源码；本地 .env/Secret 永不进入镜像层。
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
RUN mvn -B -ntp -pl devpilot-boot -am dependency:go-offline

COPY contracts contracts
COPY devpilot-framework/src devpilot-framework/src
COPY devpilot-identity/src devpilot-identity/src
COPY devpilot-project/src devpilot-project/src
COPY devpilot-outbox/src devpilot-outbox/src
COPY devpilot-task/src devpilot-task/src
COPY devpilot-github/src devpilot-github/src
COPY devpilot-notification/src devpilot-notification/src
COPY devpilot-audit/src devpilot-audit/src
COPY devpilot-agent/src devpilot-agent/src
COPY devpilot-boot/src devpilot-boot/src
RUN mvn -B -ntp -pl devpilot-boot -am package -DskipTests

FROM eclipse-temurin:21-jre-noble AS runtime
RUN apt-get update \
    && apt-get install --no-install-recommends -y curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system devpilot \
    && useradd --system --gid devpilot --home-dir /app --shell /usr/sbin/nologin devpilot
WORKDIR /app
COPY --from=build --chown=devpilot:devpilot /workspace/devpilot-boot/target/devpilot-boot-0.0.1-SNAPSHOT.jar app.jar
USER devpilot
EXPOSE 8080 50052
HEALTHCHECK --interval=15s --timeout=5s --start-period=45s --retries=10 \
  CMD curl --fail --silent http://127.0.0.1:8080/actuator/health/readiness || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

