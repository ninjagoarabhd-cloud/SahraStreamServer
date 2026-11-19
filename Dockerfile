FROM gradle:8.11.1-jdk17 AS build
WORKDIR /home/gradle/src
COPY --chown=gradle:gradle . .

RUN echo "=== PWD ===" && pwd \
 && echo "=== ROOT ===" && ls -la \
 && echo "=== ALL FILES ===" && (find . -path ./.git -prune -o -type f -print | head -100)
RUN test -f src/main/kotlin/com/sahra4ever/Application.kt \
 || (echo "Application.kt NOT IN BUILD CONTEXT" && exit 1)

RUN gradle clean buildFatJar --no-daemon
RUN jar tf build/libs/server.jar | grep -q "com/sahra4ever/ApplicationKt.class"

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=build /home/gradle/src/build/libs/server.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
