# syntax=docker/dockerfile:1
#
# Multi-stage, so a reviewer needs Docker and nothing else — no JDK, no Gradle.
# The build happens in the first stage and is thrown away; only a JRE and the
# application reach the final image.

# ----------------------------------------------------------------------------
# Build. Uses the Gradle wrapper rather than a gradle base image, so the version
# that builds here is byte-for-byte the one pinned in gradle-wrapper.properties.
# ----------------------------------------------------------------------------
FROM eclipse-temurin:21-jdk AS build
WORKDIR /build

# Build scripts first, sources second, so a source-only change does not invalidate
# this layer. Dependency downloads are served by the BuildKit cache mount below, which
# is machine-local - it is not in the image and does not exist on a clean clone or in
# CI, where the first build downloads everything.
#
# chmod because .gitattributes does not set the executable bit on gradlew.
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle/ gradle/
RUN chmod +x ./gradlew

COPY src/ src/
# Tests are deliberately not run here. They use Testcontainers, which needs a
# Docker daemon this build does not have — running them would mean mounting the
# host socket into an image build, which is a much worse idea than running the
# suite where it belongs, in CI and on a developer machine.
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon bootJar -x test

# ----------------------------------------------------------------------------
# Split the jar into layers ordered by how often they change.
# ----------------------------------------------------------------------------
FROM eclipse-temurin:21-jre AS extract
WORKDIR /extract
COPY --from=build /build/build/libs/*.jar fat.jar
# Into a subdirectory: extract refuses a destination that is not empty, and the
# jar it is reading from is sitting in this one.
RUN java -Djarmode=tools -jar fat.jar extract \
        --layers --destination layers --application-filename app.jar

# ----------------------------------------------------------------------------
# Runtime.
# ----------------------------------------------------------------------------
FROM eclipse-temurin:21-jre AS runtime

# curl is here only so Compose can health-check the container. It is a
# compose-ism, not a production requirement: Kubernetes performs httpGet probes
# from outside the container and needs nothing installed inside it, which is why
# a production image would be distroless — no shell, no package manager, far less
# to patch.
# One package, so that compose can health-check the container. Unpinned, which is a
# reproducibility hole in an image whose whole claim is that it builds from nothing -
# a production build would pin the version or, better, use a distroless base and let
# the orchestrator probe from outside, needing nothing installed at all.
RUN apt-get update \
 && apt-get install --yes --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && groupadd --system --gid 10001 app \
 && useradd --system --uid 10001 --gid app --home-dir /app app

WORKDIR /app

# One COPY per layer, least-changing first. Editing a source file invalidates
# only the last of these — 56 kB — rather than the 69 MB of dependencies above it.
COPY --from=extract --chown=app:app /extract/layers/dependencies/ ./
COPY --from=extract --chown=app:app /extract/layers/spring-boot-loader/ ./
COPY --from=extract --chown=app:app /extract/layers/snapshot-dependencies/ ./
COPY --from=extract --chown=app:app /extract/layers/application/ ./

# Never root. A container escape should not start from uid 0.
USER app

EXPOSE 8080

# MaxRAMPercentage: the JVM reads the cgroup limit, so this is a share of the
#   container's memory rather than the host's. Left at the default it will size a
#   heap for a machine it cannot have.
# ExitOnOutOfMemoryError: a JVM that has run out of memory is not going to
#   recover. Dying lets the orchestrator replace it; limping along means health
#   checks pass while requests fail.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-XX:+ExitOnOutOfMemoryError", "-jar", "app.jar"]
