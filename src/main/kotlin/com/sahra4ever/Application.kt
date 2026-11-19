package com.sahra4ever

import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

fun main() {
    embeddedServer(Netty, port = System.getenv("PORT")?.toIntOrNull() ?: 8080, host = "0.0.0.0") {
        routing {
            get("/") {
                call.respondText("Sahra4Ever Server is Running! 🚀")
            }
            
            get("/api/search") {
                val movieName = call.request.queryParameters["name"]
                if (movieName == null) {
                    call.respondText("Please provide a movie name: /api/search?name=Inception")
                    return@get
                }

                try {
                    // استخدام Ktor Client لجلب الصفحة
                    val client = HttpClient(CIO)
                    val searchUrl = "https://arabseed.cam/?s=${movieName.replace(" ", "+")}"
                    
                    val response: HttpResponse = client.get(searchUrl) {
                        header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    }
                    
                    val html = response.readText()
                    val doc: Document = Jsoup.parse(html)

                    // البحث عن أول نتيجة (هذا الـ class خاص بعرب سيد)
                    val firstResult = doc.selectFirst(".movie-box a, .post-item a, .BoxItem a")
                    
                    if (firstResult == null) {
                        call.respondText("""{"success": false, "message": "لم يتم العثور على الفيلم في عرب سيد"}""")
                    } else {
                        val moviePageUrl = firstResult.attr("href")
                        
                        // الدخول لصفحة الفيلم لاستخراج الـ iframe
                        val moviePageResponse = client.get(moviePageUrl) {
                            header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                        }
                        val movieHtml = moviePageResponse.readText()
                        val movieDoc = Jsoup.parse(movieHtml)
                        
                        // البحث عن رابط الـ iframe (سيرفر المشاهدة)
                        val iframe = movieDoc.selectFirst("iframe")
                        val iframeUrl = iframe?.attr("src") ?: "لم يتم العثور على سيرفر مشاهدة"

                        call.respondText("""
                            {
                                "success": true,
                                "movie": "$movieName",
                                "pageUrl": "$moviePageUrl",
                                "embedUrl": "$iframeUrl"
                            }
                        """.trimIndent())
                    }
                    
                } catch (e: Exception) {
                    call.respondText("""{"success": false, "error": "${e.message}"}""")
                }
            }
        }
    }.start(wait = true)
}
