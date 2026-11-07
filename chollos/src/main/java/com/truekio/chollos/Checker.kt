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
        return s.include.all { hasWord(t, it) } && s.exclude.none { hasWord(t, it) }
    }

    private fun isJunk(title: String) = title.lowercase().let { t -> junk.any { t.contains(it) } }

    private fun median(l: List<Double>): Double {
        val s = l.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }

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
            withContext(Dispatchers.IO) {
                for (s in Store.searches.value) {
                    for (src in s.sources) {
                        val key = "${s.id}_$src"
                        val raw = runCatching {
                            when (src) {
                                SRC_WALLAPOP -> Wallapop.search(s.query, s.maxPrice)
                                SRC_VINTED -> Vinted.search(s.query, s.maxPrice)
                                SRC_EBAY -> Ebay.search(s.query, s.maxPrice)
                                SRC_CHOLLOMETRO -> Pepper.search("https://www.chollometro.com/rss", SRC_CHOLLOMETRO, s.query, s.maxPrice)
                                SRC_DEALABS -> Pepper.search("https://www.dealabs.com/rss", SRC_DEALABS, s.query, s.maxPrice)
                                else -> Pepper.search("https://www.mydealz.de/rss", SRC_MYDEALZ, s.query, s.maxPrice)
                            }
                        }.getOrElse { errors += "${sourceLabel(src)}: ${it.message}"; null } ?: continue
                        val items = raw.filter { !Store.isBlocked(it) && matches(s, it) }

                        val firstRun = !Store.hasSeen(key)
                        val seen = Store.seenIds(key)
                        val prices = Store.prices(key)
                        val fresh = items.filter { it.id !in seen }
                        // Con la historia de precios ANTES de añadir los nuevos, para no sesgar la mediana.
                        val med = if (prices.size >= 8) median(prices) else null

                        val matched = items.size
                        report += "${sourceLabel(src)} \"${s.query}\": ${raw.size} anuncios${if (isDealSite(src)) " (feed de ${Pepper.lastTotal})" else ""}, ${matched} con tus filtros" + if (firstRun) " (memorizados)" else ", ${fresh.size} nuevos"
                        for (it in fresh) {
                            if (isJunk(it.title)) continue
                            val reason = when {
                                isDealSite(src) -> "Chollo de la comunidad"
                                it.price < 1.0 -> null
                                s.maxPrice != null && it.price <= s.maxPrice -> "Por debajo de tu máximo (${fmt(s.maxPrice)})"
                                med != null && it.price <= med * (1 - s.minDiscount / 100.0) ->
                                    "${((1 - it.price / med) * 100).toInt()}% por debajo de la mediana (${fmt(med)})"
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
                            fresh.filter { it.price >= 1.0 && !isJunk(it.title) }.forEach { prices += it.price }
                            Store.savePrices(key, prices)
                        }
                        Store.saveSeen(key, seen + fresh.map { it.id })
                        delay(Random.nextLong(800, 2500)) // pausa para no parecer un bot
                    }
                }
            }
            found = newDeals.size
            Store.addDeals(silent + newDeals)
            newDeals.forEach { notify(ctx, it) }
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
