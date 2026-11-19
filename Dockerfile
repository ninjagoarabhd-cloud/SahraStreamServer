# مرحلة البناء
FROM gradle:8.11.1-jdk17 AS build
WORKDIR /home/gradle/src
COPY --chown=gradle:gradle . .
RUN gradle clean buildFatJar --no-daemon

# مرحلة التشغيل
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
# ننسخ فقط ملف الـ Fat JAR الذي ينتهي اسمه بـ -all.jar
COPY --from=build /home/gradle/src/build/libs/*-all.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
