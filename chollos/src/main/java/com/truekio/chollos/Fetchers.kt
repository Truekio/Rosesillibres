package com.truekio.chollos

import android.util.Xml
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

private const val UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

private data class Resp(val code: Int, val body: String, val cookies: List<String>)

private fun http(url: String, headers: Map<String, String> = emptyMap()): Resp {
    val c = URL(url).openConnection() as HttpURLConnection
    try {
        c.connectTimeout = 15000
        c.readTimeout = 20000
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Accept-Language", "es-ES,es;q=0.9")
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
        val cookies = c.headerFields["Set-Cookie"].orEmpty()
        return Resp(code, body, cookies)
    } finally {
        c.disconnect()
    }
}

/** Precio que puede venir como número, texto ("12.5") u objeto {"amount": ...}. */
private fun priceOf(v: Any?): Double = when (v) {
    is Number -> v.toDouble()
    is String -> v.replace(",", ".").toDoubleOrNull() ?: 0.0
    is JSONObject -> priceOf(v.opt("amount"))
    else -> 0.0
}

object Wallapop {
    // Madrid como punto de referencia: Wallapop lo exige, pero sin filtro de distancia devuelve anuncios de todo el país.
    private const val LAT = "40.4168"
    private const val LON = "-3.7038"

    private const val IOS_UA = "Wallapop/1.0 (iPhone; iOS 17.4; Scale/3.00)"

    fun search(q: String, maxPrice: Double?): List<Item> {
        val qs = "keywords=${enc(q)}&order_by=newest&source=search_box&latitude=$LAT&longitude=$LON&language=es_ES" +
            (if (maxPrice != null) "&max_sale_price=${maxPrice.toInt()}" else "")
        val hdr = mapOf("Accept" to "application/json", "X-DeviceOS" to "0", "User-Agent" to IOS_UA)
        val codes = mutableListOf<String>()
        for (path in listOf("general/search", "search")) {
            val r = runCatching { http("https://api.wallapop.com/api/v3/$path?$qs", hdr) }.getOrNull()
            if (r == null) { codes += "red"; continue }
            if (r.code != 200) { codes += r.code.toString(); continue }
            return parse(r.body)
        }
        error("sin acceso (${codes.joinToString("/")})")
    }

    private fun parse(body: String): List<Item> {
        val root = JSONObject(body)
        val arr: JSONArray = root.optJSONArray("search_objects")
            ?: root.optJSONObject("data")?.optJSONObject("section")?.optJSONObject("payload")?.optJSONArray("items")
            ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id").ifBlank { return@mapNotNull null }
            val slug = o.optString("web_slug").ifBlank { id }
            val img = o.optJSONArray("images")?.optJSONObject(0)?.let { im ->
                im.optJSONObject("urls")?.optString("small")?.ifBlank { null } ?: im.optString("small").ifBlank { null }
            }
            Item(id, SRC_WALLAPOP, o.optString("title"), priceOf(o.opt("price")), "https://es.wallapop.com/item/$slug", img, o.optString("description"))
        }
    }
}

object Vinted {
    private const val WWW = "https://www.vinted.es"
    private const val API = "https://api.vinted.es"
    private var cookie = ""
    private var anonId = ""
    private var csrf = ""
    private var token = ""
    private var warmAt = 0L

    /** Vinted (desde sept. 2026) pide token anónimo, anon-id y csrf, que da la propia web al cargar la portada. */
    private fun warmUp(force: Boolean) {
        if (!force && token.isNotEmpty() && System.currentTimeMillis() - warmAt < 15 * 60_000) return
        val jar = linkedMapOf<String, String>()
        var anon = ""
        var body = ""
        var next = "$WWW/"
        // Seguimos las redirecciones a mano: las cookies suelen venir en el primer salto y Java las descarta.
        for (hop in 0..5) {
            val c = URL(next).openConnection() as HttpURLConnection
            try {
                c.instanceFollowRedirects = false
                c.connectTimeout = 15000; c.readTimeout = 20000
                c.setRequestProperty("User-Agent", UA)
                c.setRequestProperty("Accept", "text/html,application/xhtml+xml")
                c.setRequestProperty("Accept-Language", "es-ES,es;q=0.9")
                if (jar.isNotEmpty()) c.setRequestProperty("Cookie", jar.entries.joinToString("; ") { "${it.key}=${it.value}" })
                val code = c.responseCode
                c.headerFields.entries.filter { it.key.equals("Set-Cookie", true) }.flatMap { it.value }.forEach {
                    val kv = it.substringBefore(';'); jar[kv.substringBefore('=')] = kv.substringAfter('=')
                }
                c.getHeaderField("X-Anon-Id")?.trim()?.takeIf { it.isNotEmpty() }?.let { anon = it }
                if (code in 300..399) {
                    val loc = c.getHeaderField("Location") ?: break
                    next = if (loc.startsWith("http")) loc else URL(URL(next), loc).toString()
                    continue
                }
                if (code !in 200..299) error("portada HTTP $code")
                body = c.inputStream.bufferedReader().use { it.readText().take(600_000) }
                break
            } finally {
                c.disconnect()
            }
        }
        cookie = jar.entries.joinToString("; ") { "${it.key}=${it.value}" }
        token = jar["access_token_web"] ?: ""
        anonId = anon
        val k = body.indexOf("CSRF_TOKEN")
        csrf = if (k >= 0)
            Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
                .find(body.substring(k, minOf(body.length, k + 300)))?.value ?: "" else ""
        warmAt = System.currentTimeMillis()
        lastCookieNames = jar.keys.joinToString(",")
    }

    private var lastCookieNames = ""

    fun search(q: String, maxPrice: Double?): List<Item> {
        val url = "$API/svc-catalogue/items?search_text=${enc(q)}&order=newest_first&per_page=40&page=1" +
            (if (maxPrice != null) "&price_to=${maxPrice.toInt()}" else "")
        var r: Resp? = null
        for (attempt in 0..1) {
            warmUp(force = attempt > 0)
            val h = mutableMapOf(
                "Accept" to "application/json, text/plain, */*",
                "Origin" to WWW, "Referer" to "$WWW/",
                "Locale" to "es", "Platform" to "web", "X-Next-App" to "marketplace-web",
                "Cookie" to cookie
            )
            if (token.isNotEmpty()) h["Authorization"] = "Bearer $token"
            if (anonId.isNotEmpty()) h["X-Anon-Id"] = anonId
            if (csrf.isNotEmpty()) h["X-Csrf-Token"] = csrf
            r = http(url, h)
            if (r.code == 200) break
        }
        if (r == null || r.code != 200) error("Vinted HTTP ${r?.code} (token=${token.isNotEmpty()}, anon=${anonId.isNotEmpty()}, csrf=${csrf.isNotEmpty()}, cookies=$lastCookieNames)")
        val root = JSONObject(r.body)
        val arr = root.optJSONArray("items") ?: root.optJSONArray("catalog_items")
            ?: error("respuesta sin lista de anuncios; claves: " + root.keys().asSequence().take(8).joinToString(","))
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id").ifBlank { return@mapNotNull null }
            var link = o.optString("url").ifBlank { "/items/$id" }
            if (link.startsWith("/")) link = WWW + link
            val box = o.optJSONObject("item_box")
            val extra = listOf(
                o.optString("brand_title"), o.optString("size_title"),
                box?.optString("first_line") ?: "", box?.optString("second_line") ?: ""
            ).filter { it.isNotBlank() }.joinToString(" · ")
            val img = o.optJSONObject("photo")?.optString("url")?.ifBlank { null }
            Item(id, SRC_VINTED, o.optString("title") + if (extra.isNotEmpty()) " ($extra)" else "", priceOf(o.opt("price")), link, img, extra)
        }
    }
}

object Chollometro {
    private val priceRx = Regex("""(\d+(?:[.,]\d{1,2})?)\s*€""")

    /** Chollos publicados por la comunidad cuyo título contiene todas las palabras de la búsqueda. */
    fun search(q: String, maxPrice: Double?): List<Item> {
        val r = http("https://www.chollometro.com/rss", mapOf("Accept" to "application/rss+xml"))
        if (r.code != 200) error("Chollometro HTTP ${r.code}")
        val words = q.lowercase().split(' ').filter { it.length > 1 }
        val items = mutableListOf<Item>()
        val p = Xml.newPullParser()
        p.setInput(r.body.reader())
        var inItem = false
        var tag = ""
        var title = ""; var link = ""; var guid = ""
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> {
                    tag = p.name
                    if (tag == "item") { inItem = true; title = ""; link = ""; guid = "" }
                }
                XmlPullParser.TEXT, XmlPullParser.CDSECT -> if (inItem) when (tag) {
                    "title" -> title += p.text
                    "link" -> link += p.text.trim()
                    "guid" -> guid += p.text.trim()
                }
                XmlPullParser.END_TAG -> {
                    if (p.name == "item") {
                        inItem = false
                        val t = title.trim()
                        val price = priceRx.find(t)?.groupValues?.get(1)?.replace(",", ".")?.toDoubleOrNull() ?: 0.0
                        val ok = words.all { t.lowercase().contains(it) } && (maxPrice == null || price == 0.0 || price <= maxPrice)
                        if (ok && link.isNotBlank()) items += Item(guid.ifBlank { link }, SRC_CHOLLOMETRO, t, price, link)
                    }
                    tag = ""
                }
            }
            ev = p.next()
        }
        return items
    }
}
