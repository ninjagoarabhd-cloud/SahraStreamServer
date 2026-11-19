# مرحلة البناء
FROM gradle:8.11.1-jdk17 AS build
COPY --chown=gradle:gradle . /home/gradle/src
WORKDIR /home/gradle/src
# استخدام installDist لإنشاء مجلد تشغيل كامل
RUN gradle installDist --no-daemon

# مرحلة التشغيل
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
# نسخ مجلد التشغيل بالكامل (يحتوي على كل المكتبات)
COPY --from=build /home/gradle/src/build/install/SahraStreamServer/ /app/
EXPOSE 8080
# تشغيل السكربت الجاهز مباشرة
ENTRYPOINT ["/app/bin/SahraStreamServer"]
