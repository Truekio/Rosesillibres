package com.truekio.chollos

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

const val SRC_WALLAPOP = "WALLAPOP"
const val SRC_VINTED = "VINTED"
const val SRC_CHOLLOMETRO = "CHOLLOMETRO"

const val SRC_EBAY = "EBAY"
const val SRC_DEALABS = "DEALABS"
const val SRC_MYDEALZ = "MYDEALZ"
const val SRC_JOTT = "JOTT"

val ALL_SOURCES = listOf(SRC_WALLAPOP, SRC_VINTED, SRC_EBAY, SRC_CHOLLOMETRO, SRC_DEALABS, SRC_MYDEALZ, SRC_JOTT)
val DEFAULT_SOURCES = setOf(SRC_WALLAPOP, SRC_VINTED, SRC_CHOLLOMETRO)

/** Webs de chollos de la comunidad (formato RSS de Pepper): no hay mediana de precios, cada oferta ya es un chollo. */
fun isDealSite(s: String) = s == SRC_CHOLLOMETRO || s == SRC_DEALABS || s == SRC_MYDEALZ

const val CAT_ROPA = "ROPA"
const val CAT_DEPORTE = "DEPORTE"
const val CAT_ELECTRO = "ELECTRO"
const val CAT_HOGAR = "HOGAR"
val ALL_CATEGORIES = listOf(CAT_ROPA, CAT_DEPORTE, CAT_ELECTRO, CAT_HOGAR)

fun categoryLabel(c: String) = when (c) {
    CAT_ROPA -> "Ropa y calzado"
    CAT_DEPORTE -> "Deporte"
    CAT_ELECTRO -> "Electrónica"
    CAT_HOGAR -> "Hogar"
    else -> "Todo"
}

/** Tiene sentido buscar esta categoría en esta web? Las generalistas valen para todo. */
fun sourceFits(src: String, cat: String) = when {
    cat.isBlank() -> true
    src == SRC_VINTED -> cat == CAT_ROPA || cat == CAT_DEPORTE
    src == SRC_JOTT -> cat == CAT_ROPA
    else -> true
}

fun sourceLabel(s: String) = when (s) {
    SRC_WALLAPOP -> "Wallapop"
    SRC_VINTED -> "Vinted"
    SRC_CHOLLOMETRO -> "Chollometro"
    SRC_EBAY -> "eBay.es"
    SRC_DEALABS -> "Dealabs (FR)"
    SRC_MYDEALZ -> "Mydealz (DE)"
    SRC_JOTT -> "Jott (oficial)"
    else -> s
}

/** Una búsqueda guardada. maxPrice: aviso siempre que algo cueste menos. minDiscount: % por debajo de la mediana. */
data class Search(
    val id: String,
    val query: String,
    val maxPrice: Double?,
    val minDiscount: Int,
    val sources: Set<String>,
    val include: List<String> = emptyList(),
    val exclude: List<String> = emptyList(),
    val vintedUrl: String = "",
    val category: String = "", // "" = todo
    val alts: List<String> = emptyList(), // otras formas de escribir la búsqueda
    val onlyShip: Boolean = false,
    val minPhotos: Int = 0,
    val onlyNew: Boolean = false
)

/** Un anuncio encontrado. price = 0 si no se conoce. */
data class Item(
    val id: String,
    val source: String,
    val title: String,
    val price: Double,
    val url: String,
    val image: String? = null,
    val text: String = "",
    val lat: Double? = null,
    val lon: Double? = null,
    val ship: Boolean? = null,
    val seller: String = "",
    val photos: Int? = null // nº de fotos si la web lo dice
)

data class Deal(
    val item: Item,
    val searchId: String,
    val searchName: String,
    val reason: String,
    val time: Long
)

data class Place(val name: String, val lat: Double, val lon: Double)

val PLACES = listOf(
    Place("Madrid", 40.4168, -3.7038), Place("Barcelona", 41.3874, 2.1686), Place("Valencia", 39.4699, -0.3763),
    Place("Sevilla", 37.3891, -5.9845), Place("Zaragoza", 41.6488, -0.8891), Place("Málaga", 36.7213, -4.4214),
    Place("Murcia", 37.9922, -1.1307), Place("Palma", 39.5696, 2.6502), Place("Las Palmas", 28.1235, -15.4363),
    Place("Bilbao", 43.2630, -2.9350), Place("Alicante", 38.3452, -0.4810), Place("Córdoba", 37.8882, -4.7794),
    Place("Valladolid", 41.6523, -4.7245), Place("Vigo", 42.2406, -8.7207), Place("Gijón", 43.5322, -5.6611),
    Place("A Coruña", 43.3623, -8.4115), Place("Granada", 37.1773, -3.5986), Place("Pamplona", 42.8125, -1.6458),
    Place("Santander", 43.4623, -3.8100), Place("Tenerife", 28.4636, -16.2518)
)

object Store {
    private lateinit var prefs: SharedPreferences
    private var ready = false

    val searches = MutableStateFlow<List<Search>>(emptyList())
    val deals = MutableStateFlow<List<Deal>>(emptyList())
    val saved = MutableStateFlow<List<Deal>>(emptyList())
    val intervalMin = MutableStateFlow(15)
    val place = MutableStateFlow(PLACES[0])
    val radiusKm = MutableStateFlow(0) // 0 = sin límite
    val orShip = MutableStateFlow(true) // con radio: aceptar también anuncios con envío
    val blocked = MutableStateFlow<Set<String>>(emptySet()) // "FUENTE:vendedor"
    val dismissed = MutableStateFlow<Set<String>>(emptySet()) // "FUENTE:id" de anuncios descartados: no vuelven nunca
    val stats = MutableStateFlow<Map<String, String>>(emptyMap()) // id de búsqueda -> "N encontrados · M pasan tus filtros"
    val loading = MutableStateFlow(false)
    val lastRun = MutableStateFlow(0L)
    val lastStatus = MutableStateFlow("")

    @Synchronized
    fun init(ctx: Context) {
        if (ready) return
        prefs = ctx.applicationContext.getSharedPreferences("chollos", Context.MODE_PRIVATE)
        searches.value = readSearches(prefs.getString("searches", "[]") ?: "[]")
        deals.value = readDeals(prefs.getString("deals", "[]") ?: "[]")
        saved.value = readDeals(prefs.getString("saved", "[]") ?: "[]")
        intervalMin.value = prefs.getInt("interval", 15).coerceAtLeast(15)
        place.value = PLACES.firstOrNull { it.name == prefs.getString("place", "") } ?: PLACES[0]
        radiusKm.value = prefs.getInt("radius", 0)
        orShip.value = prefs.getBoolean("orship", true)
        blocked.value = readStrings(prefs.getString("blocked", null)).toSet()
        dismissed.value = readStrings(prefs.getString("dismissed", null)).toSet()
        stats.value = runCatching {
            val o = JSONObject(prefs.getString("stats", "{}") ?: "{}")
            o.keys().asSequence().associateWith { o.getString(it) }
        }.getOrDefault(emptyMap())
        lastRun.value = prefs.getLong("lastRun", 0L)
        lastStatus.value = prefs.getString("lastStatus", "") ?: ""
        ready = true
    }

    fun setSearches(list: List<Search>) {
        searches.value = list
        prefs.edit().putString("searches", writeSearches(list)).apply()
    }

    fun setInterval(min: Int) {
        intervalMin.value = min.coerceAtLeast(15)
        prefs.edit().putInt("interval", intervalMin.value).apply()
    }

    fun setPlace(p: Place) {
        place.value = p
        prefs.edit().putString("place", p.name).apply()
    }

    fun setOrShip(v: Boolean) {
        orShip.value = v
        prefs.edit().putBoolean("orship", v).apply()
    }

    fun blockSeller(it: Item) {
        if (it.seller.isBlank()) return
        blocked.value = blocked.value + "${it.source}:${it.seller}"
        prefs.edit().putString("blocked", JSONArray(blocked.value.toList()).toString()).apply()
    }

    fun isBlocked(it: Item) = it.seller.isNotBlank() && "${it.source}:${it.seller}" in blocked.value

    fun clearBlocked() {
        blocked.value = emptySet()
        prefs.edit().remove("blocked").apply()
    }

    /** id -> último precio visto, para detectar bajadas. */
    fun priceMap(key: String): MutableMap<String, Double> {
        val raw = prefs.getString("pm_$key", null) ?: return linkedMapOf()
        return runCatching {
            val o = JSONObject(raw)
            val m = linkedMapOf<String, Double>()
            o.keys().forEach { k -> m[k] = o.getDouble(k) }
            m
        }.getOrDefault(linkedMapOf())
    }

    fun savePriceMap(key: String, m: Map<String, Double>) {
        val o = JSONObject()
        m.entries.toList().takeLast(400).forEach { o.put(it.key, it.value) }
        prefs.edit().putString("pm_$key", o.toString()).apply()
    }

    fun setRadius(km: Int) {
        radiusKm.value = km
        prefs.edit().putInt("radius", km).apply()
    }

    fun addDeals(new: List<Deal>) {
        if (new.isEmpty()) return
        val all = (new + deals.value).distinctBy { it.item.source + it.item.id }.take(200)
        deals.value = all
        prefs.edit().putString("deals", writeDeals(all)).apply()
    }

    private fun same(a: Deal, b: Deal) = a.item.source == b.item.source && a.item.id == b.item.id

    fun save(d: Deal) {
        if (saved.value.any { same(it, d) }) return
        saved.value = listOf(d) + saved.value
        deals.value = deals.value.filter { !same(it, d) }
        prefs.edit().putString("saved", writeDeals(saved.value)).putString("deals", writeDeals(deals.value)).apply()
    }

    fun unsave(d: Deal) {
        saved.value = saved.value.filter { !same(it, d) }
        prefs.edit().putString("saved", writeDeals(saved.value)).apply()
    }

    /** Quita un chollo de la lista (no volverá a salir: su id ya está memorizado). */
    fun dismiss(d: Deal) {
        deals.value = deals.value.filter { !same(it, d) }
        dismissed.value = (dismissed.value + "${d.item.source}:${d.item.id}").toList().takeLast(3000).toSet()
        prefs.edit().putString("deals", writeDeals(deals.value))
            .putString("dismissed", JSONArray(dismissed.value.toList()).toString()).apply()
    }

    fun isDismissed(i: Item) = "${i.source}:${i.id}" in dismissed.value

    fun isSaved(i: Item) = saved.value.any { it.item.source == i.source && it.item.id == i.id }

    fun clearDismissed() {
        dismissed.value = emptySet()
        prefs.edit().remove("dismissed").apply()
    }

    fun setStats(m: Map<String, String>) {
        stats.value = stats.value + m
        prefs.edit().putString("stats", JSONObject(stats.value).toString()).apply()
    }

    fun clearDeals() {
        deals.value = emptyList()
        prefs.edit().putString("deals", "[]").apply()
    }

    fun setStatus(time: Long, status: String) {
        lastRun.value = time
        lastStatus.value = status
        prefs.edit().putLong("lastRun", time).putString("lastStatus", status).apply()
    }

    // ---- Estado interno por búsqueda y fuente: ids vistos y precios (para la mediana) ----

    fun seenIds(key: String): MutableList<String> =
        readStrings(prefs.getString("seen_$key", null))

    fun saveSeen(key: String, ids: List<String>) {
        prefs.edit().putString("seen_$key", JSONArray(ids.takeLast(600)).toString()).apply()
    }

    fun hasSeen(key: String) = prefs.contains("seen_$key")

    fun prices(key: String): MutableList<Double> {
        val raw = prefs.getString("prices_$key", null) ?: return mutableListOf()
        return runCatching {
            val a = JSONArray(raw)
            MutableList(a.length()) { a.getDouble(it) }
        }.getOrDefault(mutableListOf())
    }

    fun savePrices(key: String, p: List<Double>) {
        val a = JSONArray()
        p.takeLast(300).forEach { a.put(it) }
        prefs.edit().putString("prices_$key", a.toString()).apply()
    }

    fun forget(searchId: String) {
        val e = prefs.edit()
        for (s in ALL_SOURCES) {
            e.remove("seen_${searchId}_$s")
            e.remove("prices_${searchId}_$s")
            e.remove("pm_${searchId}_$s")
        }
        e.apply()
    }

    private fun readStrings(raw: String?): MutableList<String> {
        if (raw == null) return mutableListOf()
        return runCatching {
            val a = JSONArray(raw)
            MutableList(a.length()) { a.getString(it) }
        }.getOrDefault(mutableListOf())
    }

    private fun writeSearches(list: List<Search>): String {
        val a = JSONArray()
        for (s in list) a.put(JSONObject().apply {
            put("id", s.id); put("q", s.query)
            put("max", s.maxPrice ?: JSONObject.NULL)
            put("disc", s.minDiscount)
            put("src", JSONArray(s.sources.toList()))
            put("inc", JSONArray(s.include)); put("exc", JSONArray(s.exclude)); put("vurl", s.vintedUrl); put("cat", s.category); put("alts", JSONArray(s.alts)); put("ship", s.onlyShip); put("photos", s.minPhotos); put("new", s.onlyNew)
        })
        return a.toString()
    }

    private fun readSearches(raw: String): List<Search> = runCatching {
        val a = JSONArray(raw)
        (0 until a.length()).map {
            val o = a.getJSONObject(it)
            val src = o.optJSONArray("src")
            Search(
                id = o.getString("id"), query = o.getString("q"),
                maxPrice = if (o.isNull("max")) null else o.getDouble("max"),
                minDiscount = o.optInt("disc", 30),
                sources = if (src == null) DEFAULT_SOURCES else (0 until src.length()).map { i -> src.getString(i) }.toSet(),
                include = strList(o.optJSONArray("inc")), exclude = strList(o.optJSONArray("exc")),
                vintedUrl = o.optString("vurl"), category = o.optString("cat"), alts = strList(o.optJSONArray("alts")),
                onlyShip = o.optBoolean("ship"), minPhotos = o.optInt("photos"), onlyNew = o.optBoolean("new")
            )
        }
    }.getOrDefault(emptyList())

    private fun strList(a: JSONArray?): List<String> = if (a == null) emptyList() else (0 until a.length()).map { a.getString(it) }

    private fun writeDeals(list: List<Deal>): String {
        val a = JSONArray()
        for (d in list) a.put(JSONObject().apply {
            put("id", d.item.id); put("source", d.item.source); put("title", d.item.title)
            put("price", d.item.price); put("url", d.item.url); put("image", d.item.image ?: JSONObject.NULL)
            put("seller", d.item.seller)
            put("sid", d.searchId); put("sname", d.searchName); put("reason", d.reason); put("time", d.time)
        })
        return a.toString()
    }

    private fun readDeals(raw: String): List<Deal> = runCatching {
        val a = JSONArray(raw)
        (0 until a.length()).map {
            val o = a.getJSONObject(it)
            Deal(
                Item(
                    o.getString("id"), o.getString("source"), o.getString("title"), o.optDouble("price", 0.0),
                    o.getString("url"), if (o.isNull("image")) null else o.optString("image"),
                    seller = o.optString("seller")
                ),
                o.optString("sid"), o.optString("sname"), o.optString("reason"), o.optLong("time")
            )
        }
    }.getOrDefault(emptyList())
}
