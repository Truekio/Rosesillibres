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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

private val darkScheme = darkColorScheme(
    primary = Color(0xFFFF5C7A), onPrimary = Color(0xFF2B0009),
    primaryContainer = Color(0xFF4A1523), onPrimaryContainer = Color(0xFFFFD9DF),
    secondary = Color(0xFFFF8FA3), onSecondary = Color(0xFF2B0009),
    secondaryContainer = Color(0xFF3F1B26), onSecondaryContainer = Color(0xFFFFD9DF),
    tertiary = Color(0xFFFFB86B),
    background = Color(0xFF131316), onBackground = Color(0xFFEDEDF0),
    surface = Color(0xFF131316), onSurface = Color(0xFFEDEDF0),
    surfaceVariant = Color(0xFF2A2A30), onSurfaceVariant = Color(0xFFB8B8C2),
    surfaceContainer = Color(0xFF1B1B20), surfaceContainerHigh = Color(0xFF222228), surfaceContainerHighest = Color(0xFF26262D),
    outline = Color(0xFF5A5A64)
)

private val lightScheme = lightColorScheme(
    primary = Color(0xFFE11D48), onPrimary = Color.White,
    primaryContainer = Color(0xFFFFD9DF), onPrimaryContainer = Color(0xFF40000F),
    secondary = Color(0xFFBE123C), onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFE3E8), onSecondaryContainer = Color(0xFF40000F),
    tertiary = Color(0xFFB45309),
    background = Color(0xFFFAF8F9), onBackground = Color(0xFF1B1B1F),
    surface = Color(0xFFFAF8F9), onSurface = Color(0xFF1B1B1F),
    surfaceVariant = Color(0xFFEFE9EB), onSurfaceVariant = Color(0xFF55505A),
    surfaceContainer = Color(0xFFF3EEF0), surfaceContainerHigh = Color(0xFFFFFFFF), surfaceContainerHighest = Color(0xFFFFFFFF),
    outline = Color(0xFFB9B2B7)
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Store.init(this)
        Scheduler.ensure(this)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkScheme else lightScheme) {
                val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
                LaunchedEffect(Unit) {
                    if (Build.VERSION.SDK_INT >= 33) perm.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                Root()
            }
        }
    }
}

private val whenFmt = SimpleDateFormat("d MMM HH:mm", Locale("es"))
private val timeFmt = SimpleDateFormat("HH:mm", Locale("es"))

@Composable
private fun Root() {
    var tab by remember { mutableStateOf(0) }
    val deals by Store.deals.collectAsState()
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar {
                val tabs = listOf("Chollos" to Icons.Default.Notifications, "Guardados" to Icons.Default.Favorite,
                    "Búsquedas" to Icons.Default.Search, "Ajustes" to Icons.Default.Settings)
                tabs.forEachIndexed { i, (label, icon) ->
                    NavigationBarItem(
                        selected = tab == i, onClick = { tab = i }, label = { Text(label, fontSize = 12.sp) },
                        icon = {
                            if (i == 0 && deals.isNotEmpty()) BadgedBox(badge = { Badge { Text("${deals.size}") } }) { Icon(icon, label) }
                            else Icon(icon, label)
                        }
                    )
                }
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                0 -> DealsScreen()
                1 -> SavedScreen()
                2 -> SearchesScreen()
                else -> SettingsScreen()
            }
        }
    }
}

@Composable
private fun Header(title: String, subtitle: String = "", action: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        action()
    }
}

@Composable
private fun Empty(title: String, text: String) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(text, Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DealsScreen() {
    val ctx = LocalContext.current
    val deals by Store.deals.collectAsState()
    val searches by Store.searches.collectAsState()
    val loading by Store.loading.collectAsState()
    val lastRun by Store.lastRun.collectAsState()
    var selected by remember { mutableStateOf<String?>(null) }
    var byPrice by remember { mutableStateOf(false) }
    // Si se borra la búsqueda seleccionada, volvemos a "Todos"
    if (selected != null && searches.none { it.id == selected }) selected = null
    val shown = deals
        .filter { selected == null || it.searchId == selected }
        .let { l -> if (byPrice) l.sortedBy { if (it.item.price > 0) it.item.price else Double.MAX_VALUE } else l.sortedByDescending { it.time } }
    val sub = when {
        loading -> "Buscando…"
        lastRun == 0L -> "Aún sin revisar"
        else -> "Revisado a las " + timeFmt.format(Date(lastRun))
    }
    Column(Modifier.fillMaxSize()) {
        Header("Chollos", sub) {
            TextButton(onClick = { byPrice = !byPrice }) { Text(if (byPrice) "Más baratos" else "Recientes") }
            IconButton(onClick = { if (!loading) Checker.runAsync(ctx) }) {
                if (loading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                else Icon(Icons.Default.Refresh, "Buscar ahora")
            }
        }
        if (searches.size > 1 && deals.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(selected = selected == null, onClick = { selected = null }, label = { Text("Todos · ${deals.size}") })
                searches.forEach { sr ->
                    val n = deals.count { it.searchId == sr.id }
                    Chip(selected = selected == sr.id, onClick = { selected = sr.id }, label = { Text("${sr.query} · $n") })
                }
            }
        }
        if (shown.isEmpty()) {
            Empty(
                if (loading) "Buscando…" else "Sin chollos por ahora",
                if (deals.isEmpty()) "Crea una búsqueda en la pestaña Búsquedas. Cuando salga un anuncio por debajo de tu precio, aparecerá aquí y te llegará un aviso."
                else "No hay chollos de esta búsqueda."
            )
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp, 4.dp, 12.dp, 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(shown, key = { it.item.source + it.item.id }) { d ->
                    DealCard(d, saved = false, onSave = { Store.save(d) }, onDismiss = { Store.dismiss(d) },
                        onBlock = { Store.blockSeller(d.item); Store.dismiss(d) })
                }
            }
        }
    }
}

@Composable
private fun SavedScreen() {
    val saved by Store.saved.collectAsState()
    Column(Modifier.fillMaxSize()) {
        Header("Guardados", if (saved.isEmpty()) "" else "${saved.size} anuncios")
        if (saved.isEmpty()) {
            Empty("Nada guardado", "Pulsa el corazón en un chollo para guardarlo aquí y quitarlo de la lista principal.")
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp, 4.dp, 12.dp, 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(saved, key = { "s" + it.item.source + it.item.id }) { d ->
                    DealCard(d, saved = true, onSave = { Store.unsave(d) }, onDismiss = null, onBlock = null)
                }
            }
        }
    }
}

@Composable
private fun DealCard(d: Deal, saved: Boolean, onSave: () -> Unit, onDismiss: (() -> Unit)?, onBlock: (() -> Unit)?) {
    val ctx = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth().clickable {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(d.item.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }) {
                if (d.item.image != null) {
                    AsyncImage(
                        model = d.item.image, contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.size(96.dp).clip(RoundedCornerShape(10.dp))
                    )
                    Spacer(Modifier.width(12.dp))
                }
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                            Text(sourceLabel(d.item.source), Modifier.padding(horizontal = 7.dp, vertical = 2.dp), fontSize = 11.sp,
                                fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                        Text("  " + whenFmt.format(Date(d.time)), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(d.item.title, Modifier.padding(top = 4.dp), fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (d.item.price > 0) Text(Checker.fmt(d.item.price), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Text(d.reason.removePrefix("Ya publicado · ") + " · " + d.searchName, fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                if (onBlock != null && d.item.seller.isNotBlank()) {
                    TextButton(onClick = onBlock) { Text("Bloquear vendedor", fontSize = 12.sp) }
                }
                if (onDismiss != null) IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Descartar") }
                IconButton(onClick = onSave) {
                    Icon(if (saved) Icons.Default.Favorite else Icons.Default.FavoriteBorder, if (saved) "Quitar de guardados" else "Guardar",
                        tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable
private fun SearchesScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val searches by Store.searches.collectAsState()
    val stats by Store.stats.collectAsState()
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Search?>(null) }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Header("Búsquedas", if (searches.isEmpty()) "" else "${searches.size} activas")
            if (searches.isEmpty()) {
                Empty("Nada que vigilar", "Pulsa «Nueva» y escribe un producto corto, por ejemplo «zapatillas on». Los filtros (talla, precio) se ajustan después.")
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp, 4.dp, 12.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(searches, key = { it.id }) { s ->
                        Card(Modifier.fillMaxWidth().clickable { editing = s }) {
                            Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(s.query, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    val max = s.maxPrice?.let { "Máx. ${Checker.fmt(it)}" } ?: "Sin precio máximo"
                                    Text("$max · -${s.minDiscount}% vs mediana", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    stats[s.id]?.let { Text("Última revisión: $it", fontSize = 13.sp, color = MaterialTheme.colorScheme.tertiary) }
                                    if (s.include.isNotEmpty()) Text("Incluye: " + s.include.joinToString(", "), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    if (s.exclude.isNotEmpty()) Text("Excluye: " + s.exclude.joinToString(", "), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(s.sources.joinToString(" · ") { sourceLabel(it) } + "  ·  toca para editar", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
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
        }
        ExtendedFloatingActionButton(
            onClick = { adding = true },
            icon = { Icon(Icons.Default.Add, null) },
            text = { Text("Nueva") },
            containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
        )
    }
    if (adding) SearchDialog(null, onDismiss = { adding = false }) { s ->
        Store.setSearches(Store.searches.value + s)
        adding = false
        Checker.runAsync(ctx)
    }
    editing?.let { old ->
        SearchDialog(old, onDismiss = { editing = null }) { s ->
            // Si cambia algo, se empieza de cero para que los anuncios que ahora encajan no salgan como nuevos.
            if (s != old) Store.forget(old.id)
            Store.setSearches(Store.searches.value.map { if (it.id == old.id) s else it })
            editing = null
            if (s != old) Checker.runAsync(ctx)
        }
    }
}

@Composable
private fun SearchDialog(initial: Search?, onDismiss: () -> Unit, onAdd: (Search) -> Unit) {
    var query by remember { mutableStateOf(initial?.query ?: "") }
    var max by remember { mutableStateOf(initial?.maxPrice?.let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() } ?: "") }
    var disc by remember { mutableStateOf((initial?.minDiscount ?: 30).toString()) }
    var inc by remember { mutableStateOf(initial?.include?.joinToString(", ") ?: "") }
    var exc by remember { mutableStateOf(initial?.exclude?.joinToString(", ") ?: "") }
    var sources by remember { mutableStateOf(initial?.sources ?: DEFAULT_SOURCES) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Nueva búsqueda" else "Editar búsqueda") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(query, { query = it }, label = { Text("Producto, corto (ej. zapatillas on)") }, singleLine = true)
                OutlinedTextField(max, { max = it }, label = { Text("Precio máximo en € (opcional)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(inc, { inc = it }, label = { Text("Debe incluir (ej. 42, hombre/hombres)") }, singleLine = true)
                OutlinedTextField(exc, { exc = it }, label = { Text("Excluir (ej. mujer, niño)") }, singleLine = true)
                OutlinedTextField(disc, { disc = it }, label = { Text("Avisar si está X% bajo lo normal") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ALL_SOURCES.forEach { src ->
                        Chip(selected = src in sources, onClick = { sources = if (src in sources) sources - src else sources + src },
                            label = { Text(sourceLabel(src)) })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = query.isNotBlank() && sources.isNotEmpty(), onClick = {
                onAdd(Search(initial?.id ?: UUID.randomUUID().toString().take(8), query.trim(), max.replace(",", ".").toDoubleOrNull(),
                    (disc.toIntOrNull() ?: 30).coerceIn(5, 90), sources, words(inc), words(exc)))
            }) { Text(if (initial == null) "Añadir" else "Guardar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

private fun words(s: String) = s.split(",", ";").map { it.trim() }.filter { it.isNotEmpty() }

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
private fun SettingsScreen() {
    val ctx = LocalContext.current
    val interval by Store.intervalMin.collectAsState()
    val lastRun by Store.lastRun.collectAsState()
    val status by Store.lastStatus.collectAsState()
    val place by Store.place.collectAsState()
    val radius by Store.radiusKm.collectAsState()
    val orShip by Store.orShip.collectAsState()
    val blocked by Store.blocked.collectAsState()
    val dismissed by Store.dismissed.collectAsState()
    Column(Modifier.fillMaxSize()) {
        Header("Ajustes")
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp, 4.dp, 12.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Section("Wallapop · zona") {
                Text("Ciudad de referencia", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PLACES.forEach { p -> Chip(selected = place.name == p.name, onClick = { Store.setPlace(p) }, label = { Text(p.name) }) }
                }
                Text("Distancia máxima", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0, 10, 25, 50, 100, 200).forEach { km ->
                        Chip(selected = radius == km, onClick = { Store.setRadius(km) }, label = { Text(if (km == 0) "Toda España" else "$km km") })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("También anuncios con envío, aunque estén lejos", Modifier.weight(1f))
                    Switch(checked = orShip, onCheckedChange = { Store.setOrShip(it) })
                }
            }
            Section("Frecuencia") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(15, 30, 60, 180, 360).forEach { m ->
                        Chip(selected = interval == m, onClick = { Store.setInterval(m); Scheduler.ensure(ctx) },
                            label = { Text(if (m < 60) "$m min" else "${m / 60} h") })
                    }
                }
                Text("Android no permite menos de 15 min y puede retrasar los avisos con la pantalla apagada para ahorrar batería.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) {
                    Text("Quitar límites de batería")
                }
            }
            Section("Última revisión") {
                Text(if (lastRun == 0L) "Nunca" else whenFmt.format(Date(lastRun)), fontWeight = FontWeight.Medium)
                if (status.isNotBlank()) Text(status, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Section("Datos") {
                OutlinedButton(onClick = { Store.clearDeals() }) { Text("Vaciar lista de chollos") }
                if (blocked.isNotEmpty()) OutlinedButton(onClick = { Store.clearBlocked() }) { Text("Desbloquear ${blocked.size} vendedores") }
                if (dismissed.isNotEmpty()) OutlinedButton(onClick = { Store.clearDismissed() }) { Text("Olvidar ${dismissed.size} descartados") }
            }
        }
    }
}

/** Opción seleccionable con ✓ cuando está activa, para que se vea claro qué está marcado. */
@Composable
private fun Chip(selected: Boolean, onClick: () -> Unit, label: @Composable () -> Unit) {
    FilterChip(
        selected = selected, onClick = onClick, label = label,
        leadingIcon = if (selected) ({ Icon(Icons.Default.Check, null, Modifier.size(18.dp)) }) else null
    )
}
