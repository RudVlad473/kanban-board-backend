# ---- Build Stage ----
FROM gradle:8.7-jdk21 AS build
WORKDIR /app

COPY . .

RUN chmod +x gradlew
RUN ./gradlew bootJar

# ---- Runtime Stage ----
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

COPY --from=build /app/build/libs/*.jar app.jar

ENTRYPOINT ["java", "-jar", "app.jar"]
