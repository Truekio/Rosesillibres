package com.truekio.chollos

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

const val SRC_WALLAPOP = "WALLAPOP"
const val SRC_VINTED = "VINTED"
const val SRC_CHOLLOMETRO = "CHOLLOMETRO"

val ALL_SOURCES = listOf(SRC_WALLAPOP, SRC_VINTED, SRC_CHOLLOMETRO)

fun sourceLabel(s: String) = when (s) {
    SRC_WALLAPOP -> "Wallapop"
    SRC_VINTED -> "Vinted"
    SRC_CHOLLOMETRO -> "Chollometro"
    else -> s
}

/** Una búsqueda guardada. maxPrice: aviso siempre que algo cueste menos. minDiscount: % por debajo de la mediana. */
data class Search(
    val id: String,
    val query: String,
    val maxPrice: Double?,
    val minDiscount: Int,
    val sources: Set<String>
)

/** Un anuncio encontrado. price = 0 si no se conoce. */
data class Item(
    val id: String,
    val source: String,
    val title: String,
    val price: Double,
    val url: String,
    val image: String? = null
)

data class Deal(
    val item: Item,
    val searchId: String,
    val searchName: String,
    val reason: String,
    val time: Long
)

object Store {
    private lateinit var prefs: SharedPreferences
    private var ready = false

    val searches = MutableStateFlow<List<Search>>(emptyList())
    val deals = MutableStateFlow<List<Deal>>(emptyList())
    val intervalMin = MutableStateFlow(15)
    val loading = MutableStateFlow(false)
    val lastRun = MutableStateFlow(0L)
    val lastStatus = MutableStateFlow("")

    @Synchronized
    fun init(ctx: Context) {
        if (ready) return
        prefs = ctx.applicationContext.getSharedPreferences("chollos", Context.MODE_PRIVATE)
        searches.value = readSearches(prefs.getString("searches", "[]") ?: "[]")
        deals.value = readDeals(prefs.getString("deals", "[]") ?: "[]")
        intervalMin.value = prefs.getInt("interval", 15).coerceAtLeast(15)
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

    fun addDeals(new: List<Deal>) {
        if (new.isEmpty()) return
        val all = (new + deals.value).distinctBy { it.item.source + it.item.id }.take(200)
        deals.value = all
        prefs.edit().putString("deals", writeDeals(all)).apply()
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
                sources = if (src == null) ALL_SOURCES.toSet() else (0 until src.length()).map { i -> src.getString(i) }.toSet()
            )
        }
    }.getOrDefault(emptyList())

    private fun writeDeals(list: List<Deal>): String {
        val a = JSONArray()
        for (d in list) a.put(JSONObject().apply {
            put("id", d.item.id); put("source", d.item.source); put("title", d.item.title)
            put("price", d.item.price); put("url", d.item.url); put("image", d.item.image ?: JSONObject.NULL)
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
                    o.getString("url"), if (o.isNull("image")) null else o.optString("image")
                ),
                o.optString("sid"), o.optString("sname"), o.optString("reason"), o.optLong("time")
            )
        }
    }.getOrDefault(emptyList())
}
