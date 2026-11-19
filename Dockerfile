# مرحلة البناء
FROM gradle:8.11.1-jdk17 AS build
COPY --chown=gradle:gradle . /home/gradle/src
WORKDIR /home/gradle/src
# بناء الـ Fat JAR (سينتج ملف باسم server.jar)
RUN gradle buildFatJar --no-daemon

# مرحلة التشغيل
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
# نسخ ملف server.jar وتشغيله مباشرة
COPY --from=build /home/gradle/src/build/libs/server.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
