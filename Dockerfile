FROM gradle:8.11.1-jdk17 AS build
WORKDIR /home/gradle/src
COPY --chown=gradle:gradle . .
RUN gradle clean buildFatJar --no-daemon

# هذا السطر سيجبر عملية البناء على الفشل إذا لم يتم العثور على الكود داخل الملف
RUN jar tf build/libs/server.jar | grep "com/sahra4ever/ApplicationKt.class"

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=build /home/gradle/src/build/libs/server.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
