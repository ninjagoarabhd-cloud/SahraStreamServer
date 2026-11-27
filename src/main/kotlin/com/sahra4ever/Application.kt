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
import java.net.URLDecoder
import java.util.Base64

private const val UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

private val ARABSEED_DOMAINS = listOf("https://arabseed.trade")
private var BASE = ARABSEED_DOMAINS.first()

private val EGYDEAD_GATEWAYS = listOf("https://tv10.egydead.live")

private val MYCIMA_GATEWAYS = listOf(
    "https://mycima.gdn",
    "https://mycima.agency",
    "https://mycima.study",
    "https://mycima.house"
)

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

private fun isRealChallenge(body: String): Boolean {
    val low = body.lowercase()
    return low.contains("cf-chl-c") || low.contains("challenge-form") ||
        low.contains("just a moment") ||
        (low.contains("checking your browser") && body.length < 20000)
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
    if (isRealChallenge(body)) {
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

private var cachedEgyDeadBase: Pair<String, Long>? = null
private suspend fun resolveEgyDeadBase(): String {
    val now = System.currentTimeMillis()
    cachedEgyDeadBase?.let { (b, ts) -> if (now - ts < 600_000) return b }
    for (g in EGYDEAD_GATEWAYS) {
        try {
            val resp = httpClient.get("$g/")
            val finalUrl = resp.request.url.toString()
            var host = try { URI(finalUrl).let { "${it.scheme}://${it.host}" } } catch (_: Exception) { g }
            val body = resp.bodyAsText()
            if (isRealChallenge(body)) continue
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
            cachedEgyDeadBase = host to now
            return host
        } catch (_: Exception) {}
    }
    throw IllegalStateException("EGYDEAD_ALL_GATEWAYS_FAILED")
}

// ⚠️ ملاحظة: حالياً resolveMyCimaBase يأخذ أول بوابة تعمل (200 + حجم > 5000)
// لاحقاً سنحدّثها لتستخدم نفس منطق عرب سيد: تحليل أغلبية الروابط في الصفحة
// لاكتشاف النطاق الحقيقي (مثل mycima.house الذي ظهر في RSS)
private var cachedMyCimaBase: Pair<String, Long>? = null
private suspend fun resolveMyCimaBase(): String {
    val now = System.currentTimeMillis()
    cachedMyCimaBase?.let { (b, ts) -> if (now - ts < 600_000) return b }
    for (g in MYCIMA_GATEWAYS) {
        try {
            val resp = httpClient.get("$g/") {
                header("User-Agent", UA)
                header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            }
            if (resp.status.value != 200) continue
            val body = resp.bodyAsText()
            if (body.length < 5000) continue
            if (isRealChallenge(body)) continue
            val host = try { URI(resp.request.url.toString()).let { "${it.scheme}://${it.host}" } } catch (_: Exception) { g }
            cachedMyCimaBase = host to now
            return host
        } catch (_: Exception) {}
    }
    throw IllegalStateException("MYCIMA_ALL_GATEWAYS_FAILED")
}

private suspend fun fetchHtml(url: String, referer: String? = null): String {
    var last: Exception? = null
    for (i in 0 until 3) {
        try {
            val resp = httpClient.get(url) { if (referer != null) header("Referer", referer) }
            val body = resp.bodyAsText()
            if (isRealChallenge(body)) {
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

private suspend fun postHtml(url: String, referer: String? = null): String {
    var last: Exception? = null
    for (i in 0 until 3) {
        try {
            val resp = httpClient.post(url) {
                if (referer != null) header("Referer", referer)
                header("Content-Type", "application/x-www-form-urlencoded")
                setBody("View=1")
            }
            val body = resp.bodyAsText()
            if (isRealChallenge(body)) {
                throw IllegalStateException("CLOUDFLARE len=${body.length} @ $url")
            }
            return body
        } catch (e: Exception) {
            last = e
            kotlinx.coroutines.delay(700L * (i + 1))
        }
    }
    throw last ?: IllegalStateException("post failed @ $url")
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

private val STREAMHG_DOMAINS = listOf("https://hanerix.com", "https://vibuxer.com", "https://hgplaycdn.com")
private val STREAMHG_EMBED_HOSTS = listOf(
    "hanerix.com", "vibuxer.com", "hgplaycdn.com",
    "hgcloud.to", "hglink.to", "streamwish.to", "streamwish.com", "streamwish.net",
    "strwish.com", "awish.pro", "dwish.pro", "mwish.pro"
)

private suspend fun resolveStreamHg(fileId: String, originalEmbed: String? = null): Pair<String, String>? {
    val urls = mutableListOf<String>()
    if (originalEmbed != null) urls.add(originalEmbed)
    STREAMHG_DOMAINS.forEach { urls.add("$it/e/$fileId") }
    for (u in urls) {
        try {
            val page = fetchHtml(u, u)
            val unpacked = unpackPacker(page) ?: page
            val block = Regex("var links=\\{([\\s\\S]*?)\\};").find(unpacked)?.groupValues?.get(1)
            fun lv(n: String) = block?.let { Regex("\"$n\":\"([^\"]*)\"").find(it)?.groupValues?.get(1) }
            val raw = lv("hls4") ?: lv("hls2") ?: lv("hls3")
                ?: Regex("""file\s*:\s*["']([^"']+\.m3u8[^"']*)["']""").find(unpacked)?.groupValues?.get(1)
            if (raw != null) {
                val host = URI(u).let { "${it.scheme}://${it.host}" }
                val url = if (raw.startsWith("http")) raw else "$host$raw"
                return url to u
            }
        } catch (_: Exception) {}
    }
    return null
}

private suspend fun resolveMixDrop(embedUrl: String): Pair<String, String>? {
    return try {
        val page = fetchHtml(embedUrl, embedUrl)
        val unpacked = unpackPacker(page) ?: page
        val wurl = Regex("""wurl\s*=\s*["']([^"']+)["']""").find(unpacked)?.groupValues?.get(1) ?: return null
        val url = if (wurl.startsWith("//")) "https:$wurl" else wurl
        url to embedUrl
    } catch (_: Exception) { null }
}

private suspend fun resolveMegaMax(embedUrl: String): Pair<String, String>? {
    val pageUrl = embedUrl.replace("/download/", "/iframe/")
    return try {
        val html = fetchHtml(pageUrl, EGYDEAD_GATEWAYS.first())
        val version = Regex(""""version"\s*:\s*"([^"]+)"""").find(html)?.groupValues?.get(1)
        val rawComp = Regex(""""component"\s*:\s*"([^"]+)"""").find(html)?.groupValues?.get(1) ?: "files/mirror/video"
        val component = rawComp.replace("\\/", "/")
        val jsonText = if (!version.isNullOrEmpty()) {
            httpClient.get(pageUrl) {
                header("Referer", pageUrl)
                header("X-Requested-With", "XMLHttpRequest")
                header("X-Inertia", "true")
                header("X-Inertia-Version", version)
                header("X-Inertia-Partial-Component", component)
                header("X-Inertia-Partial-Data", "streams")
            }.bodyAsText()
        } else html
        val links = Regex(""""link"\s*:\s*"([^"]+)"""").findAll(jsonText)
            .map { it.groupValues[1].replace("\\/", "/") }.toList()
        for (raw in links) {
            val link = if (raw.startsWith("//")) "https:$raw" else raw
            val host = try { URI(link).host?.removePrefix("www.") ?: "" } catch (_: Exception) { "" }
            val r = when {
                host.contains("streamruby") || host.contains("stmruby") -> resolveStreamRuby(link)
                STREAMHG_EMBED_HOSTS.any { host.endsWith(it) } || link.contains("streamwish") -> {
                    val fid = link.substringAfterLast('/').substringBefore('?')
                    if (fid.isNotEmpty()) resolveStreamHg(fid, link) else null
                }
                else -> null
            }
            if (r != null) return r
        }
        null
    } catch (_: Exception) { null }
}

private suspend fun resolveEgyDead(query: String): Map<String, String>? {
    val base = resolveEgyDeadBase()
    val searchHtml = fetchHtml("$base/?s=" + query.trim().replace(" ", "+"), "$base/")
    val sdoc = Jsoup.parse(searchHtml, base)

    val movieUrl = sdoc.select("li.movieItem, div.BlockItem").mapNotNull { el ->
        val a = if (el.tagName() == "a") el else el.selectFirst("a") ?: return@mapNotNull null
        val href = a.absUrl("href")
        val title = a.selectFirst("h1, h2, h3, .BottomTitle")?.text() ?: a.attr("title")
        if (href.isEmpty() || href.contains("/category/") || href.contains("/page/") || href.contains("/tag/")) return@mapNotNull null
        if (title.contains(query, true) || href.contains(query.replace(" ", "-"), true)) href else null
    }.firstOrNull() ?: findMovieLink(sdoc, query, base) ?: return null

    val doc = try {
        val d = Jsoup.parse(postHtml(movieUrl, "$base/"), movieUrl)
        if (d.selectFirst("ul.serversList, div.ServersList, .donwload-servers-list, [data-link]") != null) d
        else Jsoup.parse(fetchHtml(movieUrl, "$base/"), movieUrl)
    } catch (_: Exception) {
        Jsoup.parse(fetchHtml(movieUrl, "$base/"), movieUrl)
    }

    val cand = mutableListOf<String>()
    doc.select("ul.serversList [data-link], div.ServersList [data-link], ul.serversList > li, div.ServersList li").forEach { el ->
        val u = el.attr("data-link").trim().ifEmpty { el.attr("href").trim() }
        if (u.isNotEmpty() && !u.startsWith("javascript")) cand.add(u)
    }
    doc.select(".donwload-servers-list > li a, ul.download a").forEach { el ->
        val u = el.attr("href").trim()
        if (u.isNotEmpty() && !u.startsWith("javascript") && !u.contains("egydead")) cand.add(u)
    }
    doc.select("iframe").forEach { ifr ->
        val s = ifr.attr("src").trim()
        if (s.isNotEmpty() && !s.contains("youtube") && !s.contains("google") && !s.contains("egydead") && !s.startsWith("javascript")) cand.add(s)
    }

    for (c in cand.distinct()) {
        val link = if (c.startsWith("//")) "https:$c" else c
        val host = try { URI(link).host?.removePrefix("www.") ?: "" } catch (_: Exception) { "" }
        val lower = link.lowercase()
        val r = when {
            lower.contains("/iframe/") || lower.contains("/download/") || lower.contains("megamax") -> resolveMegaMax(link)
            host.contains("mixdrop") -> resolveMixDrop(link)
            host.contains("streamruby") || host.contains("stmruby") -> resolveStreamRuby(link)
            STREAMHG_EMBED_HOSTS.any { host.endsWith(it) } || link.contains("streamwish") -> {
                val fid = link.substringAfterLast('/').substringBefore('?')
                if (fid.isNotEmpty()) resolveStreamHg(fid, link) else null
            }
            else -> null
        }
        if (r != null) return mapOf(
            "source" to "egydead",
            "kind" to (if (r.first.contains(".m3u8")) "m3u8" else "video"),
            "url" to r.first,
            "referer" to r.second
        )
    }
    return null
}

private fun decodeHexStr(hex: String): String = try {
    val sb = StringBuilder()
    for (i in 0 until hex.length step 2) {
        val byte = hex.substring(i, i + 2)
        sb.append(byte.toInt(16).toChar())
    }
    sb.toString()
} catch (_: Exception) { "" }

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

            get("/api/test-egydead") {
                try {
                    val resp = httpClient.get("${EGYDEAD_GATEWAYS.first()}/")
                    val body = resp.bodyAsText()
                    val challenge = isRealChallenge(body)
                    call.respondText("{\"code\":${resp.status.value},\"len\":${body.length},\"challenge\":$challenge}", ContentType.Application.Json)
                } catch (e: Exception) {
                    call.respondText("{\"error\":\"${jsonEscape(e.message ?: "unknown")}\"}", ContentType.Application.Json)
                }
            }

            get("/api/egydead") {
                val name = call.request.queryParameters["name"] ?: "Inception"
                try {
                    val r = resolveEgyDead(name)
                    if (r == null) { call.respondText("{\"success\":false,\"error\":\"EGYDEAD_NO_LINK\"}", ContentType.Application.Json); return@get }
                    call.respondText("{\"success\":true,\"source\":\"egydead\",\"kind\":\"${r["kind"]}\",\"m3u8\":\"${jsonEscape(r["url"]!!)}\",\"referer\":\"${jsonEscape(r["referer"]!!)}\"}", ContentType.Application.Json)
                } catch (e: Exception) {
                    call.respondText("{\"success\":false,\"error\":\"${jsonEscape(e.message ?: "unknown")}\"}", ContentType.Application.Json)
                }
            }

            get("/api/debug-host") {
                val url = call.request.queryParameters["url"]
                if (url.isNullOrBlank()) { call.respondText("{\"error\":\"no url\"}", ContentType.Application.Json); return@get }
                try {
                    val page = fetchHtml(url, url)
                    val unpacked = unpackPacker(page) ?: page
                    val block = Regex("var links=\\{([\\s\\S]*?)\\};").find(unpacked)?.groupValues?.get(1)
                    fun lv(n: String) = block?.let { Regex("\"$n\":\"([^\"]*)\"").find(it)?.groupValues?.get(1) }
                    val hls4 = lv("hls4"); val hls2 = lv("hls2"); val hls3 = lv("hls3")
                    val fileM3u8 = Regex("""file\s*:\s*["']([^"']+\.m3u8[^"']*)["']""").find(unpacked)?.groupValues?.get(1)
                    val wurl = Regex("""wurl\s*=\s*["']([^"']+)["']""").find(unpacked)?.groupValues?.get(1)
                    val anyM3u8 = Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""").find(unpacked)?.groupValues?.get(0)
                    val json = "{" +
                        "\"len\":${page.length}," +
                        "\"hasPacker\":${unpackPacker(page) != null}," +
                        "\"unpackedLen\":${unpacked.length}," +
                        "\"hls4\":" + (if (hls4 != null) "\"${jsonEscape(hls4.take(120))}\"" else "null") + "," +
                        "\"hls2\":" + (if (hls2 != null) "\"${jsonEscape(hls2.take(120))}\"" else "null") + "," +
                        "\"hls3\":" + (if (hls3 != null) "\"${jsonEscape(hls3.take(120))}\"" else "null") + "," +
                        "\"fileM3u8\":" + (if (fileM3u8 != null) "\"${jsonEscape(fileM3u8.take(120))}\"" else "null") + "," +
                        "\"wurl\":" + (if (wurl != null) "\"${jsonEscape(wurl.take(120))}\"" else "null") + "," +
                        "\"anyM3u8\":" + (if (anyM3u8 != null) "\"${jsonEscape(anyM3u8.take(120))}\"" else "null") + "," +
                        "\"preview\":\"${jsonEscape(unpacked.take(250))}\"" +
                        "}"
                    call.respondText(json, ContentType.Application.Json)
                } catch (e: Exception) {
                    call.respondText("{\"error\":\"${jsonEscape(e.message ?: "unknown")}\"}", ContentType.Application.Json)
                }
            }

            get("/api/debug-egydead") {
                val name = call.request.queryParameters["name"] ?: "Inception"
                try {
                    val base = resolveEgyDeadBase()
                    val html = fetchHtml("$base/?s=" + name.trim().replace(" ", "+"), "$base/")
                    val doc = Jsoup.parse(html, base)
                    val links = doc.select("a[href]").take(30).map { a -> "\"${jsonEscape(a.absUrl("href") + " | " + a.text().take(60))}\"" }
                    val movieUrl = findMovieLink(doc, name, base)
                    val servers = mutableListOf<String>()
                    if (movieUrl != null) {
                        try { Jsoup.parse(fetchHtml(movieUrl, "$base/"), movieUrl).select("li[data-link], li[data-src], iframe[src]").forEach { servers.add(it.attr("data-link").ifBlank { it.attr("data-src") }.ifBlank { it.absUrl("src") }) } } catch (_: Exception) {}
                        try {
                            val d = Jsoup.parse(postHtml(movieUrl, "$base/"), movieUrl)
                            d.select("ul.serversList [data-link], div.ServersList [data-link], .donwload-servers-list a, iframe[src]").forEach {
                                servers.add(it.attr("data-link").ifBlank { it.attr("href").ifBlank { it.absUrl("src") } })
                            }
                        } catch (_: Exception) {}
                    }
                    call.respondText("{\"base\":\"${jsonEscape(base)}\",\"containsQuery\":${html.lowercase().contains(name.lowercase())},\"movieUrl\":" + (if (movieUrl != null) "\"${jsonEscape(movieUrl)}\"" else "null") + ",\"links\":[${links.joinToString(",")}],\"servers\":[${servers.joinToString(",") { "\"${jsonEscape(it)}\"" }}]}", ContentType.Application.Json)
                } catch (e: Exception) {
                    call.respondText("{\"error\":\"${jsonEscape(e.message ?: "unknown")}\"}", ContentType.Application.Json)
                }
            }

            get("/api/test-mycima") {
                val results = mutableListOf<String>()
                for (g in MYCIMA_GATEWAYS) {
                    try {
                        val resp = httpClient.get("$g/") {
                            header("User-Agent", UA)
                            header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                        }
                        val body = resp.bodyAsText()
                        val challenge = isRealChallenge(body)
                        val blocked = body.contains("verifying you are not a bot", true) || body.contains("checking your browser", true)
                        results.add("{\"gateway\":\"$g\",\"code\":${resp.status.value},\"len\":${body.length},\"challenge\":$challenge,\"blocked\":$blocked}")
                    } catch (e: Exception) {
                        results.add("{\"gateway\":\"$g\",\"error\":\"${jsonEscape(e.message ?: "unknown")}\"}")
                    }
                }
                val resolved = try { "\"${resolveMyCimaBase()}\"" } catch (e: Exception) { "null" }
                call.respondText("{\"gateways\":[${results.joinToString(",")}],\"resolvedBase\":$resolved}", ContentType.Application.Json)
            }

            get("/api/debug-mycima") {
                val name = call.request.queryParameters["name"] ?: "Inception"
                try {
                    val base = resolveMyCimaBase()
                    val encoded = java.net.URLEncoder.encode(name.trim(), "UTF-8")
                    val searchUrl = "$base/?s=$encoded"
                    val searchHtml = fetchHtml(searchUrl, "$base/")
                    val sdoc = Jsoup.parse(searchHtml, base)

                    val searchResults = sdoc.select("div#MainFiltar > .GridItem, .GridItem").take(5).mapNotNull { el ->
                        val a = el.selectFirst("a") ?: return@mapNotNull null
                        val title = a.selectFirst("strong")?.text()?.trim() ?: a.attr("title").trim()
                        val href = a.absUrl("href")
                        if (title.isBlank() || href.isBlank()) return@mapNotNull null
                        "{\"title\":\"${jsonEscape(title)}\",\"href\":\"${jsonEscape(href)}\"}"
                    }

                    val movieUrl = sdoc.select("div#MainFiltar > .GridItem, .GridItem").mapNotNull { el ->
                        val a = el.selectFirst("a") ?: return@mapNotNull null
                        val title = a.selectFirst("strong")?.text()?.trim() ?: a.attr("title").trim()
                        val href = a.absUrl("href")
                        if (href.isBlank()) return@mapNotNull null
                        if (title.contains(name, true) || href.contains(name.replace(" ", "-"), true)) href else null
                    }.firstOrNull()

                    var movieInfo: String = "null"
                    var ajaxTest: String = "null"
                    var iframeCount = 0
                    var dataIdCount = 0
                    var dataWatchCount = 0
                    var embedUrls = mutableListOf<String>()
                    var playUrls = mutableListOf<String>()
                    var mohixFound: String? = null

                    if (movieUrl != null) {
                        try {
                            val movieHtml = fetchHtml(movieUrl, "$base/")
                            val mdoc = Jsoup.parse(movieHtml, movieUrl)
                            iframeCount = mdoc.select("iframe[src]").size
                            dataIdCount = mdoc.select("[data-id]").size
                            dataWatchCount = mdoc.select("[data-watch]").size

                            Regex("""/e/(\d+)/?""").findAll(movieHtml).take(3).forEach {
                                embedUrls.add("$base/e/${it.groupValues[1]}/")
                            }
                            Regex("""/play/([A-Za-z0-9+/=_-]+)""").findAll(movieHtml).take(3).forEach {
                                val b64 = it.groupValues[1]
                                try {
                                    val decoded = String(Base64.getDecoder().decode(b64.replace("_", "/").replace("-", "+")))
                                    playUrls.add(decoded)
                                } catch (_: Exception) {
                                    playUrls.add("DECODE_FAILED:$b64")
                                }
                            }
                            mohixFound = Regex("""(?:const|var)\s+Mohix\s*=\s*["']([0-9a-fA-F]+)["']""").find(movieHtml)?.groupValues?.get(1)

                            movieInfo = "{\"htmlLen\":${movieHtml.length},\"iframeCount\":$iframeCount,\"dataIdCount\":$dataIdCount,\"dataWatchCount\":$dataWatchCount,\"mohixFound\":" +
                                (if (mohixFound != null) "\"${jsonEscape(mohixFound!!.take(50))}\"" else "null") + "}"

                            val firstId = mdoc.select("[data-id]").firstOrNull()?.attr("data-id")
                            if (!firstId.isNullOrBlank()) {
                                try {
                                    val ajaxUrl = "$base/wp-admin/admin-ajax.php"
                                    val ajaxResp = httpClient.post(ajaxUrl) {
                                        header("Referer", movieUrl)
                                        header("X-Requested-With", "XMLHttpRequest")
                                        header("Content-Type", "application/x-www-form-urlencoded")
                                        setBody("action=get_player&server=$firstId")
                                    }
                                    val ajaxBody = ajaxResp.bodyAsText()
                                    val ajaxDoc = Jsoup.parse(ajaxBody)
                                    val iframeSrc = ajaxDoc.selectFirst("iframe")?.attr("src") ?: ""
                                    ajaxTest = "{\"code\":${ajaxResp.status.value},\"len\":${ajaxBody.length},\"firstId\":\"$firstId\",\"iframeSrc\":\"${jsonEscape(iframeSrc.take(150))}\"}"
                                } catch (e: Exception) {
                                    ajaxTest = "{\"error\":\"${jsonEscape(e.message ?: "unknown")}\"}"
                                }
                            }
                        } catch (e: Exception) {
                            movieInfo = "{\"error\":\"${jsonEscape(e.message ?: "unknown")}\"}"
                        }
                    }

                    val json = "{" +
                        "\"base\":\"${jsonEscape(base)}\"," +
                        "\"searchUrl\":\"${jsonEscape(searchUrl)}\"," +
                        "\"searchHtmlLen\":${searchHtml.length}," +
                        "\"containsQuery\":${searchHtml.lowercase().contains(name.lowercase())}," +
                        "\"searchResults\":[${searchResults.joinToString(",")}]," +
                        "\"movieUrl\":" + (if (movieUrl != null) "\"${jsonEscape(movieUrl)}\"" else "null") + "," +
                        "\"movieInfo\":$movieInfo," +
                        "\"embedUrls\":[${embedUrls.joinToString(",") { "\"${jsonEscape(it)}\"" }}]," +
                        "\"playUrls\":[${playUrls.joinToString(",") { "\"${jsonEscape(it)}\"" }}]," +
                        "\"ajaxTest\":$ajaxTest" +
                        "}"
                    call.respondText(json, ContentType.Application.Json)
                } catch (e: Exception) {
                    call.respondText("{\"error\":\"${jsonEscape(e.message ?: "unknown")}\"}", ContentType.Application.Json)
                }
            }

            get("/api/debug-mycima-search") {
                val name = call.request.queryParameters["name"] ?: "Inception"
                try {
                    val base = resolveMyCimaBase()
                    val encoded = java.net.URLEncoder.encode(name.trim(), "UTF-8")
                    val html = fetchHtml("$base/?s=$encoded", "$base/")
                    val doc = Jsoup.parse(html, base)

                    val counts = mapOf(
                        "GridItem" to doc.select(".GridItem").size,
                        "MainFiltar" to doc.select("#MainFiltar").size,
                        "MovieBlock" to doc.select(".MovieBlock").size,
                        "postDiv" to doc.select(".postDiv").size,
                        "entry-box" to doc.select(".entry-box").size,
                        "article" to doc.select("article").size,
                        "blockMovie" to doc.select(".blockMovie").size,
                        "SearchResult" to doc.select(".SearchResult").size,
                        "item" to doc.select(".item").size,
                        "card" to doc.select(".card").size,
                        "post" to doc.select(".post").size,
                        "row" to doc.select(".row").size,
                        "col" to doc.select(".col").size,
                        "all_a" to doc.select("a[href]").size
                    )

                    val anchors = doc.select("a[href]").filter { a ->
                        val t = a.text().trim(); val ti = a.attr("title").trim()
                        (t.isNotBlank() || ti.isNotBlank()) && !a.absUrl("href").isBlank()
                    }.take(35).map { a ->
                        val t = a.text().trim().ifBlank { a.attr("title").trim() }
                        "\"${jsonEscape(a.absUrl("href").take(90))} | ${jsonEscape(t.take(40))}\""
                    }

                    val idx = html.lowercase().indexOf(name.lowercase())
                    val sample = if (idx >= 0)
                        html.substring(maxOf(0, idx - 250), minOf(html.length, idx + 250))
                    else ""

                    val json = "{" +
                        "\"base\":\"${jsonEscape(base)}\"," +
                        "\"htmlLen\":${html.length}," +
                        "\"containsQuery\":${html.lowercase().contains(name.lowercase())}," +
                        "\"counts\":{" + counts.entries.joinToString(",") { "\"${it.key}\":${it.value}" } + "}," +
                        "\"anchors\":[" + anchors.joinToString(",") + "]," +
                        "\"sampleAroundQuery\":\"${jsonEscape(sample)}\"" +
                        "}"
                    call.respondText(json, ContentType.Application.Json)
                } catch (e: Exception) {
                    call.respondText("{\"error\":\"${jsonEscape(e.message ?: "unknown")}\"}", ContentType.Application.Json)
                }
            }

            get("/api/debug-mycima-rss") {
                val name = call.request.queryParameters["name"] ?: "Spider man"
                try {
                    val encoded = java.net.URLEncoder.encode(name.trim(), "UTF-8")
                    val encodedAr = java.net.URLEncoder.encode("العراب", "UTF-8")
                    val candidates = listOf(
                        "https://mycima.house/feed/",
                        "https://mycima.gdn/feed/",
                        "https://mycima.house/search/$encodedAr/feed/",
                        "https://mycima.house/search/$encoded/feed/"
                    )
                    val results = mutableListOf<String>()
                    for (u in candidates) {
                        try {
                            val resp = httpClient.get(u) { header("User-Agent", UA) }
                            val body = resp.bodyAsText()
                            val low = body.lowercase()
                            val isRss = low.contains("<rss") || low.contains("<item") || low.contains("<feed")
                            val itemCount = Regex("<item[ >]").findAll(body).count()
                            val items = Regex("<item[^>]*>([\\s\\S]*?)</item>").findAll(body).take(3)
                                .map { m ->
                                    val blk = m.groupValues[1]
                                    val t = Regex("<title>([^<]*)</title>").find(blk)?.groupValues?.get(1)?.trim() ?: ""
                                    val l = Regex("<link>([^<]*)</link>").find(blk)?.groupValues?.get(1)?.trim() ?: ""
                                    "\"${jsonEscape(t.take(45))} -> ${jsonEscape(l.take(110))}\""
                                }.toList().joinToString(",")
                            val title = Regex("<title>([^<]+)</title>").find(body)?.groupValues?.get(1)?.trim() ?: ""
                            results.add("{\"url\":\"${jsonEscape(u)}\",\"code\":${resp.status.value},\"len\":${body.length},\"isRss\":$isRss,\"itemCount\":$itemCount,\"title\":\"${jsonEscape(title.take(50))}\",\"items\":[$items]}")
                        } catch (e: Exception) {
                            results.add("{\"url\":\"${jsonEscape(u)}\",\"error\":\"${jsonEscape(e.message ?: "unknown")}\"}")
                        }
                    }
                    call.respondText("{\"results\":[$results]}", ContentType.Application.Json)
                } catch (e: Exception) {
                    call.respondText("{\"error\":\"${jsonEscape(e.message ?: "unknown")}\"}", ContentType.Application.Json)
                }
            }

            get("/api/debug-mycima-filter") {
                val name = call.request.queryParameters["name"] ?: "Spider man"
                try {
                    val base = resolveMyCimaBase()
                    val html = fetchHtml("$base/", "$base/")
                    val actions = Regex("""action["':=\s]+([a-zA-Z0-9_]+)""").findAll(html)
                        .map { it.groupValues[1] }.distinct().take(15).map { "\"${jsonEscape(it)}\"" }
                    val filterPaths = Regex("""['"](/[^'"]*(?:filter|search|ajax)[^'"]*)['"]""").findAll(html)
                        .map { it.groupValues[1] }.distinct().take(15).map { "\"${jsonEscape(it.take(120))}\"" }
                    val forms = Jsoup.parse(html, base).select("form").take(3).map { f ->
                        val act = f.attr("action"); val mth = f.attr("method")
                        val ins = f.select("input").joinToString(",") { "${it.attr("name")}:${it.attr("type")}" }
                        "\"form action=${jsonEscape(act.take(90))} method=$mth inputs=${jsonEscape(ins.take(120))}\""
                    }
                    val jsCalls = Regex("""(?:fetch|\\$\\.ajax|\\$\\.post|XMLHttpRequest)[\\s\\S]{0,120}?(?:['"]([^'"]*(?:filter|search|ajax)[^'"]*)['"])""")
                        .findAll(html).map { it.groupValues[1] }.distinct().take(8).map { "\"${jsonEscape(it.take(120))}\"" }
                    val idx = html.lowercase().indexOf("filter")
                    val sample = if (idx >= 0) html.substring(maxOf(0, idx - 200), minOf(html.length, idx + 200)) else ""
                    call.respondText("{" +
                        "\"base\":\"${jsonEscape(base)}\"," +
                        "\"htmlLen\":${html.length}," +
                        "\"actions\":[$actions]," +
                        "\"filterPaths\":[$filterPaths]," +
                        "\"forms\":[$forms]," +
                        "\"jsCalls\":[$jsCalls]," +
                        "\"sampleAroundFilter\":\"${jsonEscape(sample)}\"" +
                        "}", ContentType.Application.Json)
                } catch (e: Exception) {
                    call.respondText("{\"error\":\"${jsonEscape(e.message ?: "unknown")}\"}", ContentType.Application.Json)
                }
            }

            // 🎬 اختبار صفحة فيلم حقيقية من RSS (يختبر البحث + صفحة الفيلم + السيرفرات)
            get("/api/debug-mycima-movie") {
                val name = call.request.queryParameters["name"] ?: "Spider man"
                try {
                    val base = resolveMyCimaBase()
                    val encoded = java.net.URLEncoder.encode(name.trim(), "UTF-8")
                    val rssUrl = "$base/search/$encoded/feed/"
                    val rssHtml = fetchHtml(rssUrl, "$base/")
                    val items = Regex("<item[^>]*>([\\s\\S]*?)</item>").findAll(rssHtml)
                        .map { m ->
                            val blk = m.groupValues[1]
                            val t = Regex("<title>([^<]*)</title>").find(blk)?.groupValues?.get(1)?.trim() ?: ""
                            val l = Regex("<link>([^<]*)</link>").find(blk)?.groupValues?.get(1)?.trim() ?: ""
                            t to l
                        }.toList()
                    val movie = items.firstOrNull { (t, _) -> t.contains(name, true) } ?: items.firstOrNull()
                    if (movie == null) { call.respondText("{\"error\":\"NO_MOVIE_IN_RSS\"}", ContentType.Application.Json); return@get }

                    val movieHtml = fetchHtml(movie.second, "$base/")
                    val mdoc = Jsoup.parse(movieHtml, movie.second)

                    val iframeSrcs = mdoc.select("iframe[src]").map { it.attr("abs:src") }.take(10)
                    val dataIds = mdoc.select("[data-id]").map { it.attr("data-id") }.take(10)
                    val dataWatch = mdoc.select("[data-watch]").map { it.attr("data-watch") }.take(10)
                    val dataSrc = mdoc.select("[data-src]").map { it.attr("data-src") }.take(10)
                    val links = mdoc.select("a[href]").filter { a ->
                        val h = a.absUrl("href").lowercase()
                        h.contains("govid") || h.contains("vidsharing") || h.contains("fsdcmo") ||
                        h.contains("voe") || h.contains("filemoon") || h.contains("streamhg") ||
                        h.contains("dood") || h.contains("uqload") || h.contains("/e/") || h.contains("/play/")
                    }.map { it.absUrl("href") }.take(15)

                    val mohix = Regex("""(?:const|var)\s+Mohix\s*=\s*["']([0-9a-fA-F]+)["']""").find(movieHtml)?.groupValues?.get(1)
                    val mohixDecoded = if (mohix != null) decodeHexStr(mohix) else null

                    val ajaxTest = if (dataIds.isNotEmpty()) {
                        try {
                            val resp = httpClient.post("$base/wp-admin/admin-ajax.php") {
                                header("Referer", movie.second)
                                header("X-Requested-With", "XMLHttpRequest")
                                header("Content-Type", "application/x-www-form-urlencoded")
                                setBody("action=get_player&server=${dataIds.first()}")
                            }
                            val body = resp.bodyAsText()
                            val iframe = Jsoup.parse(body).selectFirst("iframe")?.attr("src") ?: ""
                            "{\"code\":${resp.status.value},\"len\":${body.length},\"iframe\":\"${jsonEscape(iframe.take(150))}\"}"
                        } catch (e: Exception) { "{\"error\":\"${jsonEscape(e.message ?: "unknown")}\"}" }
                    } else "null"

                    call.respondText("{" +
                        "\"base\":\"${jsonEscape(base)}\"," +
                        "\"rssUrl\":\"${jsonEscape(rssUrl)}\"," +
                        "\"items\":[" + items.take(5).joinToString(",") { "(t,l) -> \"${jsonEscape(t.first.take(50))} -> ${jsonEscape(t.second.take(100))}\"" } + "]," +
                        "\"movieTitle\":\"${jsonEscape(movie.first.take(60))}\"," +
                        "\"movieUrl\":\"${jsonEscape(movie.second.take(150))}\"," +
                        "\"movieHtmlLen\":${movieHtml.length}," +
                        "\"iframeSrcs\":[" + iframeSrcs.joinToString(",") { "\"${jsonEscape(it.take(120))}\"" } + "]," +
                        "\"dataIds\":[" + dataIds.joinToString(",") { "\"${jsonEscape(it)}\"" } + "]," +
                        "\"dataWatch\":[" + dataWatch.joinToString(",") { "\"${jsonEscape(it.take(120))}\"" } + "]," +
                        "\"dataSrc\":[" + dataSrc.joinToString(",") { "\"${jsonEscape(it.take(120))}\"" } + "]," +
                        "\"links\":[" + links.joinToString(",") { "\"${jsonEscape(it.take(120))}\"" } + "]," +
                        "\"mohix\":" + (if (mohix != null) "\"${jsonEscape(mohix.take(60))}\"" else "null") + "," +
                        "\"mohixDecoded\":" + (if (mohixDecoded != null) "\"${jsonEscape(mohixDecoded.take(120))}\"" else "null") + "," +
                        "\"ajaxTest\":$ajaxTest" +
                        "}", ContentType.Application.Json)
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
