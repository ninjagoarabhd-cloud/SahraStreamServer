plugins {
    kotlin("jvm") version "2.0.0" // تم تحديث Kotlin إلى إصدار أحدث
    id("io.ktor.plugin") version "3.6.1" // تم تحديث Ktor إلى أحدث إصدار مستقر
    application
}

group = "com.sahra4ever"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("io.ktor:ktor-server-core-jvm")
    implementation("io.ktor:ktor-server-netty-jvm")
    implementation("io.ktor:ktor-server-content-negotiation-jvm")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm")
    implementation("ch.qos.logback:logback-classic:1.4.14")
    
    // مكتبات لسحب البيانات من المواقع (مثل Jsoup)
    implementation("org.jsoup:jsoup:1.17.2")
    implementation("io.ktor:ktor-client-core-jvm")
    implementation("io.ktor:ktor-client-cio-jvm")
}

application {
    mainClass.set("com.sahra4ever.ApplicationKt")
}

ktor {
    fatJar {
        archiveFileName.set("server.jar")
    }
}
