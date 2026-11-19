plugins {
    kotlin("jvm") version "2.3.0"
    id("io.ktor.plugin") version "3.6.1"
    application
}

group = "com.sahra4ever"
version = "1.0.0"

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("io.ktor:ktor-server-core-jvm")
    implementation("io.ktor:ktor-server-netty-jvm")
    implementation("io.ktor:ktor-server-content-negotiation-jvm")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm")
    implementation("ch.qos.logback:logback-classic:1.4.14")
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

tasks.withType<Jar> {
    manifest {
        attributes["Main-Class"] = "com.sahra4ever.ApplicationKt"
    }
}
