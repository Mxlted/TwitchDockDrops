# syntax=docker/dockerfile:1.7

FROM gradle:9.5.1-jdk21-alpine AS build
USER root
RUN mkdir -p /workspace && chown gradle:gradle /workspace
USER gradle
WORKDIR /workspace

COPY --chown=gradle:gradle settings.gradle build.gradle ./
COPY --chown=gradle:gradle src ./src
RUN gradle --no-daemon clean test installDist

FROM eclipse-temurin:21-jre-alpine AS runtime

RUN addgroup -S dockdrops \
    && adduser -S -D -H -G dockdrops -u 10001 dockdrops \
    && mkdir -p /app /data \
    && chown -R dockdrops:dockdrops /app /data

WORKDIR /app
COPY --from=build --chown=dockdrops:dockdrops /workspace/build/install/twitch-dock-drops/ ./

ENV TWITCH_DROPS_DATA_DIR=/data \
    TWITCH_DROPS_PORT=8080 \
    JAVA_OPTS="-Xms16m -Xmx256m -XX:+UseSerialGC -XX:MaxMetaspaceSize=96m -XX:ReservedCodeCacheSize=32m -XX:MaxDirectMemorySize=32m -Dfile.encoding=UTF-8"

USER dockdrops:dockdrops
EXPOSE 8080
VOLUME ["/data"]

ENTRYPOINT ["bin/twitch-dock-drops"]
