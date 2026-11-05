package com.truekio.sector

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
    val time: Long,
    val es: Boolean = true,
    val site: String = ""
)

/** Solo se muestran noticias de los últimos 7 días. */
const val NEWS_MAX_AGE_MS = 7L * 24 * 3600 * 1000

/** Titulares con estas palabras se ocultan (ruido de consumo: recetas, dietas, ofertas...). */
val DEFAULT_EXCLUDED: List<String> = listOf(
    "receta", "recetas", "dieta", "adelgazar", "calorías", "propiedades", "beneficios",
    "menú del día", "oferta", "ofertas", "cupón", "horóscopo", "sorteo"
)

/** Medios de pago (por nombre o dominio): se ocultan sus noticias. Editable en la app. */
val DEFAULT_BLOCKED: List<String> = listOf(
    "alimarket", "alimarket.es", "expansión", "expansion.com", "cinco días", "cincodias.elpais.com",
    "financial times", "ft.com", "wall street journal", "wsj.com", "bloomberg", "the economist",
    "new york times", "nytimes.com", "business insider", "morningstar"
)

/** Medios del sector que se consultan en "Prensa del sector". Editable en la app. */
val DEFAULT_TRADE_DOMAINS: List<String> = listOf(
    "interempresas.net", "freshplaza.es", "eurocarne.com", "agroinformacion.com", "distribucionactualidad.com",
    "molineriaypanaderia.com", "mercacei.com", "revistaalimentaria.es", "hortoinfo.es", "agrodigital.com",
    "efeagro.com", "fiab.es", "foodretail.es", "alimentacion.es", "infoagro.com"
)

/** Un tema: nombre visible y consulta de búsqueda. */
data class Topic(val name: String, val query: String)

val PRESETS: List<Topic> = listOf(
    Topic("Pan y bollería", "(panadería OR panificación OR bollería OR \"pan industrial\")"),
    Topic("Verduras y hortalizas", "(hortalizas OR verduras OR hortofrutícola) (industria OR sector OR mercado OR exportación)"),
    Topic("Fruta", "(fruta OR frutas OR frutícola OR hortofrutícola) (sector OR mercado OR exportación OR campaña)"),
    Topic("IV gama", "(\"IV gama\" OR \"cuarta gama\")"),
    Topic("V gama", "(\"V gama\" OR \"quinta gama\" OR \"platos cocinados\" OR \"platos preparados\")"),
    Topic("Frutos secos", "(\"frutos secos\" OR almendra OR pistacho OR nuez OR avellana) (sector OR mercado OR industria)"),
    Topic("Confitería y chocolate", "(confitería OR chocolate OR caramelos OR chicles) (industria OR sector OR mercado OR fábrica)"),
    Topic("Snacks y aperitivos", "(snacks OR aperitivos OR \"patatas fritas\") (industria OR sector OR mercado OR fábrica)"),
    Topic("Carne y cárnicos", "(\"industria cárnica\" OR \"sector cárnico\" OR \"productos cárnicos\" OR embutidos OR charcutería)"),
    Topic("Pescado y marisco", "(\"industria pesquera\" OR \"sector pesquero\" OR acuicultura OR \"productos del mar\")"),
    Topic("Legumbres", "(legumbres OR garbanzos OR lentejas OR alubias) (sector OR industria OR mercado OR cosecha)"),
    Topic("Conservas vegetales", "(\"conservas vegetales\" OR \"conserva vegetal\" OR \"industria conservera\" OR \"tomate industria\")"),
    Topic("Conservas de carne y pescado", "(\"conservas de pescado\" OR \"conservas cárnicas\" OR conserveras OR \"industria conservera\")"),
    Topic("Lácteos", "(lácteos OR queso OR leche) (industria OR sector OR mercado)"),
    Topic("Congelados y platos preparados", "(congelados OR \"platos preparados\" OR \"comida preparada\") (industria OR sector)"),
    Topic("Aceite", "(\"aceite de oliva\" OR aceites) (industria OR sector OR mercado)"),
    Topic("Maquinaria y tecnología", "(\"maquinaria alimentaria\" OR automatización OR robotización OR envasado OR \"línea de producción\") (\"industria alimentaria\" OR alimentación)"),
    Topic("Inversiones y nuevas plantas", "(\"nueva fábrica\" OR \"nueva planta\" OR \"amplía su fábrica\" OR \"ampliación de su planta\" OR \"invertirá\" OR \"inversión de\") (alimentaria OR alimentación OR cárnica OR conservera OR panadería OR snacks OR lácteo OR frutas)"),
    Topic("Tendencias del sector", "(tendencias OR consumo OR innovación) (\"industria alimentaria\" OR \"sector alimentario\") (producto OR mercado)")
)

/** Convierte lo que escribe el usuario (empresa o consulta propia) en un tema. */
fun topicFromText(raw: String): Topic? {
    val name = raw.trim()
    if (name.isBlank() || name.contains('|')) return null
    val advanced = name.contains('"') || name.contains(" OR ") || name.contains('(')
    return Topic(name, if (advanced) name else "\"$name\"")
}

fun newsKey(n: News) = n.ticker + "|" + n.url

enum class Src(val label: String) {
    GOOGLE_ES("Google News"),
    TRADE("Prensa del sector"),
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

    val topics = MutableStateFlow<List<Topic>>(emptyList())
    val tradeDomains = MutableStateFlow(DEFAULT_TRADE_DOMAINS)
    val sources = MutableStateFlow(Src.values().map { it.name }.toSet())
    val feeds = MutableStateFlow<List<Feed>>(emptyList())
    val dismissed = MutableStateFlow<Set<String>>(emptySet())
    val blocked = MutableStateFlow(DEFAULT_BLOCKED)
    val excluded = MutableStateFlow(DEFAULT_EXCLUDED)
    val news = MutableStateFlow<List<News>>(emptyList())
    val loading = MutableStateFlow(false)
    val lastUpdate = MutableStateFlow(0L)

    @Synchronized
    fun init(ctx: Context) {
        if (sp != null) return
        val app = ctx.applicationContext
        val prefs = app.getSharedPreferences("sector", Context.MODE_PRIVATE)
        sp = prefs
        file = File(app.filesDir, "news.json")
        runCatching {
            val ta = JSONArray(prefs.getString("topics", "[]"))
            topics.value = List(ta.length()) {
                val o = ta.getJSONObject(it)
                Topic(o.getString("n"), o.getString("q"))
            }
        }
        runCatching {
            val d = prefs.getString("domains", null)
            if (d != null) {
                val da = JSONArray(d)
                tradeDomains.value = List(da.length()) { da.getString(it) }
            }
        }
        prefs.getStringSet("sources", null)?.let { sources.value = it.toSet() }
        lastUpdate.value = prefs.getLong("last", 0L)
        runCatching {
            val x = prefs.getString("excluded", null)
            if (x != null) {
                val xa = JSONArray(x)
                excluded.value = List(xa.length()) { xa.getString(it) }
            }
        }
        runCatching {
            val b = prefs.getString("blocked", null)
            if (b != null) {
                val ba = JSONArray(b)
                blocked.value = List(ba.length()) { ba.getString(it) }
            }
        }
        runCatching {
            val da = JSONArray(prefs.getString("dismissed", "[]"))
            dismissed.value = LinkedHashSet(List(da.length()) { da.getString(it) })
        }
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
                    News(o.getString("t"), o.getString("u"), o.getString("s"), o.getString("k"), o.getLong("d"), o.optBoolean("e", true))
                }.filter { newsKey(it) !in dismissed.value }
            }
        }
    }

    private fun saveTopics() {
        val a = JSONArray()
        topics.value.forEach { a.put(JSONObject().put("n", it.name).put("q", it.query)) }
        sp?.edit()?.putString("topics", a.toString())?.apply()
    }

    @Synchronized
    fun addTopic(t: Topic) {
        if (topics.value.any { it.name.equals(t.name, ignoreCase = true) }) return
        topics.value = topics.value + t
        saveTopics()
    }

    @Synchronized
    fun removeTopic(name: String) {
        topics.value = topics.value.filter { it.name != name }
        saveTopics()
        setNews(news.value.filter { it.ticker != name })
    }

    @Synchronized
    fun addDomain(raw: String) {
        val d = raw.trim().lowercase().removePrefix("https://").removePrefix("http://").removePrefix("www.").trimEnd('/')
        if (d.isBlank() || !d.contains('.') || d.contains(' ') || d in tradeDomains.value) return
        tradeDomains.value = tradeDomains.value + d
        sp?.edit()?.putString("domains", JSONArray(tradeDomains.value).toString())?.apply()
    }

    @Synchronized
    fun removeDomain(d: String) {
        tradeDomains.value = tradeDomains.value.filter { it != d }
        sp?.edit()?.putString("domains", JSONArray(tradeDomains.value).toString())?.apply()
    }

    private fun saveFeeds() {
        val a = JSONArray()
        feeds.value.forEach { a.put(JSONObject().put("u", it.url).put("n", it.name).put("k", it.kind)) }
        sp?.edit()?.putString("feeds", a.toString())?.apply()
    }

    /** Descarta una noticia: desaparece y no vuelve en próximas actualizaciones. */
    @Synchronized
    fun dismiss(key: String) {
        val next = LinkedHashSet(dismissed.value)
        next.add(key)
        val trimmed = if (next.size > 1000) LinkedHashSet(next.toList().takeLast(1000)) else next
        dismissed.value = trimmed
        sp?.edit()?.putString("dismissed", JSONArray(trimmed.toList()).toString())?.commit()
        setNews(news.value.filter { newsKey(it) != key })
    }

    @Synchronized
    fun addExcluded(raw: String) {
        val t = raw.trim().lowercase()
        if (t.isBlank() || t in excluded.value) return
        excluded.value = excluded.value + t
        sp?.edit()?.putString("excluded", JSONArray(excluded.value).toString())?.apply()
        setNews(news.value.filter { !NewsFetcher.hasExcluded(it.title, listOf(t)) })
    }

    @Synchronized
    fun removeExcluded(t: String) {
        excluded.value = excluded.value.filter { it != t }
        sp?.edit()?.putString("excluded", JSONArray(excluded.value).toString())?.apply()
    }

    @Synchronized
    fun addBlocked(raw: String) {
        val t = raw.trim().lowercase()
        if (t.isBlank() || t in blocked.value) return
        blocked.value = blocked.value + t
        sp?.edit()?.putString("blocked", JSONArray(blocked.value).toString())?.apply()
        setNews(news.value.filter { !NewsFetcher.isBlocked(it, listOf(t)) })
    }

    @Synchronized
    fun removeBlocked(t: String) {
        blocked.value = blocked.value.filter { it != t }
        sp?.edit()?.putString("blocked", JSONArray(blocked.value).toString())?.apply()
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

    private val io = java.util.concurrent.Executors.newSingleThreadExecutor()

    /** Actualiza en memoria al instante y guarda a disco en segundo plano. */
    @Synchronized
    fun setNews(list: List<News>) {
        news.value = list
        io.execute {
            runCatching {
                val a = JSONArray()
                list.forEach {
                    a.put(JSONObject().put("t", it.title).put("u", it.url).put("s", it.source).put("k", it.ticker).put("d", it.time).put("e", it.es))
                }
                file?.writeText(a.toString())
            }
        }
    }

    @Synchronized
    fun touch() {
        val now = System.currentTimeMillis()
        lastUpdate.value = now
        sp?.edit()?.putLong("last", now)?.apply()
    }
}
