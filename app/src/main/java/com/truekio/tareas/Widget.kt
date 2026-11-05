package com.truekio.tareas

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider

private val IdKey = ActionParameters.Key<String>("id")

private sealed class WRow {
    data class H(val bucket: Int) : WRow()
    data class T(val task: Task) : WRow()
}

class TaskWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        TaskRepository.init(context)
        val now = System.currentTimeMillis()
        val tasks = TaskRepository.tasks.value.filter { !it.done }.sortedBy { it.due }.take(40)
        val rows = buildList<WRow> {
            var last = -1
            for (t in tasks) {
                val b = bucketOf(t.due, now)
                if (b != last) {
                    add(WRow.H(b))
                    last = b
                }
                add(WRow.T(t))
            }
        }
        val cats = TaskRepository.categories.value.associateBy { it.id }
        provideContent {
            GlanceTheme {
                WidgetBody(rows, tasks.isEmpty(), cats)
            }
        }
    }
}

@Composable
private fun WidgetBody(rows: List<WRow>, empty: Boolean, cats: Map<String, Category>) {
    val context = LocalContext.current
    val now = System.currentTimeMillis()
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.widgetBackground)
            .cornerRadius(24.dp)
            .padding(14.dp)
    ) {
        Row(
            modifier = GlanceModifier.fillMaxWidth().padding(bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Tareas",
                modifier = GlanceModifier.defaultWeight().clickable(actionStartActivity<MainActivity>()),
                style = TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )
            )
            Box(
                modifier = GlanceModifier
                    .size(44.dp)
                    .cornerRadius(22.dp)
                    .background(GlanceTheme.colors.primary)
                    .clickable(
                        actionStartActivity(
                            Intent(context, QuickAddActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "+",
                    style = TextStyle(
                        color = GlanceTheme.colors.onPrimary,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold
                    )
                )
            }
        }
        if (empty) {
            Text(
                text = "Todo hecho 🎉",
                modifier = GlanceModifier.padding(top = 8.dp),
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 16.sp)
            )
        } else {
            LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
                items(
                    rows,
                    itemId = { r ->
                        when (r) {
                            is WRow.H -> -(r.bucket + 1).toLong()
                            is WRow.T -> r.task.id.hashCode().toLong()
                        }
                    }
                ) { r ->
                    when (r) {
                        is WRow.H -> SectionHeader(r.bucket)
                        is WRow.T -> TaskRow(r.task, cats[r.task.categoryId], now)
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(bucket: Int) {
    Text(
        text = BUCKET_TITLES[bucket],
        modifier = GlanceModifier.fillMaxWidth().padding(start = 4.dp, top = 6.dp, bottom = 0.dp),
        style = TextStyle(
            color = if (bucket == 0) GlanceTheme.colors.error else GlanceTheme.colors.primary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold
        )
    )
}

@Composable
private fun TaskRow(t: Task, cat: Category?, now: Long) {
    val context = LocalContext.current
    val color = Color(cat?.color ?: 0xFF64748BL)
    val overdue = t.due < now
    Box(modifier = GlanceModifier.fillMaxWidth().padding(vertical = 4.dp)) {
    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .background(GlanceTheme.colors.surfaceVariant)
            .cornerRadius(20.dp)
            .padding(start = 10.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)
            .clickable(actionStartActivity<MainActivity>()),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = GlanceModifier
                .width(5.dp)
                .height(44.dp)
                .cornerRadius(3.dp)
                .background(ColorProvider(color))
        ) {}
        Spacer(modifier = GlanceModifier.width(10.dp))
        Column(modifier = GlanceModifier.defaultWeight()) {
            Text(
                text = t.title,
                maxLines = 1,
                style = TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium
                )
            )
            Text(
                text = Fmt.due(t.due),
                maxLines = 1,
                style = TextStyle(
                    color = if (overdue) GlanceTheme.colors.error else GlanceTheme.colors.onSurfaceVariant,
                    fontSize = 14.sp
                )
            )
        }
        Box(
            modifier = GlanceModifier
                .size(44.dp)
                .cornerRadius(22.dp)
                .background(GlanceTheme.colors.secondaryContainer)
                .clickable(
                    actionStartActivity(
                        Intent(context, SnoozeActivity::class.java)
                            .putExtra("id", t.id)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(text = "⏰", style = TextStyle(color = GlanceTheme.colors.onSecondaryContainer, fontSize = 18.sp))
        }
        Spacer(modifier = GlanceModifier.width(6.dp))
        Box(
            modifier = GlanceModifier
                .size(44.dp)
                .cornerRadius(22.dp)
                .background(GlanceTheme.colors.primaryContainer)
                .clickable(actionRunCallback<DoneAction>(actionParametersOf(IdKey to t.id))),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "✓",
                style = TextStyle(
                    color = GlanceTheme.colors.onPrimaryContainer,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )
            )
        }
    }
    }
}

class DoneAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        TaskRepository.init(context)
        parameters[IdKey]?.let {
            TaskRepository.complete(it)
            Notifier.dismiss(context, it)
        }
    }
}

class TaskWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TaskWidget()
}
