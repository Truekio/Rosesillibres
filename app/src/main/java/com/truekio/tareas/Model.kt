package com.truekio.tareas

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

data class Category(val id: String, val name: String, val color: Long)

enum class Repeat(val label: String) {
    NONE("No se repite"),
    DAILY("Cada día"),
    WEEKLY("Cada semana"),
    MONTHLY("Cada mes"),
    YEARLY("Cada año")
}

enum class Snooze(val label: String) {
    M15("+15 min"),
    H1("+1 hora"),
    D1("+1 día"),
    W1("+1 semana")
}

/** Minutos antes de la hora de la tarea -> texto. */
val REMINDER_OPTIONS: List<Pair<Int, String>> = listOf(
    0 to "A la hora",
    5 to "5 min antes",
    10 to "10 min antes",
    30 to "30 min antes",
    60 to "1 h antes",
    120 to "2 h antes",
    1440 to "1 día antes",
    2880 to "2 días antes",
    10080 to "1 semana antes"
)

val PALETTE: List<Long> = listOf(
    0xFF5B8DEFL, 0xFFF59E0BL, 0xFF10B981L, 0xFF8B5CF6L,
    0xFFEF4444L, 0xFFEC4899L, 0xFF14B8A6L, 0xFFF97316L,
    0xFF6366F1L, 0xFF84CC16L, 0xFF64748BL, 0xFF0EA5E9L
)

data class Task(
    val id: String,
    val title: String,
    val notes: String = "",
    val categoryId: String,
    val due: Long,
    val repeat: Repeat = Repeat.NONE,
    val interval: Int = 1,
    /** Bits 0..6 = lunes..domingo. 0 = el mismo día de la semana. */
    val weekdays: Int = 0,
    val reminders: List<Int> = listOf(0),
    val done: Boolean = false
)

fun Category.toJson(): JSONObject =
    JSONObject().put("id", id).put("name", name).put("color", color)

fun categoryFromJson(o: JSONObject) =
    Category(o.getString("id"), o.getString("name"), o.getLong("color"))

fun Task.toJson(): JSONObject = JSONObject()
    .put("id", id)
    .put("title", title)
    .put("notes", notes)
    .put("cat", categoryId)
    .put("due", due)
    .put("repeat", repeat.name)
    .put("interval", interval)
    .put("weekdays", weekdays)
    .put("reminders", JSONArray(reminders))
    .put("done", done)

fun taskFromJson(o: JSONObject): Task {
    val ra = o.optJSONArray("reminders")
    val reminders = List(ra?.length() ?: 0) { ra!!.getInt(it) }
    return Task(
        id = o.getString("id"),
        title = o.getString("title"),
        notes = o.optString("notes", ""),
        categoryId = o.optString("cat", ""),
        due = o.getLong("due"),
        repeat = runCatching { Repeat.valueOf(o.optString("repeat", "NONE")) }.getOrDefault(Repeat.NONE),
        interval = o.optInt("interval", 1),
        weekdays = o.optInt("weekdays", 0),
        reminders = reminders,
        done = o.optBoolean("done", false)
    )
}

object Recur {
    /** Siguiente vencimiento de una tarea recurrente, siempre posterior a ahora y al vencimiento actual. */
    fun next(t: Task, now: Long): Long {
        val zone = ZoneId.systemDefault()
        val after = maxOf(t.due, now)
        val start = Instant.ofEpochMilli(t.due).atZone(zone)
        val time = start.toLocalTime()
        val n = t.interval.coerceAtLeast(1).toLong()
        return when (t.repeat) {
            Repeat.NONE -> t.due
            Repeat.DAILY -> advance(start, after) { it.plusDays(n) }
            Repeat.MONTHLY -> advance(start, after) { it.plusMonths(n) }
            Repeat.YEARLY -> advance(start, after) { it.plusYears(n) }
            Repeat.WEEKLY ->
                if (t.weekdays == 0) {
                    advance(start, after) { it.plusWeeks(n) }
                } else {
                    val first = Instant.ofEpochMilli(after).atZone(zone).toLocalDate()
                    var result = after + 24L * 3600_000L
                    for (i in 0..14) {
                        val day = first.plusDays(i.toLong())
                        val bit = 1 shl (day.dayOfWeek.value - 1)
                        if ((t.weekdays and bit) != 0) {
                            val ms = day.atTime(time).atZone(zone).toInstant().toEpochMilli()
                            if (ms > after) {
                                result = ms
                                break
                            }
                        }
                    }
                    result
                }
        }
    }

    private fun advance(start: ZonedDateTime, after: Long, step: (ZonedDateTime) -> ZonedDateTime): Long {
        var d = step(start)
        var guard = 0
        while (d.toInstant().toEpochMilli() <= after && guard++ < 5000) d = step(d)
        return d.toInstant().toEpochMilli()
    }

    fun snoozed(t: Task, s: Snooze, now: Long): Long {
        val zone = ZoneId.systemDefault()
        val base = maxOf(t.due, now)
        return when (s) {
            Snooze.M15 -> now + 15 * 60_000L
            Snooze.H1 -> now + 60 * 60_000L
            Snooze.D1 -> Instant.ofEpochMilli(base).atZone(zone).plusDays(1).toInstant().toEpochMilli()
            Snooze.W1 -> Instant.ofEpochMilli(base).atZone(zone).plusWeeks(1).toInstant().toEpochMilli()
        }
    }
}

object Fmt {
    private val es: Locale = Locale.forLanguageTag("es-ES")

    fun time(ms: Long): String =
        Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm", es))

    fun day(d: LocalDate): String {
        val today = LocalDate.now(ZoneId.systemDefault())
        return when (d) {
            today -> "Hoy"
            today.plusDays(1) -> "Mañana"
            today.minusDays(1) -> "Ayer"
            else -> {
                val pattern = if (d.year == today.year) "EEE d MMM" else "EEE d MMM yyyy"
                d.format(DateTimeFormatter.ofPattern(pattern, es)).replaceFirstChar { it.uppercase() }
            }
        }
    }

    fun due(ms: Long): String {
        val d = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()
        return "${day(d)} · ${time(ms)}"
    }

    fun repeat(t: Task): String {
        if (t.repeat == Repeat.NONE) return ""
        val n = t.interval.coerceAtLeast(1)
        return when (t.repeat) {
            Repeat.DAILY -> if (n == 1) "Cada día" else "Cada $n días"
            Repeat.WEEKLY -> {
                if (t.weekdays == 31) "Laborables (L–V)"
                else if (t.weekdays != 0) {
                    val names = listOf("L", "M", "X", "J", "V", "S", "D")
                    names.filterIndexed { i, _ -> (t.weekdays and (1 shl i)) != 0 }.joinToString(" ")
                } else if (n == 1) "Cada semana" else "Cada $n semanas"
            }
            Repeat.MONTHLY -> if (n == 1) "Cada mes" else "Cada $n meses"
            Repeat.YEARLY -> if (n == 1) "Cada año" else "Cada $n años"
            Repeat.NONE -> ""
        }
    }
}
