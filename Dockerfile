# syntax=docker/dockerfile:1
# Builds either Spring Boot service from source: --build-arg MODULE=app (default) or MODULE=simulator.

FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle gradle
COPY events events
COPY matching-engine matching-engine
COPY app app
COPY simulator simulator
ARG MODULE=app
# compose builds the exchange and simulator in parallel; "locked" gives one Gradle at a time the
# shared dependency cache, which Gradle cannot share across processes in different containers.
RUN --mount=type=cache,target=/root/.gradle,sharing=locked \
    ./gradlew --no-daemon --quiet :${MODULE}:bootJar \
    && cp "$(ls ${MODULE}/build/libs/*.jar | grep -v -- '-plain.jar')" /service.jar

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 --no-create-home exchange
COPY --from=build /service.jar /opt/service.jar
USER exchange
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/opt/service.jar"]
