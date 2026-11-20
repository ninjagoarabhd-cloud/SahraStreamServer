package com.sahra4ever

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.*
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jsoup.Jsoup

private const val USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

private val httpClient = HttpClient(CIO) {
    followRedirects = true
}

private fun jsonEscape(s: String): String {
    val sb = StringBuilder()
    for (c in s) {
        when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> sb.append(c)
        }
    }
    return sb.toString()
}

private suspend fun fetchHtml(url: String): String {
    val response: HttpResponse = httpClient.get(url) {
        header(HttpHeaders.UserAgent, USER_AGENT)
    }
    return response.bodyAsText()
}

fun main() {
    val port: Int = System.getenv("PORT")?.toIntOrNull() ?: 8080

    embeddedServer(Netty, port = port, host = "0.0.0.0") {
        routing {
            get("/") {
                call.respondText("Sahra4Ever Server is Running! 🚀")
            }

            // ==========================================
            // 👇 كود التشخيص الجديد الذي أضافه كلود 👇
            // ==========================================
            get("/api/debug") {
                val name = call.request.queryParameters["name"] ?: "Inception"
                val offset = call.request.queryParameters["offset"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
                val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 20000) ?: 5000
                val clean = call.request.queryParameters["clean"] == "1"

                try {
                    val url = "https://arabseed.cam/?s=" + name.trim().replace(" ", "+")
                    var html = fetchHtml(url)

                    if (clean) {
                        val doc = Jsoup.parse(html, url)
                        doc.select("script, style, svg, noscript, link, meta").remove()
                        html = doc.body().html()
                    }

                    val end = minOf(html.length, offset + limit)
                    val slice = if (offset >= html.length) "" else html.substring(offset, end)

                    call.respondText(
                        "URL: $url\nTotal length: ${html.length}\nShowing: $offset..$end\n\n$slice",
                        ContentType.Text.Plain
                    )
                } catch (e: Exception) {
                    call.respondText("ERROR: ${e.message}", ContentType.Text.Plain)
                }
            }
            // ==========================================
            // 👆 نهاية كود التشخيص 👆
            // ==========================================

            get("/api/search") {
                val movieName: String? = call.request.queryParameters["name"]
                if (movieName.isNullOrBlank()) {
                    call.respondText(
                        "Please provide a movie name: /api/search?name=Inception"
                    )
                    return@get
                }

                try {
                    val searchUrl =
                        "https://arabseed.cam/?s=" + movieName.trim().replace(" ", "+")
                    val searchHtml: String = fetchHtml(searchUrl)
                    val searchDoc = Jsoup.parse(searchHtml, searchUrl)

                    val firstResult =
                        searchDoc.selectFirst(".movie-box a, .post-item a, .BoxItem a")

                    if (firstResult == null) {
                        call.respondText(
                            "{\"success\": false, \"message\": \"لم يتم العثور على الفيلم في عرب سيد\"}",
                            ContentType.Application.Json
                        )
                        return@get
                    }

                    val moviePageUrl: String = firstResult.absUrl("href")
                    val movieHtml: String = fetchHtml(moviePageUrl)
                    val movieDoc = Jsoup.parse(movieHtml, moviePageUrl)

                    val iframe = movieDoc.selectFirst("iframe")
                    val iframeUrl: String = iframe?.absUrl("src")
                        ?.takeIf { it.isNotBlank() }
                        ?: "لم يتم العثور على سيرفر مشاهدة"

                    val json = "{" +
                        "\"success\": true, " +
                        "\"movie\": \"" + jsonEscape(movieName) + "\", " +
                        "\"pageUrl\": \"" + jsonEscape(moviePageUrl) + "\", " +
                        "\"embedUrl\": \"" + jsonEscape(iframeUrl) + "\"" +
                        "}"
                    call.respondText(json, ContentType.Application.Json)
                } catch (e: Exception) {
                    val msg = jsonEscape(e.message ?: "Unknown error")
                    call.respondText(
                        "{\"success\": false, \"error\": \"" + msg + "\"}",
                        ContentType.Application.Json
                    )
                }
            }
        }
    }.start(wait = true)
}
