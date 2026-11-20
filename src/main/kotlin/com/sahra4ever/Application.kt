package com.sahra4ever

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

private const val BASE_URL = "https://arabseed.press"

private const val USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
    "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

private val httpClient = HttpClient(OkHttp) {
    engine {
        config {
            followRedirects(true)
            retryOnConnectionFailure(true)
        }
    }
    install(DefaultRequest) {
        header(HttpHeaders.UserAgent, USER_AGENT)
        header(HttpHeaders.Accept, "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
        header(HttpHeaders.AcceptLanguage, "en-US,en;q=0.9,ar;q=0.8")
        header("Referer", "$BASE_URL/") // ✅ تم التصحيح: نص مباشرinstead of HttpHeadersReferer()
        header("Sec-Fetch-Dest", "document")
        header("Sec-Fetch-Mode", "navigate")
        header("Sec-Fetch-Site", "same-origin")
        header("Sec-Fetch-User", "?1")
        header(HttpHeaders.CacheControl, "no-cache")
        header(HttpHeaders.Pragma, "no-cache")
        header("Upgrade-Insecure-Requests", "1")
    }
}

// كشف الحظر أو النطاق الميت بدقة
private fun detectBlock(html: String): String? {
    val lower = html.lowercase()
    if (html.length < 500) return "Empty/Blocked page (${html.length} chars)"
    if (lower.contains("just a moment") || lower.contains("cf-chl-") ||
        lower.contains("challenge-platform") || lower.contains("turnstile"))
        return "Cloudflare Challenge detected"
    if (lower.contains("namecheap") || lower.contains("domain registration has expired") ||
        lower.contains("parkingpage"))
        return "Parked/Expired domain page"
    return null
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

private suspend fun fetchHtml(url: String): String =
    httpClient.get(url).bodyAsText()

// إيجاد رابط الفيلم بمرونة: أولاً بالنص، ثم بالـ classes المعروفة
private fun findMovieLink(doc: Document, query: String): String? {
    // الاستراتيجية 1: رابط نصه يحتوي اسم الفيلم (الأكثر موثوقية)
    val byText = doc.select("a[href]").firstOrNull { a ->
        val href = a.absUrl("href")
        val txt = a.text()
        href.startsWith(BASE_URL) &&
            href.removePrefix(BASE_URL).trim('/').isNotEmpty() &&
            !href.contains("/category/") && !href.contains("/home") &&
            !href.contains("/page/") && !href.contains("/tag/") &&
            txt.contains(query, ignoreCase = true)
    }?.absUrl("href")
    if (byText != null) return byText

    // الاستراتيجية 2: classes شائعة في قالب عرب سيد
    return doc.selectFirst(
        ".BlockItem a, .block-item a, .movie-box a, .post-item a, .BoxItem a, .Result a, article a[href]"
    )?.absUrl("href")
}

fun main() {
    val port: Int = System.getenv("PORT")?.toIntOrNull() ?: 8080

    embeddedServer(Netty, port = port, host = "0.0.0.0") {
        routing {
            get("/") { call.respondText("Sahra4Ever Server is Running! 🚀") }

            get("/api/debug") {
                val name = call.request.queryParameters["name"] ?: "Inception"
                try {
                    val url = "$BASE_URL/?s=" + name.trim().replace(" ", "+")
                    val html = fetchHtml(url)
                    val block = detectBlock(html)
                    call.respondText(
                        "URL: $url\nTotal length: ${html.length}\nBlock: ${block ?: "NONE ✅"}\n\n${html.take(4000)}",
                        ContentType.Text.Plain
                    )
                } catch (e: Exception) {
                    call.respondText("ERROR: ${e.message}", ContentType.Text.Plain)
                }
            }

            get("/api/search") {
                val movieName = call.request.queryParameters["name"]
                if (movieName.isNullOrBlank()) {
                    call.respondText("{\"success\":false,\"error\":\"Missing name\"}", ContentType.Application.Json)
                    return@get
                }
                try {
                    val searchUrl = "$BASE_URL/?s=" + movieName.trim().replace(" ", "+")
                    val searchHtml = fetchHtml(searchUrl)
                    val block = detectBlock(searchHtml)
                    if (block != null) {
                        call.respondText("{\"success\":false,\"error\":\"${jsonEscape(block)}\"}", ContentType.Application.Json)
                        return@get
                    }

                    val searchDoc = Jsoup.parse(searchHtml, searchUrl)
                    val moviePageUrl = findMovieLink(searchDoc, movieName)
                    if (moviePageUrl == null) {
                        call.respondText("{\"success\":false,\"message\":\"لم يتم العثور على الفيلم\"}", ContentType.Application.Json)
                        return@get
                    }

                    val movieHtml = fetchHtml(moviePageUrl)
                    val movieDoc = Jsoup.parse(movieHtml, moviePageUrl)

                    // جمع كل الـ iframes وسيرفرات المشاهدة المحتملة
                    val iframes = movieDoc.select("iframe[src]").mapNotNull { it.absUrl("src").takeIf { s -> s.isNotBlank() } }
                    val servers = movieDoc.select("a[data-server], .server-item a, .servers-list a, ul.servers a, .watch-servers a")
                        .mapNotNull { it.absUrl("href").takeIf { s -> s.isNotBlank() } }.distinct()

                    val iframeUrl = iframes.firstOrNull() ?: ""
                    val serversJson = servers.joinToString(",") { "\"" + jsonEscape(it) + "\"" }

                    val json = "{" +
                        "\"success\":true," +
                        "\"movie\":\"${jsonEscape(movieName)}\"," +
                        "\"pageUrl\":\"${jsonEscape(moviePageUrl)}\"," +
                        "\"embedUrl\":\"${jsonEscape(iframeUrl)}\"," +
                        "\"iframes\":[${iframes.joinToString(",") { "\"" + jsonEscape(it) + "\"" }}]," +
                        "\"servers\":[$serversJson]" +
                        "}"
                    call.respondText(json, ContentType.Application.Json)
                } catch (e: Exception) {
                    call.respondText("{\"success\":false,\"error\":\"${jsonEscape(e.message ?: "Unknown")}\"}", ContentType.Application.Json)
                }
            }
        }
    }.start(wait = true)
}
