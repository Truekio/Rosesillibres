package com.truekio.acciones

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class News(
    val title: String,
    val url: String,
    val source: String,
    val ticker: String,
    val time: Long
)

enum class Src(val label: String) {
    GOOGLE_ES("Google News (ES)"),
    GOOGLE_EN("Google News (EN)"),
    YAHOO("Yahoo Finance"),
    INVESTING("Investing.com"),
    BING("Bing News")
}

/** Fuente propia: un RSS cualquiera (kind = RSS) o un canal de YouTube (kind = YT). */
data class Feed(val url: String, val name: String, val kind: String)

val PALETTE: List<Long> = listOf(
    0xFF5B8DEFL, 0xFFF59E0BL, 0xFF10B981L, 0xFF8B5CF6L,
    0xFFEF4444L, 0xFFEC4899L, 0xFF14B8A6L, 0xFFF97316L,
    0xFF6366F1L, 0xFF84CC16L, 0xFF0EA5E9L
)

fun tickerColor(t: String): Long = PALETTE[(t.hashCode() and 0x7fffffff) % PALETTE.size]

fun ago(ms: Long): String {
    val diff = (System.currentTimeMillis() - ms).coerceAtLeast(0)
    val min = diff / 60_000
    return when {
        min < 1 -> "ahora"
        min < 60 -> "hace $min min"
        min < 60 * 24 -> "hace ${min / 60} h"
        else -> "hace ${min / (60 * 24)} d"
    }
}

object Store {
    private var sp: SharedPreferences? = null
    private var file: File? = null

    val tickers = MutableStateFlow<List<String>>(emptyList())
    val sources = MutableStateFlow(Src.values().map { it.name }.toSet())
    val feeds = MutableStateFlow<List<Feed>>(emptyList())
    val news = MutableStateFlow<List<News>>(emptyList())
    val loading = MutableStateFlow(false)
    val lastUpdate = MutableStateFlow(0L)

    @Synchronized
    fun init(ctx: Context) {
        if (sp != null) return
        val app = ctx.applicationContext
        val prefs = app.getSharedPreferences("acciones", Context.MODE_PRIVATE)
        sp = prefs
        file = File(app.filesDir, "news.json")
        tickers.value = (prefs.getString("tickers", "") ?: "").split("|").filter { it.isNotBlank() }
        prefs.getStringSet("sources", null)?.let { sources.value = it.toSet() }
        lastUpdate.value = prefs.getLong("last", 0L)
        runCatching {
            val fa = JSONArray(prefs.getString("feeds", "[]"))
            feeds.value = List(fa.length()) {
                val o = fa.getJSONObject(it)
                Feed(o.getString("u"), o.getString("n"), o.getString("k"))
            }
        }
        runCatching {
            val f = file!!
            if (f.exists()) {
                val a = JSONArray(f.readText())
                news.value = List(a.length()) {
                    val o = a.getJSONObject(it)
                    News(o.getString("t"), o.getString("u"), o.getString("s"), o.getString("k"), o.getLong("d"))
                }
            }
        }
    }

    private fun saveTickers() {
        sp?.edit()?.putString("tickers", tickers.value.joinToString("|"))?.apply()
    }

    @Synchronized
    fun addTicker(raw: String) {
        val t = raw.trim().let { if (it.contains(' ')) it else it.uppercase() }
        if (t.isBlank() || t.contains('|') || tickers.value.any { it.equals(t, ignoreCase = true) }) return
        tickers.value = tickers.value + t
        saveTickers()
    }

    @Synchronized
    fun removeTicker(t: String) {
        tickers.value = tickers.value.filter { it != t }
        saveTickers()
        setNews(news.value.filter { it.ticker != t })
    }

    private fun saveFeeds() {
        val a = JSONArray()
        feeds.value.forEach { a.put(JSONObject().put("u", it.url).put("n", it.name).put("k", it.kind)) }
        sp?.edit()?.putString("feeds", a.toString())?.apply()
    }

    @Synchronized
    fun addFeed(f: Feed) {
        if (feeds.value.any { it.url == f.url }) return
        feeds.value = feeds.value + f
        saveFeeds()
    }

    @Synchronized
    fun removeFeed(f: Feed) {
        feeds.value = feeds.value.filter { it.url != f.url }
        saveFeeds()
        setNews(news.value.filter { it.ticker != f.name })
    }

    @Synchronized
    fun toggleSource(s: Src) {
        val cur = sources.value
        val next = if (s.name in cur) cur - s.name else cur + s.name
        if (next.isEmpty()) return
        sources.value = next
        sp?.edit()?.putStringSet("sources", next)?.apply()
    }

    @Synchronized
    fun setNews(list: List<News>) {
        news.value = list
        runCatching {
            val a = JSONArray()
            list.forEach {
                a.put(JSONObject().put("t", it.title).put("u", it.url).put("s", it.source).put("k", it.ticker).put("d", it.time))
            }
            file?.writeText(a.toString())
        }
    }

    @Synchronized
    fun touch() {
        val now = System.currentTimeMillis()
        lastUpdate.value = now
        sp?.edit()?.putLong("last", now)?.apply()
    }
}
