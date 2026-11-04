package com.truekio.acciones

import android.content.Context
import android.util.Xml
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.time.OffsetDateTime
import java.util.Locale

object NewsFetcher {
    private val mutex = Mutex()
    private const val MAX_AGE_MS = 30L * 24 * 3600 * 1000
    private const val PER_TAG = 15
    private val INVESTING_FEEDS = listOf(
        "https://www.investing.com/rss/news_25.xml",
        "https://www.investing.com/rss/news.xml"
    )

    suspend fun refresh(ctx: Context) {
        Store.init(ctx)
        if (!mutex.tryLock()) return
        try {
            Store.loading.value = true
            val tickers = Store.tickers.value
            val feeds = Store.feeds.value
            val enabled = Store.sources.value
            if (tickers.isEmpty() && feeds.isEmpty()) {
                Store.setNews(emptyList())
                return
            }
            val fetched = withContext(Dispatchers.IO) {
                coroutineScope {
                    val jobs = mutableListOf<Deferred<List<News>>>()
                    for (t in tickers) {
                        for (s in Src.values()) {
                            if (s.name in enabled && s != Src.INVESTING) {
                                jobs += async { runCatching { fetchTicker(t, s) }.getOrDefault(emptyList()) }
                            }
                        }
                    }
                    if (Src.INVESTING.name in enabled && tickers.isNotEmpty()) {
                        for (u in INVESTING_FEEDS) {
                            jobs += async {
                                runCatching {
                                    val items = fetchUrl(u, "Investing.com", "", Src.INVESTING)
                                    tickers.flatMap { t -> items.filter { mentions(it.title, t) }.map { it.copy(ticker = t) } }
                                }.getOrDefault(emptyList())
                            }
                        }
                    }
                    for (f in feeds) {
                        jobs += async {
                            runCatching {
                                val label = if (f.kind == "YT") "YouTube" else "RSS"
                                fetchUrl(f.url, label, f.name, null).map { it.copy(ticker = f.name, source = label) }
                            }.getOrDefault(emptyList())
                        }
                    }
                    jobs.awaitAll().flatten()
                }
            }
            if (fetched.isNotEmpty()) {
                val cutoff = System.currentTimeMillis() - MAX_AGE_MS
                val hidden = Store.dismissed.value
                val result = fetched
                    .filter { it.time >= cutoff && newsKey(it) !in hidden }
                    .sortedByDescending { it.time }
                    .distinctBy { it.ticker + "|" + norm(it.title) }
                    .groupBy { it.ticker }
                    .flatMap { (_, list) -> list.take(PER_TAG) }
                    .sortedByDescending { it.time }
                Store.setNews(result)
                Store.touch()
            }
        } finally {
            Store.loading.value = false
            mutex.unlock()
            runCatching { AccionesWidget().updateAll(ctx.applicationContext) }
        }
    }

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }.take(60)

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun mentions(title: String, term: String): Boolean {
        val re = Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(term) + "(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
        return re.containsMatchIn(title)
    }

    private fun open(url: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
        conn.setRequestProperty("Accept", "application/rss+xml, application/atom+xml, application/xml, text/xml, text/html, */*")
        conn.setRequestProperty("Cookie", "CONSENT=YES+1; SOCS=CAI")
        return conn
    }

    private fun fetchTicker(ticker: String, s: Src): List<News> {
        val url = when (s) {
            Src.GOOGLE_ES -> "https://news.google.com/rss/search?q=${enc("$ticker acciones")}&hl=es&gl=ES&ceid=ES:es"
            Src.GOOGLE_EN -> "https://news.google.com/rss/search?q=${enc("$ticker stock")}&hl=en-US&gl=US&ceid=US:en"
            Src.YAHOO -> "https://feeds.finance.yahoo.com/rss/2.0/headline?s=${enc(ticker)}&region=US&lang=en-US"
            Src.BING -> "https://www.bing.com/news/search?q=${enc("$ticker stock")}&format=rss&setlang=en"
            Src.INVESTING -> return emptyList()
        }
        val default = when (s) {
            Src.YAHOO -> "Yahoo Finance"
            Src.BING -> "Bing News"
            else -> "Google News"
        }
        return fetchUrl(url, default, ticker, s)
    }

    private fun fetchUrl(url: String, defaultSource: String, ticker: String, s: Src?): List<News> {
        val conn = open(url)
        try {
            if (conn.responseCode != 200) return emptyList()
            return conn.inputStream.use { parseFeed(it, defaultSource, ticker, s) }
        } finally {
            conn.disconnect()
        }
    }

    private fun httpGet(url: String): String? {
        val conn = open(url)
        return try {
            if (conn.responseCode != 200) null
            else conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            conn.disconnect()
        }
    }

    private fun feedTitle(url: String): String? {
        val conn = open(url)
        try {
            if (conn.responseCode != 200) return null
            return conn.inputStream.use { stream ->
                val p = Xml.newPullParser()
                p.setInput(stream, null)
                var ev = p.eventType
                var result: String? = null
                while (ev != XmlPullParser.END_DOCUMENT) {
                    if (ev == XmlPullParser.START_TAG) {
                        if (p.name == "item" || p.name == "entry") break
                        if (p.name == "title") {
                            result = p.nextText().trim().ifBlank { null }
                            break
                        }
                    }
                    ev = p.next()
                }
                result
            }
        } finally {
            conn.disconnect()
        }
    }

    /** Convierte lo que escribe el usuario (RSS, enlace/@usuario/ID de YouTube) en una fuente. */
    suspend fun resolveFeed(input: String): Feed? = withContext(Dispatchers.IO) {
        runCatching {
            val raw = input.trim()
            if (raw.isBlank()) return@runCatching null
            val ytId = Regex("UC[A-Za-z0-9_-]{22}")
            val isYt = raw.startsWith("@") || raw.contains("youtube.com") || raw.contains("youtu.be") || ytId.matches(raw)
            if (isYt) {
                var id: String? = ytId.find(raw)?.value
                if (id == null) {
                    val page = if (raw.startsWith("@")) "https://www.youtube.com/$raw"
                    else if (raw.startsWith("http")) raw else "https://$raw"
                    val html = httpGet(page)
                    if (html != null) {
                        id = Regex("\"channelId\":\"(UC[A-Za-z0-9_-]{22})\"").find(html)?.groupValues?.get(1)
                            ?: Regex("channel/(UC[A-Za-z0-9_-]{22})").find(html)?.groupValues?.get(1)
                    }
                }
                if (id == null) return@runCatching null
                val feedUrl = "https://www.youtube.com/feeds/videos.xml?channel_id=$id"
                Feed(feedUrl, feedTitle(feedUrl) ?: "YouTube", "YT")
            } else {
                val url = if (raw.startsWith("http")) raw else "https://$raw"
                val title = feedTitle(url) ?: return@runCatching null
                Feed(url, title, "RSS")
            }
        }.getOrNull()
    }

    private fun parseDate(raw: String): Long {
        val txt = raw.trim()
        runCatching { return OffsetDateTime.parse(txt).toInstant().toEpochMilli() }
        for (p in listOf("EEE, dd MMM yyyy HH:mm:ss zzz", "EEE, dd MMM yyyy HH:mm:ss Z", "EEE, d MMM yyyy HH:mm:ss zzz")) {
            runCatching { return SimpleDateFormat(p, Locale.US).parse(txt)!!.time }
        }
        return System.currentTimeMillis()
    }

    /** Lee RSS (item) y Atom (entry, usado por YouTube). */
    private fun parseFeed(stream: InputStream, defaultSource: String, ticker: String, s: Src?): List<News> {
        val p = Xml.newPullParser()
        p.setInput(stream, null)
        val out = mutableListOf<News>()
        var inItem = false
        var title = ""
        var link = ""
        var date = 0L
        var src = ""
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> {
                    val n = p.name
                    if (n == "item" || n == "entry") {
                        inItem = true
                        title = ""; link = ""; date = 0L; src = ""
                    } else if (inItem) {
                        when (n) {
                            "title" -> title = p.nextText().trim()
                            "link" -> {
                                val href = p.getAttributeValue(null, "href")
                                val rel = p.getAttributeValue(null, "rel")
                                val text = p.nextText().trim()
                                if (href != null) {
                                    if (rel == null || rel == "alternate") link = href
                                } else {
                                    link = text
                                }
                            }
                            "pubDate", "published" -> date = parseDate(p.nextText())
                            "updated" -> if (date == 0L) date = parseDate(p.nextText())
                            "source", "News:Source" -> src = p.nextText().trim()
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if ((p.name == "item" || p.name == "entry") && inItem) {
                        inItem = false
                        if (title.isNotBlank() && link.isNotBlank()) {
                            val source = src.ifBlank { defaultSource }
                            val clean = if (s == Src.GOOGLE_ES || s == Src.GOOGLE_EN) title.removeSuffix(" - $source") else title
                            out.add(News(clean, link, source, ticker, if (date == 0L) System.currentTimeMillis() else date))
                        }
                    }
                }
            }
            ev = p.next()
        }
        return out
    }
}
