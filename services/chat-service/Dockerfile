FROM maven:3.9.11-eclipse-temurin-21-alpine AS build
WORKDIR /workspace
COPY . .
RUN mvn -B -ntp -pl services/chat-service -am package -DskipTests

FROM eclipse-temurin:21.0.7_6-jre-alpine-3.21
RUN addgroup -S docbase && adduser -S docbase -G docbase
WORKDIR /app
COPY --from=build /workspace/services/chat-service/target/chat-service-*.jar app.jar
USER docbase
EXPOSE 8084
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-Djava.security.egd=file:/dev/./urandom", "-jar", "/app/app.jar"]
