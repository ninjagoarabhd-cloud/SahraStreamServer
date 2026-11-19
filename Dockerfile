FROM gradle:8.11.1-jdk17 AS build
WORKDIR /home/gradle/src
COPY --chown=gradle:gradle . .

# Diagnostic: which Kotlin files actually reached the build?
RUN echo "=== KOTLIN SOURCES ===" && find . -name "*.kt" -not -path "./build/*"

RUN gradle clean buildFatJar --no-daemon

RUN echo "=== build/libs ===" && ls -la build/libs/
RUN echo "=== Classes in JAR ===" && jar tf build/libs/server.jar | grep "com/sahra4ever/" || true

# Fail the build if the main class is missing
RUN jar tf build/libs/server.jar | grep -q "com/sahra4ever/ApplicationKt.class"

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=build /home/gradle/src/build/libs/server.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
