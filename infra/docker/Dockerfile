# Runtime image only — build JARs on the host first (avoids pulling maven:* when Docker Hub/proxy fails).
# From repo root: mvn -B -pl client,server,simulator -am package -DskipTests
# Or: ./infra/docker/build-local.sh
FROM eclipse-temurin:21-jre-alpine
RUN apk add --no-cache curl
WORKDIR /app

ARG MODULE=client
COPY ${MODULE}/target/${MODULE}-*.jar /app/app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
