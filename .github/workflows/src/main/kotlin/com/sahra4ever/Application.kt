package com.sahra4ever

import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun main() {
    embeddedServer(Netty, port = System.getenv("PORT")?.toIntOrNull() ?: 8080, host = "0.0.0.0") {
        routing {
            get("/") {
                call.respondText("Sahra4Ever Server is Running! 🚀")
            }
            get("/api/stream") {
                val movieName = call.request.queryParameters["name"]
                if (movieName == null) {
                    call.respondText("Please provide a movie name: /api/stream?name=Inception")
                } else {
                    // هنا لاحقاً سنضع كود arprov للبحث عن الفيلم
                    call.respondText("جاري البحث عن: $movieName... (هنا سنضع كود arprov لاحقاً)")
                }
            }
        }
    }.start(wait = true)
}
