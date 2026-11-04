package com.truekio.tareas

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object Scheduler {
    private fun pending(ctx: Context, id: String, offset: Int): PendingIntent {
        val i = Intent(ctx, AlarmReceiver::class.java).putExtra("id", id).putExtra("off", offset)
        return PendingIntent.getBroadcast(
            ctx, "$id|$offset".hashCode(), i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun schedule(ctx: Context, t: Task) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val now = System.currentTimeMillis()
        for ((off, _) in REMINDER_OPTIONS) {
            val pi = pending(ctx, t.id, off)
            am.cancel(pi)
            if (t.done || off !in t.reminders) continue
            val at = t.due - off * 60_000L
            if (at <= now) continue
            val canExact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
            if (canExact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    fun cancel(ctx: Context, id: String) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        for ((off, _) in REMINDER_OPTIONS) am.cancel(pending(ctx, id, off))
    }

    fun rescheduleAll(ctx: Context) {
        TaskRepository.init(ctx)
        TaskRepository.tasks.value.forEach { schedule(ctx, it) }
    }
}

object Notifier {
    private const val CHANNEL = "reminders"
    const val ACTION_DONE = "com.truekio.tareas.DONE"
    const val ACTION_SNOOZE15 = "com.truekio.tareas.SNOOZE15"

    fun notifId(taskId: String) = taskId.hashCode()

    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(CHANNEL, "Recordatorios", NotificationManager.IMPORTANCE_HIGH)
            ch.enableVibration(true)
            ch.description = "Avisos de tus tareas"
            nm.createNotificationChannel(ch)
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    fun show(ctx: Context, t: Task, offset: Int) {
        ensureChannel(ctx)
        val nm = NotificationManagerCompat.from(ctx)
        if (!nm.areNotificationsEnabled()) return
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val nid = notifId(t.id)

        val open = PendingIntent.getActivity(ctx, nid, Intent(ctx, MainActivity::class.java), flags)

        fun act(action: String, code: Int) = PendingIntent.getBroadcast(
            ctx, nid * 10 + code,
            Intent(ctx, ActionReceiver::class.java).setAction(action).putExtra("id", t.id), flags
        )

        val more = PendingIntent.getActivity(
            ctx, nid * 10 + 3,
            Intent(ctx, SnoozeActivity::class.java).putExtra("id", t.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            flags
        )

        val whenText = if (offset == 0) "Ahora" else REMINDER_OPTIONS.firstOrNull { it.first == offset }?.second ?: ""
        val cat = TaskRepository.category(t.categoryId)

        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(t.title)
            .setContentText("$whenText · ${Fmt.due(t.due)}")
            .setStyle(NotificationCompat.BigTextStyle().bigText(listOf("$whenText · ${Fmt.due(t.due)}", t.notes).filter { it.isNotBlank() }.joinToString("\n")))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setColor((cat?.color ?: 0xFF5B8DEFL).toInt())
            .setAutoCancel(true)
            .setContentIntent(open)
            .addAction(0, "Hecho", act(ACTION_DONE, 1))
            .addAction(0, "+15 min", act(ACTION_SNOOZE15, 2))
            .addAction(0, "Posponer…", more)
            .build()
        nm.notify(nid, n)
    }

    fun dismiss(ctx: Context, taskId: String) {
        NotificationManagerCompat.from(ctx).cancel(notifId(taskId))
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        TaskRepository.init(context)
        val id = intent.getStringExtra("id") ?: return
        val off = intent.getIntExtra("off", 0)
        val t = TaskRepository.find(id) ?: return
        if (t.done) return
        Notifier.show(context, t, off)
    }
}

class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        TaskRepository.init(context)
        val id = intent.getStringExtra("id") ?: return
        when (intent.action) {
            Notifier.ACTION_DONE -> TaskRepository.complete(id)
            Notifier.ACTION_SNOOZE15 -> TaskRepository.snooze(id, Snooze.M15)
        }
        Notifier.dismiss(context, id)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Scheduler.rescheduleAll(context)
        WidgetUpdater.update(context)
    }
}
