# syntax=docker/dockerfile:1

# ----------------------
#  Dockerfile
#  Description: Builds the frontend bundle and the Spring bridge, then ships them as one unprivileged jar image.
#  Author: suinevere
#  Dependencies: node:22-bookworm-slim, eclipse-temurin:25-jdk-noble, eclipse-temurin:25-jre-noble
#  Globals: N/A
# ----------------------

# The bundle is always built here, never copied from the host, so a stale local dist cannot ship.
FROM node:22-bookworm-slim AS frontend
WORKDIR /frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
# `npm test` carries the guard that keeps the Buffer polyfill first in the import chain.
RUN npm test && npm run build

# The npm tasks are excluded because the bundle above already satisfies processResources.
FROM eclipse-temurin:25-jdk-noble AS jvm
WORKDIR /workspace
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle/ gradle/
COPY src/ src/
COPY --from=frontend /frontend/dist/ frontend/dist/
RUN --mount=type=cache,target=/root/.gradle \
    chmod +x gradlew \
    && ./gradlew --no-daemon -x npmInstall -x npmBuild bootJar \
    && find build/libs -name '*.jar' ! -name '*-plain.jar' -exec cp {} /workspace/app.jar \;

FROM eclipse-temurin:25-jre-noble
RUN useradd --system --uid 1001 --user-group --home-dir /nonexistent \
      --shell /usr/sbin/nologin terminal

COPY --from=jvm /workspace/app.jar /app/app.jar

USER terminal
EXPOSE 8080

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/app.jar"]
