# مرحلة البناء
FROM gradle:8.11.1-jdk17 AS build
COPY --chown=gradle:gradle . /home/gradle/src
WORKDIR /home/gradle/src
# بناء الـ Fat JAR (الآن سينجح لأن Gradle محدث)
RUN gradle buildFatJar --no-daemon

# مرحلة التشغيل
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
# نسخ الملف الذي قمنا بتسميته في build.gradle.kts
COPY --from=build /home/gradle/src/build/libs/server.jar app.jar
EXPOSE 8080
# تشغيل السيرفر مباشرة باستخدام Java
ENTRYPOINT ["java", "-jar", "app.jar"]
