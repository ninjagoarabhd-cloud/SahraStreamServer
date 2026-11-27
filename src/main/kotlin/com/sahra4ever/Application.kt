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

// بوابات أكوام (من arprov + النطاق الذي أكدته أنت)
private val AKWAM_GATEWAYS = listOf("https://akwams.org", "https://akwam.ss")

private val httpClient = HttpClient(OkHttp) {
    engine {
        config {
            followRedirects(true)
            retryOnConnectionFailure(true)
            connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            readTimeout(40, java.util.concurrent.TimeUnit.SECONDS)
            writeTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
        }
    }
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
    if (top != null && top != host && (counts[top] ?: 0) >= 5) host = top
    synchronized(BASE_LOCK) { cachedFinalBase = host to now; BASE = host }
    return host
}

// ✅ نفس حركة عربسيد لأكوام: بوابة → redirects → أغلبية الروابط → كاش
private var cachedAkwamBase: Pair<String, Long>? = null
private suspend fun resolveAkwamBase(): String {
    val now = System.currentTimeMillis()
    cachedAkwamBase?.let { (b, ts) -> if (now - ts < 600_000) return b }
    for (g in AKWAM_GATEWAYS) {
        try {
            val resp = httpClient.get("$g/")
            val finalUrl = resp.request.url.toString()
            var host = try { URI(finalUrl).let { "${it.scheme}://${it.host}" } } catch (_: Exception) { g }
            val body = resp.bodyAsText()
            val low = body.lowercase()
            if (low.contains("just a moment") || low.contains("challenge-platform") || low.contains("cf-chl")) continue
            if (body.length < 500) continue
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
            if (top != null && top != host && (counts[top] ?: 0) >= 5) host = top
            cachedAkwamBase = host to now
            return host
        } catch (_: Exception) {}
    }
    throw IllegalStateException("AKWAM_ALL_GATEWAYS_FAILED")
}

private suspend fun fetchHtml(url: String, referer: String? = null): String {
    var last: Exception? = null
    for (i in 0 until 3) {
        try {
            val resp = httpClient.get(url) { if (referer != null) header("Referer", referer) }
            val body = resp.bodyAsText()
            val low = body.lowercase()
            if (low.contains("just a moment") || low.contains("challenge-platform") || low.contains("cf-chl")) {
                throw IllegalStateException("CLOUDFLARE len=${body.length} @ $url")
            }
            return body
        } catch (e: Exception) {
            last = e
            kotlinx.coroutines.delay(700L * (i + 1))
        }
    }
    throw last ?: IllegalStateException("fetch failed @ $url")
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

// ===== فك Packer (لمضيفات أكوام StreamWish / StreamRuby) =====
private const val PACKER_HEADER = "eval(function(p,a,c,k,e,d){while(c--)if(k[c])p=p.replace(new RegExp('\\\\b'+c.toString(a)+'\\\\b','g'),k[c]);return p}('"
private const val PACKER_SPLIT_END = "'.split('|'))"
private fun unpackPacker(html: String): String? {
    val start = html.indexOf(PACKER_HEADER); if (start < 0) return null
    val end = html.indexOf(PACKER_SPLIT_END, start); if (end < 0) return null
    val sepStart = html.lastIndexOf("',", end); if (sepStart < start) return null
    val sep = html.substring(sepStart + 2, end)
    val m = Regex("^(\\d+),(\\d+),'([\\s\\S]*)$").find(sep) ?: return null
    val a = m.groupValues[1].toInt(); val c = m.groupValues[2].toInt()
    val k = m.groupValues[3].split("|"); if (c > k.size) return null
    var p = html.substring(start + PACKER_HEADER.length, sepStart)
    for (i in c - 1 downTo 0) { val ki = k.getOrNull(i) ?: continue; if (ki.isEmpty()) continue; p = p.replace(Regex("\\b" + i.toString(a) + "\\b"), ki) }
    return p
}
private suspend fun resolveStreamWish(embedUrl: String): Pair<String, String>? = try {
    val page = fetchHtml(embedUrl, embedUrl)
    val unpacked = unpackPacker(page) ?: page
    val block = Regex("var links=\\{([\\s\\S]*?)\\};").find(unpacked)?.groupValues?.get(1)
    fun lv(n: String) = block?.let { Regex("\"$n\":\"([^\"]*)\"").find(it)?.groupValues?.get(1) }
    val raw = lv("hls4") ?: lv("hls2") ?: lv("hls3")
        ?: Regex("""file\s*:\s*["']([^"']+\.m3u8[^"']*)["']""").find(unpacked)?.groupValues?.get(1)
    if (raw != null) {
        val url = if (raw.startsWith("http")) raw else "https://" + URI(embedUrl).host + raw
        url to embedUrl
    } else null
} catch (_: Exception) { null }
private suspend fun resolveStreamRuby(embedUrl: String): Pair<String, String>? = try {
    val page = fetchHtml(embedUrl, embedUrl)
    val unpacked = unpackPacker(page) ?: page
    val m3u8 = Regex("""file\s*:\s*["']([^"']+\.m3u8[^"']*)["']""").find(unpacked)?.groupValues?.get(1)
        ?: Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""").find(unpacked)?.groupValues?.get(0)
    if (m3u8 != null) m3u8 to embedUrl else null
} catch (_: Exception) { null }

// ===== استخراج أكوام =====
private suspend fun resolveAkwam(query: String): Map<String, String>? {
    val base = resolveAkwamBase()
    val searchHtml = fetchHtml("$base/search?q=" + query.trim().replace(" ", "+"), "$base/")
    val doc = Jsoup.parse(searchHtml, base)
    val box = doc.select("div.entry-box").firstOrNull { el ->
        val href = el.selectFirst("a.box, a")?.absUrl("href") ?: return@firstOrNull false
        !href.contains("/games/") && !href.contains("/programs/") &&
            (el.selectFirst("picture > img, img")?.attr("alt") ?: el.selectFirst("h3, .entry-title")?.text() ?: "").contains(query, true)
    } ?: doc.select("div.entry-box").firstOrNull { el ->
        val h = el.selectFirst("a.box, a")?.absUrl("href") ?: ""; h.isNotEmpty() && !h.contains("/games/") && !h.contains("/programs/")
    }
    val movieUrl = box?.selectFirst("a.box, a")?.absUrl("href") ?: return null
    val baseUrl = movieUrl.trimEnd('/')
    val cand = mutableListOf<String>()
    try { Jsoup.parse(fetchHtml("$baseUrl/watch", baseUrl), "$baseUrl/watch").select("iframe[src]").forEach { val s = it.absUrl("src"); if (s.startsWith("http") && !s.contains("ads") && !s.contains("facebook")) cand.add(s) } } catch (_: Exception) {}
    try {
        val mirror = Regex("(?i)(mixdrop|dood|streamruby|send\\.cm|uqload|streamwish|byseraguci|vidhide|voe|hgcloud|mp4upload|vidmoly)")
        Jsoup.parse(fetchHtml("$baseUrl/download", baseUrl), "$baseUrl/download").select("a[href]").forEach { val h = it.absUrl("href"); if (h.startsWith("http") && mirror.containsMatchIn(h)) cand.add(h) }
    } catch (_: Exception) {}
    for (c in cand.distinct()) {
        val host = try { URI(c).host?.removePrefix("www.") ?: "" } catch (_: Exception) { "" }
        val r = when {
            host.contains("streamwish") || host.contains("hgcloud") || host.contains("hglink") || host.contains("hanerix") || host.contains("vibuxer") || host.contains("hgplaycdn") || host.contains("strwish") || host.contains("awish") || host.contains("dwish") || host.contains("mwish") -> resolveStreamWish(c)
            host.contains("streamruby") || host.contains("stmruby") -> resolveStreamRuby(c)
            else -> null
        }
        if (r != null) return mapOf("source" to "akwam", "kind" to "m3u8", "url" to r.first, "referer" to r.second)
    }
    return null
}

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

            // 🧪 اختبار بوابات أكوام + النطاق الحي المكتشف من Render
            get("/api/test-akwam") {
                val results = mutableListOf<String>()
                for (g in AKWAM_GATEWAYS) {
                    try {
                        val resp = httpClient.get("$g/")
                        val body = resp.bodyAsText()
                        val low = body.lowercase()
                        val challenge = low.contains("just a moment") || low.contains("challenge-platform") || low.contains("cf-chl")
                        results.add("{\"gateway\":\"$g\",\"code\":${resp.status.value},\"len\":${body.length},\"challenge\":$challenge}")
                    } catch (e: Exception) {
                        results.add("{\"gateway\":\"$g\",\"error\":\"${jsonEscape(e.message ?: "unknown")}\"}")
                    }
                }
                val resolved = try { "\"${resolveAkwamBase()}\"" } catch (e: Exception) { "null" }
                call.respondText("{\"gateways\":[${results.joinToString(",")}],\"resolvedBase\":$resolved}", ContentType.Application.Json)
            }

            // 🧪 استخراج أكوام كامل (للاختبار قبل ربط fallback)
            get("/api/akwam") {
                val name = call.request.queryParameters["name"] ?: "Inception"
                try {
                    val r = resolveAkwam(name)
                    if (r == null) { call.respondText("{\"success\":false,\"error\":\"AKWAM_NO_LINK\"}", ContentType.Application.Json); return@get }
                    call.respondText("{\"success\":true,\"source\":\"akwam\",\"kind\":\"m3u8\",\"m3u8\":\"${jsonEscape(r["url"]!!)}\",\"referer\":\"${jsonEscape(r["referer"]!!)}\"}", ContentType.Application.Json)
                } catch (e: Exception) {
                    call.respondText("{\"success\":false,\"error\":\"${jsonEscape(e.message ?: "unknown")}\"}", ContentType.Application.Json)
                }
            }

            // 🔍 تشخيص أكوام: يعرض نتائج البحث + المضيفات الموجودة فعلاً
            get("/api/debug-akwam") {
                val name = call.request.queryParameters["name"] ?: "Inception"
                try {
                    val base = resolveAkwamBase()
                    val searchUrl = "$base/search?q=" + name.trim().replace(" ", "+")
                    val html = fetchHtml(searchUrl, "$base/")
                    val doc = Jsoup.parse(html, base)
                    val boxes = doc.select("div.entry-box")
                    val boxInfo = boxes.take(5).map { el ->
                        val t = el.selectFirst("picture > img, img")?.attr("alt") ?: el.selectFirst("h3, .entry-title")?.text() ?: ""
                        val h = el.selectFirst("a.box, a")?.absUrl("href") ?: ""
                        "{\"title\":\"${jsonEscape(t)}\",\"href\":\"${jsonEscape(h)}\"}"
                    }
                    val box = boxes.firstOrNull { el ->
                        val href = el.selectFirst("a.box, a")?.absUrl("href") ?: return@firstOrNull false
                        !href.contains("/games/") && !href.contains("/programs/") &&
                            (el.selectFirst("picture > img, img")?.attr("alt") ?: el.selectFirst("h3, .entry-title")?.text() ?: "").contains(name, true)
                    } ?: boxes.firstOrNull { el ->
                        val h = el.selectFirst("a.box, a")?.absUrl("href") ?: ""; h.isNotEmpty() && !h.contains("/games/") && !h.contains("/programs/")
                    }
                    val movieUrl = box?.selectFirst("a.box, a")?.absUrl("href")
                    val watchIframes = mutableListOf<String>()
                    val mirrors = mutableListOf<String>()
                    if (movieUrl != null) {
                        val b = movieUrl.trimEnd('/')
                        try { Jsoup.parse(fetchHtml("$b/watch", b), "$b/watch").select("iframe[src]").forEach { watchIframes.add(it.absUrl("src")) } } catch (_: Exception) {}
                        try {
                            val mr = Regex("(?i)(mixdrop|dood|streamruby|send\\.cm|uqload|streamwish|byseraguci|vidhide|voe|hgcloud|mp4upload|vidmoly)")
                            Jsoup.parse(fetchHtml("$b/download", b), "$b/download").select("a[href]").forEach { val h = it.absUrl("href"); if (mr.containsMatchIn(h)) mirrors.add(h) }
                        } catch (_: Exception) {}
                    }
                    val json = "{" +
                        "\"base\":\"${jsonEscape(base)}\"," +
                        "\"htmlLen\":${html.length}," +
                        "\"containsQuery\":${html.lowercase().contains(name.lowercase())}," +
                        "\"boxCount\":${boxes.size}," +
                        "\"boxes\":[${boxInfo.joinToString(",")}]," +
                        "\"movieUrl\":" + (if (movieUrl != null) "\"${jsonEscape(movieUrl)}\"" else "null") + "," +
                        "\"watchIframes\":[${watchIframes.joinToString(",") { "\"${jsonEscape(it)}\"" }}]," +
                        "\"downloadMirrors\":[${mirrors.joinToString(",") { "\"${jsonEscape(it)}\"" }}]" +
                        "}"
                    call.respondText(json, ContentType.Application.Json)
                } catch (e: Exception) {
                    call.respondText("{\"error\":\"${jsonEscape(e.message ?: "unknown")}\"}", ContentType.Application.Json)
                }
            }

            get("/api/debug-search") {
                val name = call.request.queryParameters["name"] ?: "Inception"
                try {
                    val base = resolveFinalBase()
                    val searchUrl = "$base/?s=" + name.trim().replace(" ", "+")
                    val resp = httpClient.get(searchUrl) {
                        header("User-Agent", UA)
                        header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                        header("Referer", "$base/")
                    }
                    val html = resp.bodyAsText()
                    val doc = Jsoup.parse(html, base)
                    val allLinks = doc.select("a[href]").take(50).map { a ->
                        val href = a.absUrl("href"); val txt = a.text().take(80); "$href | $txt"
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
                    val guess = movieUrl.trimEnd('/') + "/watch"
                    var watchUrl = guess
                    var watchDoc: Document? = try {
                        val d = Jsoup.parse(fetchHtml(guess, movieUrl), guess)
                        if (d.selectFirst("iframe#videoIframe") != null) d else null
                    } catch (_: Exception) { null }
                    if (watchDoc == null) {
                        val movieHtml = fetchHtml(movieUrl, "$base/")
                        watchUrl = Jsoup.parse(movieHtml, movieUrl).selectFirst("a.watch__btn")?.absUrl("href") ?: guess
                        watchDoc = Jsoup.parse(fetchHtml(watchUrl, movieUrl), watchUrl)
                    }
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
