package com.truekio.chollos

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import kotlin.random.Random

object Checker {
    private val mutex = Mutex()
    private val appScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Default)

    /** Lanza una comprobación que no depende de la pantalla: sigue aunque cambies de pestaña. */
    fun runAsync(ctx: Context) {
        val app = ctx.applicationContext
        appScope.launch { run(app) }
    }

    /** Palabras que delatan anuncios que no sirven (piezas, cajas vacías, compras...). */
    private val junk = listOf(
        "para piezas", "piezas", "despiece", "averiad", "roto", "rota ", "no funciona", "solo caja", "caja vacía",
        "caja vacia", "repuesto", "busco", "compro", "reservad", "vendido", "bloqueado", "icloud", "cambio por"
    )

    /** La palabra debe aparecer entera ("42" no vale para "142" ni "42,5"). */
    private fun hasWord(text: String, w: String) =
        Regex("(?<![\\p{L}\\d])" + Regex.escape(w.lowercase()) + "(?![\\p{L}]|[.,]?\\d)").containsMatchIn(text)

    private fun matches(s: Search, i: Item): Boolean {
        val t = (i.title + " " + i.text).lowercase()
        return s.include.all { anyWord(t, it) } && s.exclude.none { anyWord(t, it) }
    }

    /** "hombre/hombres/caballero" vale si aparece cualquiera de las alternativas. */
    private fun anyWord(text: String, spec: String) =
        spec.split('/').map { it.trim() }.filter { it.isNotEmpty() }.any { hasWord(text, it) }

    private fun isJunk(title: String) = title.lowercase().let { t -> junk.any { t.contains(it) } }

    suspend fun run(ctx: Context): Int {
        Store.init(ctx)
        mutex.lock() // si ya hay una en marcha, esta espera su turno
        var found = 0
        val errors = mutableListOf<String>()
        try {
            Store.loading.value = true
            val newDeals = mutableListOf<Deal>()
            val silent = mutableListOf<Deal>()
            val report = mutableListOf<String>()
            val statRaw = mutableMapOf<String, Int>()
            val statOk = mutableMapOf<String, Int>()
            withContext(Dispatchers.IO) {
                for (s in Store.searches.value) {
                    for (src in s.sources.filter { sourceFits(it, s.category) }) {
                        val key = "${s.id}_$src"
                        val raw = runCatching {
                            when (src) {
                                SRC_WALLAPOP -> Wallapop.search(s.query, s.maxPrice)
                                SRC_VINTED -> Vinted.search(s.query, s.maxPrice, s.vintedUrl)
                                SRC_EBAY -> Ebay.search(s.query, s.maxPrice)
                                SRC_CHOLLOMETRO -> Pepper.search("https://www.chollometro.com/rss", SRC_CHOLLOMETRO, s.query, s.maxPrice)
                                SRC_JOTT -> Shop.search("https://jott.com/es", listOf("outlet", "soldes"), listOf("jott"), s.query, s.maxPrice)
                                SRC_DEALABS -> Pepper.search("https://www.dealabs.com/rss", SRC_DEALABS, s.query, s.maxPrice)
                                else -> Pepper.search("https://www.mydealz.de/rss", SRC_MYDEALZ, s.query, s.maxPrice)
                            }
                        }.getOrElse { errors += "${sourceLabel(src)}: ${it.message}"; null } ?: continue
                        val passed = raw.count { matches(s, it) }
                        statRaw[s.id] = (statRaw[s.id] ?: 0) + raw.size
                        statOk[s.id] = (statOk[s.id] ?: 0) + passed
                        val items = raw.filter { !Store.isBlocked(it) && !Store.isDismissed(it) && matches(s, it) }

                        val firstRun = !Store.hasSeen(key)
                        val seen = Store.seenIds(key)
                        val fresh = items.filter { it.id !in seen }

                        val matched = items.size
                        report += "${sourceLabel(src)} \"${s.query}\": ${raw.size} anuncios${if (isDealSite(src)) " (feed de ${Pepper.lastTotal})" else ""}, ${matched} con tus filtros" + if (firstRun) " (memorizados)" else ", ${fresh.size} nuevos"
                        for (it in fresh) {
                            if (isJunk(it.title)) continue
                            val reason = when {
                                isDealSite(src) -> "Chollo de la comunidad"
                                it.price < 1.0 -> null
                                s.maxPrice != null && it.price <= s.maxPrice -> "Por debajo de tu máximo (${fmt(s.maxPrice)})"
                                else -> null
                            }
                            if (reason != null) {
                                if (firstRun) silent += Deal(it, s.id, s.query, "Ya publicado · $reason", System.currentTimeMillis())
                                else newDeals += Deal(it, s.id, s.query, reason, System.currentTimeMillis())
                            }
                        }
                        if (!isDealSite(src)) {
                            // Bajadas de precio en anuncios que ya conocíamos
                            val pm = Store.priceMap(key)
                            if (!firstRun) for (it in items) {
                                val old = pm[it.id] ?: continue
                                if (it.id in seen && it.price >= 1.0 && it.price <= old * 0.85 && !isJunk(it.title) &&
                                    (s.maxPrice == null || it.price <= s.maxPrice)) {
                                    newDeals += Deal(it, s.id, s.query, "Bajó de ${fmt(old)} a ${fmt(it.price)}", System.currentTimeMillis())
                                }
                            }
                            for (it in items) if (it.price >= 1.0) pm[it.id] = it.price
                            Store.savePriceMap(key, pm)
                        }
                        Store.saveSeen(key, seen + fresh.map { it.id })
                        delay(Random.nextLong(800, 2500)) // pausa para no parecer un bot
                    }
                }
            }
            found = newDeals.size
            Store.addDeals(silent.filter { !Store.isSaved(it.item) } + newDeals)
            Store.setStats(statRaw.keys.associateWith { "${statRaw[it]} encontrados · ${statOk[it]} pasan tus filtros" })
            notifyAll(ctx, newDeals)
            val status = ("$found chollos nuevos\n" + report.joinToString("\n") +
                (if (errors.isEmpty()) "" else "\nERRORES: " + errors.distinct().joinToString(" | "))).trim()
            Store.setStatus(System.currentTimeMillis(), status)
        } finally {
            Store.loading.value = false
            mutex.unlock()
        }
        return found
    }

    fun fmt(v: Double) = if (v % 1.0 == 0.0) "${v.toInt()} €" else String.format("%.2f €", v)

    /** Un solo aviso por revisión: el chollo si es uno, o un resumen si son varios. */
    private fun notifyAll(ctx: Context, deals: List<Deal>) {
        if (deals.isEmpty()) return
        if (deals.size == 1) { notify(ctx, deals[0]); return }
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("chollos", "Chollos", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(
            ctx, 1, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        fun line(d: Deal) = (if (d.item.price > 0) fmt(d.item.price) + " · " else "") + d.item.title.take(45)
        val inbox = android.app.Notification.InboxStyle()
        deals.take(6).forEach { inbox.addLine(line(it)) }
        if (deals.size > 6) inbox.setSummaryText("y ${deals.size - 6} más")
        val n = android.app.Notification.Builder(ctx, "chollos")
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("${deals.size} chollos nuevos")
            .setContentText(line(deals[0]))
            .setStyle(inbox)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify("resumen".hashCode(), n) }
    }

    private fun notify(ctx: Context, d: Deal) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("chollos", "Chollos", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(
            ctx, d.item.id.hashCode(), Intent(Intent.ACTION_VIEW, Uri.parse(d.item.url)),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val price = if (d.item.price > 0) fmt(d.item.price) + " · " else ""
        val n = android.app.Notification.Builder(ctx, "chollos")
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("$price${d.item.title}".take(80))
            .setContentText("${sourceLabel(d.item.source)} · ${d.reason}")
            .setStyle(android.app.Notification.BigTextStyle().bigText("${sourceLabel(d.item.source)} · ${d.reason}\nBúsqueda: ${d.searchName}"))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify((d.item.source + d.item.id).hashCode(), n) }
    }
}

class CheckWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        Checker.run(applicationContext)
        return Result.success()
    }
}

object Scheduler {
    /** WorkManager no permite menos de 15 min entre ejecuciones. */
    fun ensure(ctx: Context) {
        Store.init(ctx)
        val req = PeriodicWorkRequestBuilder<CheckWorker>(Store.intervalMin.value.toLong(), TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(ctx.applicationContext)
            .enqueueUniquePeriodicWork("chollos", ExistingPeriodicWorkPolicy.UPDATE, req)
    }
}
