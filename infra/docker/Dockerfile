# Runtime image only — build JARs on the host first (avoids pulling maven:* when Docker Hub/proxy fails).
# From repo root: mvn -B -pl client,server,simulator -am package -DskipTests
# Or: ./infra/docker/build-local.sh
#
# Default JRE is enough for client/simulator. Server (Chronicle Map/Queue) needs a JDK base image so
# runtime bytecode generation can load com.sun.tools.javac.api.JavacTool — override via build-arg BASE_IMAGE.
#
# When MAIN_CLASS is set, the Spring Boot fat jar is exploded to /app/extracted and the app is
# launched with an explicit classpath (BOOT-INF/classes + BOOT-INF/lib/*). This is required for
# Chronicle Map: its embedded javac compiler reads java.class.path to find dependencies like
# net.openhft.chronicle.hash.VanillaGlobalMutableState when generating $$Native value proxies at
# runtime — nested BOOT-INF/lib/*.jar inside a sealed fat jar are invisible to it.
# BASE_IMAGE to eclipse-temurin:21-jdk-noble
ARG BASE_IMAGE=eclipse-temurin:21-jre-alpine
FROM ${BASE_IMAGE}
RUN apk add --no-cache curl unzip
WORKDIR /app

ARG MODULE=client
ARG MAIN_CLASS=""
COPY ${MODULE}/target/${MODULE}-*.jar /app/app.jar

RUN if [ -n "$MAIN_CLASS" ]; then \
      mkdir -p /app/extracted && \
      unzip -q /app/app.jar -d /app/extracted && \
      rm /app/app.jar; \
    fi

ENV MAIN_CLASS=${MAIN_CLASS}
EXPOSE 8080
ENTRYPOINT ["/bin/sh","-c","if [ -n \"$MAIN_CLASS\" ]; then exec java -cp '/app/extracted/BOOT-INF/classes:/app/extracted/BOOT-INF/lib/*' \"$MAIN_CLASS\" \"$@\"; else exec java -jar /app/app.jar \"$@\"; fi","--"]
