# ---- Build Stage ----
FROM gradle:8.7-jdk21 AS build
WORKDIR /app

COPY . .
RUN ./gradlew bootJar

# ---- Runtime Stage ----
FROM openjdk:21-jdk-slim
WORKDIR /app

COPY --from=build /app/build/libs/*.jar app.jar

ENTRYPOINT ["java", "-jar", "app.jar"]
