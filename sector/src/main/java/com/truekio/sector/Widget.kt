package com.truekio.sector

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
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
import androidx.glance.appwidget.lazy.itemsIndexed
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
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

private val KeyParam = ActionParameters.Key<String>("k")

class SectorWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        Store.init(context)
        val cutoff = System.currentTimeMillis() - NEWS_MAX_AGE_MS
        val excluded = Store.excluded.value
        val news = Store.news.value.filter { it.time >= cutoff && !NewsFetcher.hasExcluded(it.title, excluded) }.sortedByDescending { it.time }.take(20)
        val hasTickers = Store.topics.value.isNotEmpty()
        provideContent {
            GlanceTheme {
                Body(news, hasTickers)
            }
        }
    }
}

@Composable
private fun Body(news: List<News>, hasTickers: Boolean) {
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
                text = "Sector",
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
                    .clickable(actionRunCallback<RefreshAction>()),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "↻",
                    style = TextStyle(
                        color = GlanceTheme.colors.onPrimary,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold
                    )
                )
            }
        }
        if (news.isEmpty()) {
            Text(
                text = if (hasTickers) "Sin noticias aún. Pulsa ↻ para actualizar." else "Abre la app y elige tus sectores.",
                modifier = GlanceModifier.padding(top = 8.dp).clickable(actionStartActivity<MainActivity>()),
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 16.sp)
            )
        } else {
            LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
                itemsIndexed(news) { _, n -> NewsRow(n) }
            }
        }
    }
}

@Composable
private fun NewsRow(n: News) {
    val color = Color(tickerColor(n.ticker))
    val open = Intent(Intent.ACTION_VIEW, Uri.parse(n.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    Box(modifier = GlanceModifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            modifier = GlanceModifier
                .fillMaxWidth()
                .background(GlanceTheme.colors.surfaceVariant)
                .cornerRadius(20.dp)
                .padding(start = 10.dp, end = 12.dp, top = 10.dp, bottom = 10.dp)
                .clickable(actionStartActivity(open)),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = GlanceModifier
                    .width(5.dp)
                    .height(52.dp)
                    .cornerRadius(3.dp)
                    .background(ColorProvider(color))
            ) {}
            Spacer(modifier = GlanceModifier.width(10.dp))
            Column(modifier = GlanceModifier.defaultWeight()) {
                Text(
                    text = n.title,
                    maxLines = 2,
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurface,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium
                    )
                )
                Text(
                    text = "${n.ticker} · ${n.source} · ${ago(n.time)}",
                    maxLines = 1,
                    style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp)
                )
            }
            Spacer(modifier = GlanceModifier.width(6.dp))
            Box(
                modifier = GlanceModifier
                    .size(40.dp)
                    .cornerRadius(20.dp)
                    .background(GlanceTheme.colors.primaryContainer)
                    .clickable(actionRunCallback<DismissAction>(actionParametersOf(KeyParam to newsKey(n)))),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "✕",
                    style = TextStyle(
                        color = GlanceTheme.colors.onPrimaryContainer,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                )
            }
        }
    }
}

class DismissAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        Store.init(context)
        parameters[KeyParam]?.let { Store.dismiss(it) }
        SectorWidget().update(context, glanceId)
    }
}

class RefreshAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        NewsFetcher.refresh(context)
    }
}

class SectorWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = SectorWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        Scheduler.ensure(context)
    }
}
