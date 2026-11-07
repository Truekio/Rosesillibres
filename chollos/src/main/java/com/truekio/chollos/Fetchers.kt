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

    fun search(q: String, maxPrice: Double?): List<Item> {
        var url = "https://api.wallapop.com/api/v3/search?keywords=${enc(q)}&order_by=newest" +
            "&source=search_box&latitude=$LAT&longitude=$LON&language=es_ES"
        // Pedimos hasta un margen por encima del máximo; el filtro real lo hace el analizador.
        if (maxPrice != null) url += "&max_sale_price=${maxPrice.toInt()}"
        val r = http(url, mapOf("Accept" to "application/json", "X-DeviceOS" to "0"))
        if (r.code != 200) error("Wallapop HTTP ${r.code}")
        val root = JSONObject(r.body)
        val arr: JSONArray = root.optJSONObject("data")?.optJSONObject("section")?.optJSONObject("payload")?.optJSONArray("items")
            ?: root.optJSONArray("search_objects")
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
    private var cookie: String = ""
    private var cookieAt = 0L

    private fun session(force: Boolean) {
        if (!force && cookie.isNotEmpty() && System.currentTimeMillis() - cookieAt < 20 * 60_000) return
        val r = http("https://www.vinted.es/", mapOf("Accept" to "text/html"))
        cookie = r.cookies.joinToString("; ") { it.substringBefore(';') }
        cookieAt = System.currentTimeMillis()
    }

    fun search(q: String, maxPrice: Double?): List<Item> {
        var url = "https://www.vinted.es/api/v2/catalog/items?search_text=${enc(q)}&order=newest_first&per_page=40"
        if (maxPrice != null) url += "&price_to=${maxPrice.toInt()}"
        var r: Resp? = null
        for (attempt in 0..1) {
            session(force = attempt > 0)
            r = http(url, mapOf("Accept" to "application/json", "Cookie" to cookie))
            if (r.code == 200) break
        }
        if (r == null || r.code != 200) error("Vinted HTTP ${r?.code}")
        val arr = JSONObject(r.body).optJSONArray("items") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id").ifBlank { return@mapNotNull null }
            val link = o.optString("url").ifBlank { "https://www.vinted.es/items/$id" }
            val extra = listOf(o.optString("brand_title"), o.optString("size_title")).filter { it.isNotBlank() }.joinToString(" · ")
            val title = o.optString("title") + if (extra.isNotEmpty()) " ($extra)" else ""
            Item(id, SRC_VINTED, title, priceOf(o.opt("price")), link, o.optJSONObject("photo")?.optString("url")?.ifBlank { null }, extra)
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
