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

class TaskWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        TaskRepository.init(context)
        val tasks = TaskRepository.tasks.value.filter { !it.done }.sortedBy { it.due }.take(40)
        val cats = TaskRepository.categories.value.associateBy { it.id }
        provideContent {
            GlanceTheme {
                WidgetBody(tasks, cats)
            }
        }
    }
}

@Composable
private fun WidgetBody(tasks: List<Task>, cats: Map<String, Category>) {
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
                            Intent(context, MainActivity::class.java)
                                .putExtra("new", true)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
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
        if (tasks.isEmpty()) {
            Text(
                text = "Todo hecho 🎉",
                modifier = GlanceModifier.padding(top = 8.dp),
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 16.sp)
            )
        } else {
            LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
                items(tasks, itemId = { it.id.hashCode().toLong() }) { t ->
                    TaskRow(t, cats[t.categoryId], now)
                }
            }
        }
    }
}

@Composable
private fun TaskRow(t: Task, cat: Category?, now: Long) {
    val color = Color(cat?.color ?: 0xFF64748BL)
    val overdue = t.due < now
    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .padding(vertical = 7.dp)
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
                .size(48.dp)
                .cornerRadius(24.dp)
                .background(GlanceTheme.colors.secondaryContainer)
                .clickable(actionRunCallback<DoneAction>(actionParametersOf(IdKey to t.id))),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "✓",
                style = TextStyle(
                    color = GlanceTheme.colors.onSecondaryContainer,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )
            )
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
