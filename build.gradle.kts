plugins {
    kotlin("jvm") version "1.9.22"
    id("io.ktor.plugin") version "2.3.7"
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
