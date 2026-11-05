@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.truekio.tareas

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Shapes
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

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

private val SECTION_TITLES = listOf("Atrasadas", "Hoy", "Mañana", "Esta semana", "Más adelante")

@Composable
fun MainScreen(
    openNew: Boolean,
    onNewConsumed: () -> Unit,
    onAskBattery: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    pendingImport: String?,
    onImportDecision: (Boolean) -> Unit
) {
    val ctx = LocalContext.current
    val tasks by TaskRepository.tasks.collectAsState()
    val cats by TaskRepository.categories.collectAsState()
    var filter by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<Task?>(null) }
    var creating by remember { mutableStateOf(false) }
    var showCats by remember { mutableStateOf(false) }
    var showDone by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }
    LaunchedEffect(openNew) {
        if (openNew) {
            creating = true
            onNewConsumed()
        }
    }

    val activeFilter = filter?.takeIf { f -> cats.any { it.id == f } }
    val visible = tasks.filter { activeFilter == null || it.categoryId == activeFilter }
    val pending = visible.filter { !it.done }.sortedBy { it.due }
    val done = visible.filter { it.done }.sortedByDescending { it.due }

    val zone = ZoneId.systemDefault()
    val today: LocalDate = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    fun bucket(t: Task): Int {
        if (t.due < now) return 0
        val d = Instant.ofEpochMilli(t.due).atZone(zone).toLocalDate()
        return when {
            d == today -> 1
            d == today.plusDays(1) -> 2
            d <= today.plusDays(7) -> 3
            else -> 4
        }
    }
    val grouped = pending.groupBy { bucket(it) }.toSortedMap()

    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    fun withUndo(msg: String, before: Task, action: () -> Unit) {
        action()
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val r = snackbar.showSnackbar(msg, actionLabel = "Deshacer", duration = SnackbarDuration.Short)
            if (r == SnackbarResult.ActionPerformed) TaskRepository.upsert(before)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Mis tareas", fontWeight = FontWeight.Bold) },
                actions = {
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Más")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(text = { Text("Categorías y colores") }, onClick = {
                                menuOpen = false
                                showCats = true
                            })
                            DropdownMenuItem(text = { Text("Exportar copia de seguridad") }, onClick = {
                                menuOpen = false
                                onExport()
                            })
                            DropdownMenuItem(text = { Text("Importar copia de seguridad") }, onClick = {
                                menuOpen = false
                                onImport()
                            })
                            DropdownMenuItem(text = { Text("Evitar ahorro de batería") }, onClick = {
                                menuOpen = false
                                onAskBattery()
                            })
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { creating = true },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Nueva tarea") }
            )
        }
    ) { pad ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(top = pad.calculateTopPadding()),
            contentPadding = PaddingValues(bottom = 120.dp)
        ) {
            item(key = "filters") {
                Row(
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = activeFilter == null,
                        onClick = { filter = null },
                        label = { Text("Todas") }
                    )
                    cats.forEach { c ->
                        FilterChip(
                            selected = activeFilter == c.id,
                            onClick = { filter = if (filter == c.id) null else c.id },
                            label = { Text(c.name) },
                            leadingIcon = {
                                Box(Modifier.size(10.dp).clip(CircleShape).background(Color(c.color)))
                            }
                        )
                    }
                }
            }

            if (pending.isEmpty() && done.isEmpty()) {
                item(key = "empty") {
                    Text(
                        "No hay tareas.\nPulsa «Nueva tarea» para empezar.",
                        modifier = Modifier.fillMaxWidth().padding(48.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }

            grouped.forEach { (b, list) ->
                item(key = "h$b") {
                    Text(
                        "${SECTION_TITLES[b]} · ${list.size}",
                        modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (b == 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                }
                items(list, key = { it.id }) { t ->
                    TaskCard(
                        t = t,
                        cat = cats.firstOrNull { it.id == t.categoryId },
                        now = now,
                        onToggle = { withUndo("Tarea completada", t) { TaskRepository.complete(t.id); Notifier.dismiss(ctx, t.id) } },
                        onSnooze = { s -> withUndo("Pospuesta ${s.label.removePrefix("+")}", t) { TaskRepository.snooze(t.id, s) } },
                        onEdit = { editing = t }
                    )
                }
            }

            if (done.isNotEmpty()) {
                item(key = "doneHeader") {
                    TextButton(
                        onClick = { showDone = !showDone },
                        modifier = Modifier.padding(start = 8.dp, top = 8.dp)
                    ) {
                        Text(if (showDone) "Ocultar hechas (${done.size})" else "Ver hechas (${done.size})")
                    }
                }
                if (showDone) {
                    items(done, key = { it.id }) { t ->
                        TaskCard(
                            t = t,
                            cat = cats.firstOrNull { it.id == t.categoryId },
                            now = now,
                            onToggle = { TaskRepository.reopen(t.id) },
                            onSnooze = {},
                            onEdit = { editing = t }
                        )
                    }
                }
            }
        }
    }

    if (creating) {
        TaskEditor(
            initial = null,
            cats = cats,
            defaultCategoryId = activeFilter,
            onSave = { TaskRepository.upsert(it); creating = false },
            onDelete = null,
            onClose = { creating = false }
        )
    }
    editing?.let { t ->
        TaskEditor(
            initial = t,
            cats = cats,
            defaultCategoryId = null,
            onSave = { TaskRepository.upsert(it); editing = null },
            onDelete = { withUndo("Tarea eliminada", t) { TaskRepository.delete(t.id) }; editing = null },
            onClose = { editing = null }
        )
    }
    pendingImport?.let { text ->
        val n = remember(text) { TaskRepository.parseBackup(text)?.second?.size ?: 0 }
        AlertDialog(
            onDismissRequest = { onImportDecision(false) },
            title = { Text("¿Importar copia?") },
            text = { Text("Se reemplazarán tus tareas actuales por las $n de la copia.") },
            confirmButton = { TextButton(onClick = { onImportDecision(true) }) { Text("Reemplazar") } },
            dismissButton = { TextButton(onClick = { onImportDecision(false) }) { Text("Cancelar") } }
        )
    }
    if (showCats) {
        CategoriesDialog(cats = cats, onClose = { showCats = false })
    }
}

@Composable
fun TaskCard(
    t: Task,
    cat: Category?,
    now: Long,
    onToggle: () -> Unit,
    onSnooze: (Snooze) -> Unit,
    onEdit: () -> Unit
) {
    val color = Color(cat?.color ?: 0xFF64748BL)
    val overdue = !t.done && t.due < now
    var menu by remember { mutableStateOf(false) }

    Card(
        onClick = onEdit,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Row(
            modifier = Modifier.height(IntrinsicSize.Min),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.width(6.dp).fillMaxHeight().background(color))
            IconButton(onClick = onToggle) {
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(if (t.done) color else Color.Transparent)
                        .border(BorderStroke(2.dp, color), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    if (t.done) {
                        Icon(Icons.Default.Check, contentDescription = "Hecha", tint = Color.White, modifier = Modifier.size(16.dp))
                    }
                }
            }
            Column(Modifier.weight(1f).padding(vertical = 12.dp)) {
                Text(
                    t.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    textDecoration = if (t.done) TextDecoration.LineThrough else null,
                    color = if (t.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                )
                val sub = buildString {
                    append(Fmt.due(t.due))
                    if (t.repeat != Repeat.NONE) append("  ·  🔁 ${Fmt.repeat(t)}")
                    if (t.reminders.size > 1) append("  ·  🔔 ${t.reminders.size}")
                }
                Text(
                    sub,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (cat != null) {
                    Text(cat.name, style = MaterialTheme.typography.labelSmall, color = color)
                }
            }
            if (!t.done) {
                Box {
                    IconButton(onClick = { menu = true }) { Text("⏰") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        Snooze.values().forEach { s ->
                            DropdownMenuItem(text = { Text("Posponer ${s.label}") }, onClick = {
                                menu = false
                                onSnooze(s)
                            })
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CategoriesDialog(cats: List<Category>, onClose: () -> Unit) {
    var editing by remember { mutableStateOf<Category?>(null) }

    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = onClose) { Text("Cerrar") } },
        title = { Text("Categorías") },
        text = {
            Column {
                cats.forEach { c ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { editing = c }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.size(20.dp).clip(CircleShape).background(Color(c.color)))
                        Text(c.name, modifier = Modifier.padding(start = 14.dp))
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                TextButton(onClick = { editing = Category("", "", PALETTE[cats.size % PALETTE.size]) }) {
                    Text("+ Nueva categoría")
                }
            }
        }
    )

    editing?.let { c ->
        var name by remember(c) { mutableStateOf(c.name) }
        var color by remember(c) { mutableStateOf(c.color) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(if (c.id.isEmpty()) "Nueva categoría" else "Editar categoría") },
            text = {
                Column {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        singleLine = true,
                        label = { Text("Nombre") }
                    )
                    FlowRow(
                        modifier = Modifier.padding(top = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        PALETTE.forEach { p ->
                            Box(
                                Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(Color(p))
                                    .border(
                                        if (p == color) BorderStroke(3.dp, MaterialTheme.colorScheme.onSurface) else BorderStroke(0.dp, Color.Transparent),
                                        CircleShape
                                    )
                                    .clickable { color = p }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank(),
                    onClick = {
                        TaskRepository.upsertCategory(
                            Category(c.id.ifEmpty { UUID.randomUUID().toString() }, name.trim(), color)
                        )
                        editing = null
                    }
                ) { Text("Guardar") }
            },
            dismissButton = {
                Row {
                    if (c.id.isNotEmpty() && cats.size > 1) {
                        TextButton(onClick = {
                            TaskRepository.deleteCategory(c.id)
                            editing = null
                        }) { Text("Eliminar", color = MaterialTheme.colorScheme.error) }
                    }
                    TextButton(onClick = { editing = null }) { Text("Cancelar") }
                }
            }
        )
    }
}
