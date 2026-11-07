@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.truekio.chollos

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.util.UUID

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Store.init(this)
        Scheduler.ensure(this)
        setContent {
            val dark = isSystemInDarkTheme()
            val accent = Color(0xFFE11D48)
            MaterialTheme(colorScheme = if (dark) darkColorScheme(primary = accent) else lightColorScheme(primary = accent)) {
                val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    if (Build.VERSION.SDK_INT >= 33) perm.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                Root()
            }
        }
    }
}

@Composable
private fun Root() {
    var tab by remember { mutableStateOf(0) }
    val titles = listOf("Chollos", "Búsquedas", "Ajustes")
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        TabRow(selectedTabIndex = tab) {
            titles.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) }) }
        }
        when (tab) {
            0 -> DealsScreen()
            1 -> SearchesScreen()
            else -> SettingsScreen()
        }
    }
}

@Composable
private fun DealsScreen() {
    val ctx = LocalContext.current
    val deals by Store.deals.collectAsState()
    val loading by Store.loading.collectAsState()
    val scope = rememberCoroutineScope()
    val df = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
    Scaffold(floatingActionButton = {
        FloatingActionButton(onClick = { if (!loading) scope.launch { Checker.run(ctx) } }) {
            Icon(Icons.Default.Refresh, "Buscar ahora")
        }
    }) { pad ->
        if (deals.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(pad).padding(24.dp)) {
                Text(if (loading) "Buscando…" else "Aún no hay chollos.", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Crea una búsqueda en la pestaña Búsquedas. La primera pasada solo memoriza los anuncios existentes; " +
                        "los avisos llegan con los anuncios nuevos.",
                    Modifier.padding(top = 8.dp)
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(deals, key = { it.item.source + it.item.id }) { d ->
                    Card(Modifier.fillMaxWidth().clickable {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(d.item.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }) {
                        Column(Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(sourceLabel(d.item.source), fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                                Text("  ·  " + df.format(Date(d.time)), fontSize = 12.sp)
                            }
                            Text(d.item.title, Modifier.padding(top = 4.dp), fontWeight = FontWeight.Medium)
                            if (d.item.price > 0) Text(Checker.fmt(d.item.price), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            Text(d.reason + " · " + d.searchName, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchesScreen() {
    val searches by Store.searches.collectAsState()
    var adding by remember { mutableStateOf(false) }
    Scaffold(floatingActionButton = {
        FloatingActionButton(onClick = { adding = true }) { Icon(Icons.Default.Add, "Nueva búsqueda") }
    }) { pad ->
        if (searches.isEmpty()) {
            Text("Pulsa + para añadir lo que quieres vigilar.", Modifier.padding(pad).padding(24.dp))
        }
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(searches, key = { it.id }) { s ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(s.query, fontWeight = FontWeight.Bold)
                            val max = s.maxPrice?.let { "máx. ${Checker.fmt(it)} · " } ?: ""
                            if (s.include.isNotEmpty()) Text("Incluye: " + s.include.joinToString(", "), fontSize = 12.sp)
                            if (s.exclude.isNotEmpty()) Text("Excluye: " + s.exclude.joinToString(", "), fontSize = 12.sp)
                            Text("$max-${s.minDiscount}% vs mediana · " + s.sources.joinToString { sourceLabel(it) }, fontSize = 12.sp)
                        }
                        IconButton(onClick = {
                            Store.forget(s.id)
                            Store.setSearches(searches.filter { it.id != s.id })
                        }) { Icon(Icons.Default.Close, "Borrar") }
                    }
                }
            }
        }
    }
    if (adding) AddSearchDialog(onDismiss = { adding = false }) { s ->
        Store.setSearches(Store.searches.value + s)
        adding = false
    }
}

@Composable
private fun AddSearchDialog(onDismiss: () -> Unit, onAdd: (Search) -> Unit) {
    var query by remember { mutableStateOf("") }
    var max by remember { mutableStateOf("") }
    var disc by remember { mutableStateOf("30") }
    var inc by remember { mutableStateOf("") }
    var exc by remember { mutableStateOf("") }
    var sources by remember { mutableStateOf(ALL_SOURCES.toSet()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nueva búsqueda") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(query, { query = it }, label = { Text("Producto, corto (ej. zapatillas on)") }, singleLine = true)
                OutlinedTextField(inc, { inc = it }, label = { Text("Debe incluir (ej. 42, hombre)") }, singleLine = true)
                OutlinedTextField(exc, { exc = it }, label = { Text("Excluir (ej. mujer, niño)") }, singleLine = true)
                OutlinedTextField(max, { max = it }, label = { Text("Avisar si cuesta menos de (€, opcional)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(disc, { disc = it }, label = { Text("…o si está X% por debajo de lo normal") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ALL_SOURCES.forEach { src ->
                        FilterChip(selected = src in sources, onClick = { sources = if (src in sources) sources - src else sources + src },
                            label = { Text(sourceLabel(src)) })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = query.isNotBlank() && sources.isNotEmpty(), onClick = {
                onAdd(Search(UUID.randomUUID().toString().take(8), query.trim(), max.replace(",", ".").toDoubleOrNull(),
                    (disc.toIntOrNull() ?: 30).coerceIn(5, 90), sources, words(inc), words(exc)))
            }) { Text("Añadir") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

private fun words(s: String) = s.split(",", ";").map { it.trim() }.filter { it.isNotEmpty() }

@Composable
private fun SettingsScreen() {
    val ctx = LocalContext.current
    val interval by Store.intervalMin.collectAsState()
    val lastRun by Store.lastRun.collectAsState()
    val status by Store.lastStatus.collectAsState()
    val df = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
    val options = listOf(15, 30, 60, 180, 360)
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Revisar cada", fontWeight = FontWeight.Bold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { m ->
                FilterChip(selected = interval == m, onClick = { Store.setInterval(m); Scheduler.ensure(ctx) },
                    label = { Text(if (m < 60) "$m min" else "${m / 60} h") })
            }
        }
        Text("Android no permite menos de 15 min y puede retrasar los avisos con la pantalla apagada para ahorrar batería.", fontSize = 12.sp)
        OutlinedButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) {
            Text("Quitar límites de batería")
        }
        Button(onClick = { Store.clearDeals() }) { Text("Borrar lista de chollos") }
        Text("Última revisión: " + (if (lastRun == 0L) "nunca" else df.format(Date(lastRun))))
        if (status.isNotBlank()) Text(status, fontSize = 12.sp)
    }
}
