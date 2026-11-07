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

/** Si el DNS del móvil falla a ratos, se reintenta un par de veces con pausa. */
private fun http(url: String, headers: Map<String, String> = emptyMap()): Resp {
    var attempt = 0
    while (true) {
        try {
            return httpOnce(url, headers)
        } catch (e: java.net.UnknownHostException) {
            if (++attempt > 2) throw e
            Thread.sleep(3000L * attempt)
        }
    }
}

private fun httpOnce(url: String, headers: Map<String, String> = emptyMap()): Resp {
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
        val p = Store.place.value
        val km = Store.radiusKm.value
        val qs = "keywords=${enc(q)}&order_by=newest&source=search_box&latitude=${p.lat}&longitude=${p.lon}&language=es_ES" +
            (if (maxPrice != null) "&max_sale_price=${maxPrice.toInt()}" else "") +
            (if (km > 0 && !Store.orShip.value) "&distance_in_km=$km" else "")
        val hdr = mapOf("Accept" to "application/json", "X-DeviceOS" to "0", "User-Agent" to IOS_UA)
        val codes = mutableListOf<String>()
        for (path in listOf("general/search", "search")) {
            val r = runCatching { http("https://api.wallapop.com/api/v3/$path?$qs", hdr) }.getOrNull()
            if (r == null) { codes += "red"; continue }
            if (r.code != 200) { codes += r.code.toString(); continue }
            val all = parse(r.body)
            // "Cerca o con envío": pedimos todo y filtramos aquí por distancia real o envío disponible.
            return if (km > 0 && Store.orShip.value) all.filter { near(it, p, km) } else all
        }
        error("sin acceso (${codes.joinToString("/")})")
    }

    private fun near(i: Item, p: Place, km: Int): Boolean {
        if (i.ship == true) return true
        val la = i.lat ?: return true // sin datos: no lo descartamos
        val lo = i.lon ?: return true
        val r = 6371.0
        val dLa = Math.toRadians(la - p.lat); val dLo = Math.toRadians(lo - p.lon)
        val a = Math.sin(dLa / 2).let { it * it } +
            Math.cos(Math.toRadians(p.lat)) * Math.cos(Math.toRadians(la)) * Math.sin(dLo / 2).let { it * it }
        return 2 * r * Math.asin(Math.sqrt(a)) <= km
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
            val loc = o.optJSONObject("location")
            val sh = o.optJSONObject("shipping")
            val ship = sh?.let { if (it.has("item_is_shippable")) it.optBoolean("item_is_shippable") else if (it.has("user_allows_shipping")) it.optBoolean("user_allows_shipping") else null }
            Item(
                id, SRC_WALLAPOP, o.optString("title"), priceOf(o.opt("price")), "https://es.wallapop.com/item/$slug", img, o.optString("description"),
                lat = loc?.optDouble("latitude")?.takeIf { !it.isNaN() }, lon = loc?.optDouble("longitude")?.takeIf { !it.isNaN() },
                ship = ship, seller = o.optString("user_id"), photos = o.optJSONArray("images")?.length()
            )
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

    /**
     * Toma los filtros de un enlace de vinted.es/catalog (categoría, talla, marca, estado, color, material, precio)
     * y los traduce a los nombres de la API actual (attribute_ids[...]).
     */
    private fun filtersFromLink(link: String): Map<String, String> {
        val q = link.trim().substringAfter('?', "")
        if (q.isBlank()) return emptyMap()
        val multi = linkedMapOf<String, MutableList<String>>()
        for (part in q.split('&')) {
            if (part.isBlank()) continue
            val k = runCatching { java.net.URLDecoder.decode(part.substringBefore('='), "UTF-8") }.getOrDefault(part.substringBefore('='))
            val v = runCatching { java.net.URLDecoder.decode(part.substringAfter('=', ""), "UTF-8") }.getOrDefault("")
            if (v.isBlank()) continue
            val key = when (k.removeSuffix("[]")) {
                "catalog", "catalog_ids" -> "attribute_ids[catalog]"
                "size_ids" -> "attribute_ids[size]"
                "brand_ids" -> "attribute_ids[brand]"
                "color_ids" -> "attribute_ids[color]"
                "status_ids" -> "attribute_ids[status]"
                "material_ids" -> "attribute_ids[material]"
                "video_game_platform_ids" -> "attribute_ids[video_game_platform]"
                "search_text", "price_from", "price_to", "currency" -> k
                else -> null
            } ?: continue
            multi.getOrPut(key) { mutableListOf() } += v
        }
        return multi.mapValues { it.value.joinToString(",") }
    }

    fun search(q: String, maxPrice: Double?, link: String = ""): List<Item> {
        val f = filtersFromLink(link).toMutableMap()
        if (maxPrice != null) {
            val cur = f["price_to"]?.toDoubleOrNull()
            f["price_to"] = (if (cur == null || maxPrice < cur) maxPrice else cur).toInt().toString()
        }
        val text = f.remove("search_text")?.takeIf { it.isNotBlank() } ?: q
        val url = "$API/svc-catalogue/items?search_text=${enc(text)}&order=newest_first&per_page=40&page=1" +
            f.entries.joinToString("") { "&${enc(it.key)}=${enc(it.value)}" }
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
            Item(
                id, SRC_VINTED, o.optString("title") + if (extra.isNotEmpty()) " ($extra)" else "", priceOf(o.opt("price")), link, img, extra,
                seller = o.optJSONObject("user")?.optString("id") ?: ""
            )
        }
    }
}

object Pepper {
    private val priceRx = Regex("""class=["']?[^>]*?s-(?:card|item)__price[^>]*>(.*?)</span>""", RegexOption.DOT_MATCHES_ALL)
    private val tagRx = Regex("<[^>]+>")

    /** Ofertas totales que traía el último feed leído (para el estado). */
    @Volatile var lastTotal = 0

    private fun parsePrice(s: String?): Double {
        if (s.isNullOrBlank()) return 0.0
        val m = priceRx.find(s) ?: return 0.0
        return m.groupValues[1].replace(" ", "").replace(".", "").replace(",", ".").toDoubleOrNull() ?: 0.0
    }

    /** Chollos publicados por la comunidad cuyo título o descripción contienen todas las palabras de la búsqueda. */
    fun search(feed: String, source: String, q: String, maxPrice: Double?): List<Item> {
        val r = http(feed, mapOf("Accept" to "application/rss+xml"))
        if (r.code != 200) error("${sourceLabel(source)} HTTP ${r.code}")
        val words = q.lowercase().split(' ').filter { it.length > 1 }
        val items = mutableListOf<Item>()
        val p = Xml.newPullParser()
        p.setInput(r.body.reader())
        var inItem = false
        var tag = ""
        var title = ""; var link = ""; var guid = ""; var desc = ""
        var merchantPrice = ""; var thumb: String? = null
        var total = 0
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> {
                    tag = p.name
                    if (tag == "item") { inItem = true; total++; title = ""; link = ""; guid = ""; desc = ""; merchantPrice = ""; thumb = null }
                    else if (inItem && tag == "pepper:merchant") merchantPrice = p.getAttributeValue(null, "price") ?: ""
                    else if (inItem && (tag == "media:thumbnail" || tag == "media:content") && thumb == null) thumb = p.getAttributeValue(null, "url")
                }
                XmlPullParser.TEXT, XmlPullParser.CDSECT -> if (inItem) when (tag) {
                    "title" -> title += p.text
                    "link" -> link += p.text.trim()
                    "guid" -> guid += p.text.trim()
                    "description" -> desc += p.text
                }
                XmlPullParser.END_TAG -> {
                    if (p.name == "item") {
                        inItem = false
                        val t = title.trim()
                        val body = tagRx.replace(desc, " ").replace("&nbsp;", " ").trim().take(600)
                        val price = parsePrice(merchantPrice).takeIf { it > 0 } ?: parsePrice(t).takeIf { it > 0 } ?: parsePrice(body)
                        val hay = (t + " " + body).lowercase()
                        val ok = words.all { hay.contains(it) } && (maxPrice == null || price == 0.0 || price <= maxPrice)
                        if (ok && link.isNotBlank()) items += Item(guid.ifBlank { link }, source, t, price, link, thumb, body)
                    }
                    tag = ""
                }
            }
            ev = p.next()
        }
        lastTotal = total
        return items
    }
}

/** GET que sigue las redirecciones a mano guardando las cookies de cada salto (Java las pierde). */
private fun getWithJar(url: String, headers: Map<String, String>, jar: MutableMap<String, String>): Resp {
    var next = url
    for (hop in 0..5) {
        val c = URL(next).openConnection() as HttpURLConnection
        try {
            c.instanceFollowRedirects = false
            c.connectTimeout = 15000; c.readTimeout = 20000
            c.setRequestProperty("User-Agent", UA)
            c.setRequestProperty("Accept-Language", "es-ES,es;q=0.9")
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (jar.isNotEmpty()) c.setRequestProperty("Cookie", jar.entries.joinToString("; ") { "${it.key}=${it.value}" })
            val code = c.responseCode
            c.headerFields.entries.filter { it.key.equals("Set-Cookie", true) }.flatMap { it.value }.forEach {
                val kv = it.substringBefore(';'); jar[kv.substringBefore('=')] = kv.substringAfter('=')
            }
            if (code in 300..399) {
                val loc = c.getHeaderField("Location") ?: return Resp(code, "", emptyList())
                next = if (loc.startsWith("http")) loc else URL(URL(next), loc).toString()
                continue
            }
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            return Resp(code, stream?.bufferedReader()?.use { it.readText() } ?: "", emptyList())
        } finally {
            c.disconnect()
        }
    }
    return Resp(310, "", emptyList())
}

object Ebay {
    private const val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
    private val jar = linkedMapOf<String, String>()
    private var warmAt = 0L

    private val cardSplit = Regex("""(?=<li[^>]*\bs-card\b|<li[^>]*\bs-item\b)""")
    private val hrefRx = Regex("""href=["']?(https://(?:www\.)?ebay\.[a-z.]+/itm/(\d+)[^"'\s>]*)""")
    private val titleRx = Regex("""class=["']?[^>]*?s-(?:card|item)__title[^>]*>(.*?)</(?:div|h3)>""", RegexOption.DOT_MATCHES_ALL)
    private val priceRx = Regex("""class="[^"]*s-(?:card|item)__price[^"]*"[^>]*>(.*?)</span>""", RegexOption.DOT_MATCHES_ALL)
    private val subRx = Regex("""class=["']?[^>]*?s-(?:card|item)__subtitle[^>]*>(.*?)</(?:div|span)>""", RegexOption.DOT_MATCHES_ALL)
    private val imgRx = Regex("""<img[^>]+?(?:data-src|src)=["']?(https://i\.ebayimg\.com/[^"'\s>]+)""")
    private val tagRx = Regex("<[^>]+>")
    private val numRx = Regex("""(\d[\d.]*(?:,\d{1,2})?)""")

    private fun clean(s: String) = tagRx.replace(s, " ").replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
        .replace("Se abre en una ventana o pestaña nueva", "").replace("NUEVO ANUNCIO", "").replace(Regex("\\s+"), " ").trim()

    private fun price(s: String): Double {
        val m = numRx.find(s) ?: return 0.0
        return m.groupValues[1].replace(".", "").replace(",", ".").toDoubleOrNull() ?: 0.0
    }

    private var lastCall = 0L
    private val anchorRx = Regex("""<a[^>]+?href=["']?(https://(?:www\.)?ebay\.[a-z.]+/itm/(\d+)[^"'\s>]*)[^>]*>""")
    private val altRx = Regex("""alt=["']([^"']{6,200})["']""")
    private val eurRx = Regex("""(\d{1,3}(?:\.\d{3})*,\d{2})\s*(?:EUR|€)""")

    private fun diag(body: String): String {
        val i = body.indexOf("/itm/")
        val around = if (i >= 0) body.substring(maxOf(0, i - 120), minOf(body.length, i + 160)).replace(Regex("\\s+"), " ") else "sin /itm/"
        return "s-card=${Regex("s-card").findAll(body).count()}, s-item=${Regex("s-item").findAll(body).count()}, itm=${Regex("/itm/").findAll(body).count()}, muestra: $around"
    }

    /** Plan B si cambian las clases: busca cada enlace /itm/ y toma título (foto/alt) y precio de lo que lo rodea. */
    private fun fallback(body: String): List<Item> {
        val out = linkedMapOf<String, Item>()
        for (m in anchorRx.findAll(body)) {
            val id = m.groupValues[2]
            if (id in out || id == "123456") continue
            val win = body.substring(maxOf(0, m.range.first - 300), minOf(body.length, m.range.last + 2500))
            val title = altRx.find(win)?.groupValues?.get(1)?.let { clean(it) }
                ?: clean(win.substring(win.indexOf('>', m.range.first - maxOf(0, m.range.first - 300)).coerceAtLeast(0)).take(300)).take(120)
            val pr = eurRx.find(win)?.groupValues?.get(1)?.let { price(it) } ?: 0.0
            if (pr > 0 && title.length > 5) out[id] = Item(id, SRC_EBAY, title, pr, "https://www.ebay.es/itm/$id", imgRx.find(win)?.groupValues?.get(1), "")
        }
        return out.values.toList()
    }

    fun search(q: String, maxPrice: Double?): List<Item> {
        val h = mapOf("User-Agent" to DESKTOP_UA, "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Sec-Fetch-Dest" to "document", "Sec-Fetch-Mode" to "navigate", "Sec-Fetch-Site" to "none", "Upgrade-Insecure-Requests" to "1")
        // _sop=10: más recientes; LH_BIN=1: solo "Cómpralo ya" (las subastas con puja de 1 € falsearían los chollos)
        val url = "https://www.ebay.es/sch/i.html?_nkw=${enc(q)}&_sop=10&LH_BIN=1&_ipg=60" +
            (if (maxPrice != null) "&_udhi=${maxPrice.toInt()}" else "")
        var r: Resp? = null
        for (attempt in 0..1) {
            // eBay bloquea el ritmo rápido: separamos las peticiones al menos 8 s
            val wait = 8000 - (System.currentTimeMillis() - lastCall)
            if (wait > 0) Thread.sleep(wait)
            if (jar.isEmpty() || attempt > 0 || System.currentTimeMillis() - warmAt > 20 * 60_000) {
                jar.clear()
                getWithJar("https://www.ebay.es/", h, jar)
                warmAt = System.currentTimeMillis()
                Thread.sleep(2500)
            }
            r = getWithJar(url, h + ("Referer" to "https://www.ebay.es/"), jar)
            lastCall = System.currentTimeMillis()
            if (r.code != 403 && r.code != 429) break
        }
        if (r == null || r.code != 200) error("eBay HTTP ${r?.code} (bloqueo temporal)")
        val lower = r.body.lowercase()
        if ("captcha" in lower || "pardon our interruption" in lower || "verifying you are human" in lower) error("eBay pide verificación (anti-bot)")
        val items = mutableListOf<Item>()
        for (chunk in cardSplit.split(r.body)) {
            val m = hrefRx.find(chunk) ?: continue
            val id = m.groupValues[2]
            if (id == "123456") continue // tarjeta de muestra de eBay
            val title = titleRx.find(chunk)?.groupValues?.get(1)?.let { clean(it) } ?: continue
            if (title.isBlank() || title.startsWith("Shop on eBay", true) || title.startsWith("Resultados", true)) continue
            val pr = priceRx.find(chunk)?.groupValues?.get(1)?.let { clean(it) } ?: ""
            val sub = subRx.find(chunk)?.groupValues?.get(1)?.let { clean(it) } ?: ""
            items += Item(id, SRC_EBAY, title, price(pr), "https://www.ebay.es/itm/$id", imgRx.find(chunk)?.groupValues?.get(1), sub)
        }
        if (items.isEmpty()) items += fallback(r.body)
        if (items.isEmpty()) error("eBay: 0 anuncios leídos (${r.body.length} bytes). ${diag(r.body)}".take(700))
        return items.distinctBy { it.id }
    }
}


/** Tiendas oficiales hechas con Shopify: publican sus productos en /collections/<x>/products.json. */
object Shop {
    /** Los títulos vienen en francés: se añaden las palabras equivalentes en español para poder filtrar. */
    private val synonyms = listOf(
        "sans manches" to "chaleco sin mangas",
        "doudoune" to "plumas plumífero",
        "capuche" to "capucha",
        "veste" to "chaqueta",
        "manteau" to "abrigo",
        "parka" to "parka",
        "réversible" to "reversible",
        "homme" to "hombre",
        "femme" to "mujer",
        "enfant" to "niño niña"
    )

    /**
     * store: dirección base (con idioma). collections: colecciones a mirar (outlet, soldes...).
     * brand: palabras de la búsqueda que son el nombre de la tienda y no del producto.
     */
    fun search(store: String, collections: List<String>, brand: List<String>, q: String, maxPrice: Double?): List<Item> {
        val words = q.lowercase().split(' ').filter { it.length > 1 && it !in brand }
        val out = LinkedHashMap<String, Item>()
        var okAny = false
        var lastErr = ""
        for (c in collections) {
            val r = runCatching { http("$store/collections/$c/products.json?limit=250", mapOf("Accept" to "application/json")) }
                .getOrElse { lastErr = it.message ?: "error"; null } ?: continue
            if (r.code != 200) { lastErr = "HTTP ${r.code}"; continue }
            okAny = true
            val arr = runCatching { JSONObject(r.body).getJSONArray("products") }.getOrNull() ?: continue
            for (i in 0 until arr.length()) {
                val p = arr.getJSONObject(i)
                val title = p.optString("title")
                val handle = p.optString("handle")
                val vars = p.optJSONArray("variants") ?: continue
                var price = Double.MAX_VALUE
                var before = 0.0
                val sizes = mutableListOf<String>()
                for (j in 0 until vars.length()) {
                    val v = vars.getJSONObject(j)
                    if (!v.optBoolean("available", true)) continue
                    val pr = v.optString("price").toDoubleOrNull() ?: continue
                    if (pr < price) { price = pr; before = v.optString("compare_at_price").toDoubleOrNull() ?: 0.0 }
                    sizes += v.optString("title")
                }
                if (price == Double.MAX_VALUE) continue // agotado
                var text = title.lowercase() + " " + p.optString("product_type").lowercase() + " " + p.optString("tags").lowercase()
                for ((fr, es) in synonyms) if (text.contains(fr)) text += " $es"
                text += " tallas ${sizes.distinct().joinToString(" ")}"
                if (!words.all { w -> Regex("(?<![\\p{L}\\d])" + Regex.escape(w) + "(?![\\p{L}\\d])").containsMatchIn(text) }) continue
                if (maxPrice != null && price > maxPrice) continue
                val img = p.optJSONArray("images")?.optJSONObject(0)?.optString("src")
                val shown = if (before > price) "$title (antes ${Checker.fmt(before)})" else title
                out[handle] = Item("jott_$handle", SRC_JOTT, shown, price, "$store/products/$handle", img, text)
            }
        }
        if (!okAny) error("Jott: $lastErr")
        return out.values.toList()
    }
}

/** Zalando.es: no tiene API pública; se leen los enlaces de producto y los precios del HTML del catálogo. */
object Zalando {
    private val linkRx = Regex("""href=["'](/[a-z0-9][a-z0-9\-]+\.html)["']""")
    private val priceRx = Regex("""(\d{1,4}(?:\.\d{3})*,\d{2})\s*(?:€|&euro;|&#8364;)""")
    private val imgRx = Regex("""https://img\d*\.ztat\.net/[^"'\s)]+""")
    private val altRx = Regex("""(?:alt|aria-label|title)=["']([^"']{8,150})["']""")

    fun search(q: String, maxPrice: Double?): List<Item> {
        val r = http("https://www.zalando.es/catalogo/?q=${enc(q)}&order=activation_date", mapOf("Accept" to "text/html"))
        if (r.code != 200) error("Zalando HTTP ${r.code}")
        val body = r.body
        val hits = linkRx.findAll(body).toList()
        val out = LinkedHashMap<String, Item>()
        for ((n, m) in hits.withIndex()) {
            val href = m.groupValues[1]
            if (href in out) continue
            val end = minOf(body.length, m.range.first + 2500, hits.drop(n + 1).firstOrNull { it.groupValues[1] != href }?.range?.first ?: Int.MAX_VALUE)
            val win = body.substring(m.range.first, maxOf(end, m.range.last + 1))
            val prices = priceRx.findAll(win).take(3).mapNotNull { it.groupValues[1].replace(".", "").replace(",", ".").toDoubleOrNull() }.toList()
            if (prices.isEmpty()) continue
            val price = prices.minOrNull() ?: continue
            if (maxPrice != null && price > maxPrice) continue
            val title = altRx.find(win)?.groupValues?.get(1)?.let { android.text.Html.fromHtml(it, 0).toString() }
                ?: href.removePrefix("/").removeSuffix(".html").replace('-', ' ')
            out[href] = Item("zal_" + href.removePrefix("/").removeSuffix(".html"), SRC_ZALANDO, title, price,
                "https://www.zalando.es$href", imgRx.find(win)?.value, title + " " + href.replace('-', ' '))
        }
        if (out.isEmpty()) {
            val i = body.indexOf('€').let { if (it < 0) 0 else it }
            val sample = body.substring(maxOf(0, i - 150), minOf(body.length, i + 150)).replace(Regex("\\s+"), " ")
            error("Zalando: sin productos reconocidos (${body.length} bytes, ${hits.size} enlaces). Muestra: $sample")
        }
        return out.values.toList()
    }
}

/** PcComponentes: se leen del HTML de la búsqueda los enlaces de producto que llevan nombre y precio. */
object PcComponentes {
    private val aRx = Regex("""<a\b[^>]*?href=["'](/[a-z0-9][a-z0-9\-]{10,})["'][^>]*>(.*?)</a>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val tagRx = Regex("<[^>]+>")
    private val priceRx = Regex("""(\d{1,5}(?:\.\d{3})*(?:,\d{1,2})?)\s*€""")
    private val imgRx = Regex("""https://img\.pccomponentes\.com/[^"'\s)]+""")

    fun search(q: String, maxPrice: Double?): List<Item> {
        val r = http("https://www.pccomponentes.com/buscar/?query=${enc(q)}", mapOf("Accept" to "text/html"))
        if (r.code != 200) error("PcComponentes HTTP ${r.code}")
        val body = r.body
        val anchors = aRx.findAll(body).toList()
        val out = LinkedHashMap<String, Item>()
        for (m in anchors) {
            val href = m.groupValues[1]
            if (href in out) continue
            val inner = m.groupValues[2]
            val text = android.text.Html.fromHtml(tagRx.replace(inner, " "), 0).toString().replace(Regex("\\s+"), " ").trim()
            val pm = priceRx.find(text) ?: continue
            val prices = priceRx.findAll(text).take(2).mapNotNull { it.groupValues[1].replace(".", "").replace(",", ".").toDoubleOrNull() }.toList()
            val price = prices.minOrNull() ?: continue
            val title = text.substring(0, pm.range.first).trim().ifBlank { href.removePrefix("/").replace('-', ' ') }
            if (title.length < 6 || (maxPrice != null && price > maxPrice)) continue
            out[href] = Item("pcc_" + href.removePrefix("/"), SRC_PCCOMP, title.take(140), price,
                "https://www.pccomponentes.com$href", imgRx.find(inner)?.value, title)
        }
        if (out.isEmpty()) {
            val i = body.indexOf('€').let { if (it < 0) 0 else it }
            val sample = body.substring(maxOf(0, i - 150), minOf(body.length, i + 150)).replace(Regex("\\s+"), " ")
            error("PcComponentes: sin productos reconocidos (${body.length} bytes, ${anchors.size} enlaces). Muestra: $sample")
        }
        return out.values.toList()
    }
}
