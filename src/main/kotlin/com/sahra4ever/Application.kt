package com.sahra4ever

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.request
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI

private const val UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

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

private var cachedFinalBase: Pair<String, Long>? = null
private val BASE_LOCK = Any()

private suspend fun resolveFinalBase(): String {
    val now = System.currentTimeMillis()
    cachedFinalBase?.let { (base, ts) -> if (now - ts < 600_000) return base }
    synchronized(BASE_LOCK) {
        cachedFinalBase?.let { (base, ts) -> if (now - ts < 600_000) return base }
    }

    val resp = httpClient.get("$BASE/")
    val finalUrl = resp.request.url.toString()
    var host = try { URI(finalUrl).let { "${it.scheme}://${it.host}" } } catch (_: Exception) { BASE }

    val body = resp.bodyAsText()
    val low = body.lowercase()
    if (low.contains("just a moment") || low.contains("challenge-platform") || low.contains("cf-chl")) {
        throw IllegalStateException("CLOUDFLARE on final base $host")
    }

    val doc = Jsoup.parse(body, host)
    val counts = mutableMapOf<String, Int>()
    doc.select("a[href]").forEach { a ->
        val href = a.absUrl("href")
        if (href.startsWith("http")) {
            val h = try { URI(href).let { "${it.scheme}://${it.host}" } } catch (_: Exception) { null }
            if (h != null) counts[h] = (counts[h] ?: 0) + 1
        }
    }
    val top = counts.maxByOrNull { it.value }?.key
    if (top != null && top != host && (counts[top] ?: 0) >= 5) {
        host = top
    }

    synchronized(BASE_LOCK) {
        cachedFinalBase = host to now
        BASE = host
    }
    return host
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

private fun findMovieLink(doc: Document, query: String, base: String): String? {
    val byText = doc.select("a[href]").firstOrNull { a ->
        val href = a.absUrl("href"); val txt = a.text()
        href.startsWith(base) && href.removePrefix(base).trim('/').isNotEmpty() &&
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
            get("/") { call.respondText("Sahra4Ever Server Running 🚀\nCurrent base: $BASE") }

            get("/api/resolve") {
                try {
                    val base = resolveFinalBase()
                    call.respondText("{\"currentBase\":\"$base\"}", ContentType.Application.Json)
                } catch (e: Exception) {
                    call.respondText("{\"error\":\"${jsonEscape(e.message ?: "unknown")}\"}", ContentType.Application.Json)
                }
            }

            get("/api/debug-search") {
                val name = call.request.queryParameters["name"] ?: "Inception"
                try {
                    val base = resolveFinalBase()
                    val searchUrl = "$base/?s=" + name.trim().replace(" ", "+")
                    // ✅ الإصلاح: إضافة Referer لتجاوز Cloudflare
                    val resp = httpClient.get(searchUrl) {
                        header("User-Agent", UA)
                        header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                        header("Referer", "$base/")
                    }
                    val html = resp.bodyAsText()
                    val doc = Jsoup.parse(html, base)

                    val allLinks = doc.select("a[href]").take(50).map { a ->
                        val href = a.absUrl("href")
                        val txt = a.text().take(80)
                        "$href | $txt"
                    }

                    val containsQuery = html.lowercase().contains(name.lowercase())
                    val movieUrl = findMovieLink(doc, name, base)

                    val json = "{" +
                        "\"status\":${resp.status.value}," +
                        "\"finalBase\":\"${jsonEscape(base)}\"," +
                        "\"searchUrl\":\"${jsonEscape(searchUrl)}\"," +
                        "\"htmlLen\":${html.length}," +
                        "\"containsQuery\":$containsQuery," +
                        "\"linksCount\":${allLinks.size}," +
                        "\"sampleLinks\":[${allLinks.joinToString(",") { "\"${jsonEscape(it)}\"" }}]," +
                        "\"foundMovie\":" + (if (movieUrl != null) "\"${jsonEscape(movieUrl)}\"" else "null") +
                        "}"
                    call.respondText(json, ContentType.Application.Json)
                } catch (e: Exception) {
                    call.respondText("{\"error\":\"${jsonEscape(e.message ?: "unknown")}\"}", ContentType.Application.Json)
                }
            }

            get("/api/watch") {
                val name = call.request.queryParameters["name"] ?: "Inception"
                val now = System.currentTimeMillis()
                watchCache[name]?.let { (json, ts) ->
                    if (now - ts < 600_000) { call.respondText(json, ContentType.Application.Json); return@get }
                }
                try {
                    val base = resolveFinalBase()
                    val searchHtml = fetchHtml("$base/?s=" + name.trim().replace(" ", "+"), "$base/")
                    val movieUrl = findMovieLink(Jsoup.parse(searchHtml, base), name, base)
                    if (movieUrl == null) { call.respondText("{\"success\":false,\"error\":\"NO_MOVIE_LINK\"}", ContentType.Application.Json); return@get }
                    val movieHtml = fetchHtml(movieUrl, "$base/")
                    val watchUrl = Jsoup.parse(movieHtml, movieUrl).selectFirst("a.watch__btn")?.absUrl("href")
                        ?: (movieUrl.trimEnd('/') + "/watch")
                    val watchHtml = fetchHtml(watchUrl, movieUrl)
                    val watchDoc = Jsoup.parse(watchHtml, watchUrl)
                    val playUrl = watchDoc.selectFirst("iframe#videoIframe")?.absUrl("src")
                    if (playUrl == null) { call.respondText("{\"success\":false,\"error\":\"NO_IFRAME\"}", ContentType.Application.Json); return@get }
                    val altServers = watchDoc.select("#watch li[data-link]").mapNotNull { it.attr("data-link").takeIf { s -> s.isNotBlank() } }
                    val json = "{\"success\":true,\"base\":\"" + jsonEscape(base) + "\",\"playUrl\":\"" + jsonEscape(playUrl) + "\",\"altServers\":[" +
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
                    val base = resolveFinalBase()
                    val searchHtml = fetchHtml("$base/?s=" + name.trim().replace(" ", "+"), "$base/")
                    val movieUrl = findMovieLink(Jsoup.parse(searchHtml, base), name, base)
                    if (movieUrl == null) { call.respondText("{\"step\":\"search\",\"error\":\"NO_MOVIE_LINK\",\"base\":\"$base\"}", ContentType.Application.Json); return@get }
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
                        "\"base\":\"${jsonEscape(base)}\"," +
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
