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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import kotlin.random.Random

object Checker {
    private val mutex = Mutex()

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
        if (!mutex.tryLock()) return 0
        var found = 0
        val errors = mutableListOf<String>()
        try {
            Store.loading.value = true
            val newDeals = mutableListOf<Deal>()
            withContext(Dispatchers.IO) {
                for (s in Store.searches.value) {
                    for (src in s.sources) {
                        val key = "${s.id}_$src"
                        val items = runCatching {
                            when (src) {
                                SRC_WALLAPOP -> Wallapop.search(s.query, s.maxPrice)
                                SRC_VINTED -> Vinted.search(s.query, s.maxPrice)
                                else -> Chollometro.search(s.query, s.maxPrice)
                            }
                        }.getOrElse { errors += "${sourceLabel(src)}: ${it.message}"; null }
                            ?.filter { matches(s, it) } ?: continue

                        val firstRun = !Store.hasSeen(key)
                        val seen = Store.seenIds(key)
                        val prices = Store.prices(key)
                        val fresh = items.filter { it.id !in seen }
                        // Con la historia de precios ANTES de añadir los nuevos, para no sesgar la mediana.
                        val med = if (prices.size >= 8) median(prices) else null

                        if (!firstRun) for (it in fresh) {
                            if (isJunk(it.title)) continue
                            val reason = when {
                                src == SRC_CHOLLOMETRO -> "Chollo de la comunidad"
                                it.price < 1.0 -> null
                                s.maxPrice != null && it.price <= s.maxPrice -> "Por debajo de tu máximo (${fmt(s.maxPrice)})"
                                med != null && it.price <= med * (1 - s.minDiscount / 100.0) ->
                                    "${((1 - it.price / med) * 100).toInt()}% por debajo de la mediana (${fmt(med)})"
                                else -> null
                            }
                            if (reason != null) newDeals += Deal(it, s.id, s.query, reason, System.currentTimeMillis())
                        }
                        if (src != SRC_CHOLLOMETRO) {
                            fresh.filter { it.price >= 1.0 && !isJunk(it.title) }.forEach { prices += it.price }
                            Store.savePrices(key, prices)
                        }
                        Store.saveSeen(key, seen + fresh.map { it.id })
                        delay(Random.nextLong(800, 2500)) // pausa para no parecer un bot
                    }
                }
            }
            found = newDeals.size
            Store.addDeals(newDeals)
            newDeals.forEach { notify(ctx, it) }
            val status = if (errors.isEmpty()) "OK · $found chollos nuevos" else "$found nuevos · " + errors.distinct().joinToString(" | ")
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
