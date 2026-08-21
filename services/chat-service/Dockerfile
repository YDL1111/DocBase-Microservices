# syntax=docker/dockerfile:1.7

FROM maven:3.9.11-eclipse-temurin-21-alpine AS build
ARG MAVEN_REPOSITORY_URL=https://repo.maven.apache.org/maven2
ENV MAVEN_REPOSITORY_URL=${MAVEN_REPOSITORY_URL}
WORKDIR /workspace
COPY . .
RUN --mount=type=cache,target=/root/.m2/repository \
    for attempt in 1 2 3; do \
      mvn -B -ntp -s .mvn/settings-docker.xml \
        -Dmaven.wagon.http.retryHandler.count=5 \
        -Dmaven.wagon.http.retryHandler.requestSentEnabled=true \
        -Dmaven.wagon.httpconnectionManager.ttlSeconds=30 \
        -pl services/chat-service -am package -DskipTests && exit 0; \
      if [ "$attempt" -eq 3 ]; then exit 1; fi; \
      echo "Maven build attempt $attempt failed; retrying with the cached local repository..."; \
      sleep $((attempt * 5)); \
    done

FROM eclipse-temurin:21.0.7_6-jre-alpine-3.21
RUN addgroup -S docbase && adduser -S docbase -G docbase
WORKDIR /app
COPY --from=build /workspace/services/chat-service/target/chat-service-*.jar app.jar
USER docbase
EXPOSE 8084
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-Djava.security.egd=file:/dev/./urandom", "-jar", "/app/app.jar"]
