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

private const val UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

// نطاق عرب سيد من arprov
private val ARABSEED_DOMAINS = listOf("https://arabseed.trade")
private var BASE = ARABSEED_DOMAINS.first()

private val httpClient = HttpClient(OkHttp) {
    engine { config { followRedirects(true); retryOnConnectionFailure(true) } }
    install(DefaultRequest) {
        header(HttpHeaders.UserAgent, UA)
        header(HttpHeaders.Accept, "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        header(HttpHeaders.AcceptLanguage, "en-US,en;q=0.9,ar;q=0.8")
    }
}

private fun jsonEscape(s: String): String {
    val sb = StringBuilder()
    for (c in s) when (c) {
        '"' -> sb.append("\\\""); '\\' -> sb.append("\\\\")
        '\n' -> sb.append("\\n"); '\r' -> sb.append("\\r"); '\t' -> sb.append("\\t")
        else -> sb.append(c)
    }
    return sb.toString()
}

private suspend fun fetchHtml(url: String, referer: String? = null): String {
    val resp = httpClient.get(url) { if (referer != null) header("Referer", referer) }
    val body = resp.bodyAsText()
    val low = body.lowercase()
    if (low.contains("just a moment") || low.contains("challenge-platform") || low.contains("cf-chl")) {
        throw IllegalStateException("CLOUDFLARE len=${body.length} @ $url")
    }
    return body
}

// يكتشف النطاق الحي من القائمة
private suspend fun resolveBase(): String {
    for (d in listOf(BASE) + ARABSEED_DOMAINS) {
        try {
            val body = httpClient.get("$d/").bodyAsText()
            val low = body.lowercase()
            if (body.length > 500 && !low.contains("just a moment") &&
                !low.contains("challenge-platform") && !low.contains("cf-chl")) {
                BASE = d; return d
            }
        } catch (_: Exception) {}
    }
    return BASE
}

private fun findMovieLink(doc: Document, query: String): String? {
    val byText = doc.select("a[href]").firstOrNull { a ->
        val href = a.absUrl("href"); val txt = a.text()
        href.startsWith(BASE) && href.removePrefix(BASE).trim('/').isNotEmpty() &&
            !href.contains("/category/") && !href.contains("/home") &&
            !href.contains("/page/") && !href.contains("/tag/") &&
            txt.contains(query, ignoreCase = true)
    }?.absUrl("href")
    if (byText != null) return byText
    return doc.selectFirst(".BlockItem a, .post-item a, .BoxItem a, article a[href]")?.absUrl("href")
}

private fun hexToUrl(hex: String): String =
    hex.chunked(2).joinToString("") { it.toInt(16).toChar().toString() }

private val watchCache = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Long>>()

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    embeddedServer(Netty, port = port, host = "0.0.0.0") {
        routing {
            get("/") { call.respondText("Sahra4Ever Server Running 🚀") }

            get("/api/watch") {
                val name = call.request.queryParameters["name"] ?: "Inception"
                val now = System.currentTimeMillis()
                watchCache[name]?.let { (json, ts) ->
                    if (now - ts < 600_000) { call.respondText(json, ContentType.Application.Json); return@get }
                }
                try {
                    val base = resolveBase()
                    val searchHtml = fetchHtml("$base/?s=" + name.trim().replace(" ", "+"), "$base/")
                    val movieUrl = findMovieLink(Jsoup.parse(searchHtml, base), name)
                    if (movieUrl == null) { call.respondText("{\"success\":false,\"error\":\"NO_MOVIE_LINK\"}", ContentType.Application.Json); return@get }
                    val movieHtml = fetchHtml(movieUrl, "$base/")
                    val watchUrl = Jsoup.parse(movieHtml, movieUrl).selectFirst("a.watch__btn")?.absUrl("href")
                        ?: (movieUrl.trimEnd('/') + "/watch")
                    val watchHtml = fetchHtml(watchUrl, movieUrl)
                    val watchDoc = Jsoup.parse(watchHtml, watchUrl)
                    val playUrl = watchDoc.selectFirst("iframe#videoIframe")?.absUrl("src")
                    if (playUrl == null) { call.respondText("{\"success\":false,\"error\":\"NO_IFRAME\"}", ContentType.Application.Json); return@get }
                    val altServers = watchDoc.select("#watch li[data-link]").mapNotNull { it.attr("data-link").takeIf { s -> s.isNotBlank() } }
                    val json = "{\"success\":true,\"playUrl\":\"" + jsonEscape(playUrl) + "\",\"altServers\":[" +
                        altServers.joinToString(",") { "\"" + jsonEscape(it) + "\"" } + "]}"
                    watchCache[name] = json to now
                    call.respondText(json, ContentType.Application.Json)
                } catch (e: Exception) {
                    call.respondText("{\"success\":false,\"error\":\"${jsonEscape(e.message ?: "unknown")}\"}", ContentType.Application.Json)
                }
            }

            get("/api/extract") {
                val name = call.request.queryParameters["name"] ?: "Inception"
                try {
                    val base = resolveBase()
                    val searchHtml = fetchHtml("$base/?s=" + name.trim().replace(" ", "+"), "$base/")
                    val movieUrl = findMovieLink(Jsoup.parse(searchHtml, base), name)
                    if (movieUrl == null) { call.respondText("{\"step\":\"search\",\"error\":\"NO_MOVIE_LINK\"}", ContentType.Application.Json); return@get }
                    val movieHtml = fetchHtml(movieUrl, "$base/")
                    val watchUrl = Jsoup.parse(movieHtml, movieUrl).selectFirst("a.watch__btn")?.absUrl("href")
                        ?: (movieUrl.trimEnd('/') + "/watch")
                    val watchHtml = fetchHtml(watchUrl, movieUrl)
                    val watchDoc = Jsoup.parse(watchHtml, watchUrl)
                    val playUrl = watchDoc.selectFirst("iframe#videoIframe")?.absUrl("src")
                    if (playUrl == null) { call.respondText("{\"step\":\"watch\",\"error\":\"NO_IFRAME\"}", ContentType.Application.Json); return@get }
                    val altServers = watchDoc.select("#watch li[data-link]").mapNotNull { it.attr("data-link").takeIf { s -> s.isNotBlank() } }
                    val playHtml = fetchHtml(playUrl, watchUrl)
                    val eUrl = Jsoup.parse(playHtml, playUrl).select("iframe[src]").map { it.absUrl("src") }.firstOrNull { it.contains("/e/") }
                    if (eUrl == null) { call.respondText("{\"step\":\"play\",\"error\":\"NO_E_IFRAME\"}", ContentType.Application.Json); return@get }
                    val eHtml = fetchHtml(eUrl, playUrl)
                    val hex = Regex("""const\s+Mohix\s*=\s*"([0-9a-fA-F]+)"""").find(eHtml)?.groupValues?.get(1)
                    if (hex == null) { call.respondText("{\"step\":\"e\",\"error\":\"NO_HEX\"}", ContentType.Application.Json); return@get }
                    val m3u8 = hexToUrl(hex)
                    val testResp = httpClient.get(m3u8) { header("Referer", "https://govid.live/") }
                    val testBody = testResp.bodyAsText()
                    val isPlaylist = testBody.trimStart().startsWith("#EXTM3U")
                    val json = "{" +
                        "\"success\":true," +
                        "\"m3u8\":\"${jsonEscape(m3u8)}\"," +
                        "\"altServers\":[${altServers.joinToString(",") { "\"" + jsonEscape(it) + "\"" }}]," +
                        "\"m3u8Test\":{\"code\":${testResp.status.value},\"isPlaylist\":$isPlaylist,\"preview\":\"${jsonEscape(testBody.take(150))}\"}" +
                        "}"
                    call.respondText(json, ContentType.Application.Json)
                } catch (e: Exception) {
                    call.respondText("{\"success\":false,\"error\":\"${jsonEscape(e.message ?: "unknown")}\"}", ContentType.Application.Json)
                }
            }
        }
    }.start(wait = true)
                                    }
