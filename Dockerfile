# syntax=docker/dockerfile:1
# Production image: API and web UI in one layered Spring Boot app, running as an unprivileged user.
#
#   docker build -t agentic-sdlc .
#
# Tests are not run here (they need Docker and Testcontainers); CI runs `./mvnw verify` before building the image.
# See "Deployment" in README.md for the Docker access and workspace volume the container needs.

FROM node:24.21.0-alpine AS ui
WORKDIR /src/ui
COPY ui/package.json ui/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY ui/ ./
RUN UI_OUT_DIR=/out/static npm run build

FROM eclipse-temurin:25.0.4_7-jdk-noble AS build
WORKDIR /src
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY core/ core/
COPY app/ app/
COPY --from=ui /out/static/ app/src/main/resources/static/
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -ntp -DskipTests -Djacoco.skip=true package \
    && java -Djarmode=tools -jar app/target/app-*.jar extract --layers --launcher --destination /extracted

FROM eclipse-temurin:25.0.4_7-jre-noble
# A fixed non-root uid: it owns the workspace volume, and sandboxes run as the workspace owner (never root).
RUN groupadd --system --gid 10001 agentic \
    && useradd --system --uid 10001 --gid agentic --home-dir /home/agentic --create-home agentic \
    && mkdir -p /var/lib/agentic/workspaces \
    && chown -R agentic:agentic /var/lib/agentic
WORKDIR /app
COPY --from=build /extracted/dependencies/ ./
COPY --from=build /extracted/spring-boot-loader/ ./
COPY --from=build /extracted/snapshot-dependencies/ ./
COPY --from=build /extracted/application/ ./
USER 10001:10001
ENV SPRING_PROFILES_ACTIVE=prod \
    AGENTIC_SANDBOX_WORKSPACEROOT=/var/lib/agentic/workspaces
# 8080: API and UI. 8081: actuator (health probes, Prometheus) in the prod profile; keep it internal.
EXPOSE 8080 8081
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "org.springframework.boot.loader.launch.JarLauncher"]
