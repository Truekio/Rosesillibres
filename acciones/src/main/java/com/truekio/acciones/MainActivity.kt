@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.truekio.acciones

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Shapes
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Store.init(this)
        Scheduler.ensure(this)
        setContent { AppTheme { Screen() } }
    }
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(
        colorScheme = scheme,
        shapes = Shapes(
            small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(20.dp),
            large = RoundedCornerShape(28.dp)
        ),
        content = content
    )
}

@Composable
fun Screen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    val tickers by Store.tickers.collectAsState()
    val feeds by Store.feeds.collectAsState()
    val news by Store.news.collectAsState()
    val loading by Store.loading.collectAsState()
    var input by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf<String?>(null) }
    var showSources by remember { mutableStateOf(false) }

    fun refresh() {
        scope.launch { NewsFetcher.refresh(ctx) }
    }

    fun add() {
        if (input.isNotBlank()) {
            Store.addTicker(input)
            input = ""
            refresh()
        }
    }

    LaunchedEffect(Unit) {
        if (System.currentTimeMillis() - Store.lastUpdate.value > 10 * 60_000L) NewsFetcher.refresh(ctx)
    }

    val tags = tickers + feeds.map { it.name }
    val active = filter?.takeIf { it in tags }
    val shown = news.filter { active == null || it.ticker == active }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Acciones", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = { refresh() }) { Icon(Icons.Default.Refresh, contentDescription = "Actualizar") }
                    IconButton(onClick = { showSources = true }) { Icon(Icons.Default.Settings, contentDescription = "Fuentes") }
                }
            )
        }
    ) { pad ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(top = pad.calculateTopPadding()),
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
            item(key = "progress") {
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            item(key = "add") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        label = { Text("Ticker o empresa (AAPL, Tesla…)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { add() }),
                        modifier = Modifier.weight(1f)
                    )
                    Button(onClick = { add() }) { Icon(Icons.Default.Add, contentDescription = "Añadir") }
                }
            }
            if (tags.isNotEmpty()) {
                item(key = "chips") {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(selected = active == null, onClick = { filter = null }, label = { Text("Todas") })
                        tickers.forEach { t ->
                            FilterChip(
                                selected = active == t,
                                onClick = { filter = if (filter == t) null else t },
                                label = { Text(t) },
                                trailingIcon = {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Quitar $t",
                                        modifier = Modifier.size(16.dp).clickable { Store.removeTicker(t) }
                                    )
                                }
                            )
                        }
                        feeds.forEach { f ->
                            FilterChip(
                                selected = active == f.name,
                                onClick = { filter = if (filter == f.name) null else f.name },
                                label = { Text(f.name) }
                            )
                        }
                    }
                }
            }
            if (shown.isEmpty()) {
                item(key = "empty") {
                    Text(
                        if (tags.isEmpty()) "Añade un ticker o el nombre de una empresa para ver sus últimas noticias.\n\nEn ⚙ puedes elegir fuentes y añadir RSS o canales de YouTube."
                        else if (loading) "Buscando noticias…" else "Sin noticias por ahora. Pulsa ↻ para actualizar.",
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
            itemsIndexed(shown) { _, n ->
                val color = Color(tickerColor(n.ticker))
                Card(
                    onClick = { runCatching { uri.openUri(n.url) } },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                ) {
                    Row(Modifier.height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(6.dp).fillMaxHeight().background(color))
                        Column(Modifier.weight(1f).padding(horizontal = 14.dp, vertical = 12.dp)) {
                            Text(
                                n.title,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Medium,
                                maxLines = 3
                            )
                            Text(
                                "${n.ticker} · ${n.source} · ${ago(n.time)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                        IconButton(onClick = { Store.dismiss(newsKey(n)) }) {
                            Icon(Icons.Default.Close, contentDescription = "Descartar noticia")
                        }
                    }
                }
            }
        }
    }

    if (showSources) {
        SourcesDialog(onClose = { showSources = false }, onChanged = { refresh() })
    }
}

@Composable
fun SourcesDialog(onClose: () -> Unit, onChanged: () -> Unit) {
    val scope = rememberCoroutineScope()
    val sources by Store.sources.collectAsState()
    val feeds by Store.feeds.collectAsState()
    var url by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun addFeed() {
        if (url.isBlank() || busy) return
        busy = true
        error = null
        scope.launch {
            val f = NewsFetcher.resolveFeed(url)
            busy = false
            if (f == null) {
                error = "No se pudo leer ese enlace. Prueba con la URL del RSS, el @usuario o el ID del canal (UC…)."
            } else {
                Store.addFeed(f)
                url = ""
                onChanged()
            }
        }
    }

    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = {
            TextButton(onClick = { onClose(); onChanged() }) { Text("Listo") }
        },
        title = { Text("Fuentes") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Noticias por ticker", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                FlowRow(
                    modifier = Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Src.values().forEach { s ->
                        FilterChip(
                            selected = s.name in sources,
                            onClick = { Store.toggleSource(s) },
                            label = { Text(s.label) }
                        )
                    }
                }
                Text(
                    "Investing.com solo muestra noticias que nombran tu ticker o empresa.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
                HorizontalDivider(Modifier.padding(vertical = 14.dp))
                Text("RSS y YouTube propios", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("URL del RSS, @canal o enlace de YouTube") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { addFeed() }),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                TextButton(onClick = { addFeed() }, enabled = !busy) {
                    Text(if (busy) "Comprobando…" else "Añadir fuente")
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                feeds.forEach { f ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            (if (f.kind == "YT") "▶ " else "RSS · ") + f.name,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { Store.removeFeed(f) }) {
                            Icon(Icons.Default.Close, contentDescription = "Quitar ${f.name}")
                        }
                    }
                }
                if (feeds.isNotEmpty()) {
                    Text(
                        "De estas fuentes se muestran las últimas publicaciones, sin filtrar por ticker.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    )
}
