@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.truekio.tareas

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

private fun defaultDue(): Long {
    val z = ZoneId.systemDefault()
    val t = Instant.ofEpochMilli(System.currentTimeMillis()).atZone(z).plusHours(1).withMinute(0).withSecond(0).withNano(0)
    return t.toInstant().toEpochMilli()
}

@Composable
fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary
    )
}

@Composable
fun TaskEditor(
    initial: Task?,
    cats: List<Category>,
    defaultCategoryId: String?,
    onSave: (Task) -> Unit,
    onDelete: (() -> Unit)?,
    onClose: () -> Unit
) {
    val ctx = LocalContext.current
    val zone = ZoneId.systemDefault()

    var title by remember { mutableStateOf(initial?.title ?: "") }
    var notes by remember { mutableStateOf(initial?.notes ?: "") }
    var catId by remember { mutableStateOf(initial?.categoryId ?: defaultCategoryId ?: cats.first().id) }
    var due by remember { mutableLongStateOf(initial?.due ?: defaultDue()) }
    var repeat by remember { mutableStateOf(initial?.repeat ?: Repeat.NONE) }
    var interval by remember { mutableIntStateOf(initial?.interval ?: 1) }
    var weekdays by remember { mutableIntStateOf(initial?.weekdays ?: 0) }
    var reminders by remember { mutableStateOf((initial?.reminders ?: listOf(0)).toSet()) }
    var nag by remember { mutableIntStateOf(initial?.nag ?: 0) }
    var confirmDelete by remember { mutableStateOf(false) }

    val zdt = Instant.ofEpochMilli(due).atZone(zone)

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .imePadding()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Cerrar") }
                    Text(
                        if (initial == null) "Nueva tarea" else "Editar tarea",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        enabled = title.isNotBlank(),
                        onClick = {
                            onSave(
                                Task(
                                    id = initial?.id ?: UUID.randomUUID().toString(),
                                    title = title.trim(),
                                    notes = notes.trim(),
                                    categoryId = catId,
                                    due = due,
                                    repeat = repeat,
                                    interval = interval.coerceAtLeast(1),
                                    weekdays = if (repeat == Repeat.WEEKLY) weekdays else 0,
                                    reminders = reminders.sorted(),
                                    done = initial?.done ?: false,
                                    nag = nag
                                )
                            )
                        }
                    ) { Text("Guardar") }
                    Box(Modifier.size(8.dp))
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text("Tarea") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = notes,
                        onValueChange = { notes = it },
                        label = { Text("Notas (opcional)") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2
                    )

                    SectionLabel("Categoría")
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        cats.forEach { c ->
                            FilterChip(
                                selected = catId == c.id,
                                onClick = { catId = c.id },
                                label = { Text(c.name) },
                                leadingIcon = {
                                    Box(Modifier.size(10.dp).clip(CircleShape).background(Color(c.color)))
                                }
                            )
                        }
                    }

                    SectionLabel("Cuándo")
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            onClick = {
                                DatePickerDialog(
                                    ctx,
                                    { _, y, m, d ->
                                        due = LocalDate.of(y, m + 1, d).atTime(zdt.toLocalTime())
                                            .atZone(zone).toInstant().toEpochMilli()
                                    },
                                    zdt.year, zdt.monthValue - 1, zdt.dayOfMonth
                                ).show()
                            }
                        ) { Text(Fmt.day(zdt.toLocalDate())) }
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            onClick = {
                                TimePickerDialog(
                                    ctx,
                                    { _, h, min ->
                                        due = zdt.toLocalDate().atTime(h, min).atZone(zone).toInstant().toEpochMilli()
                                    },
                                    zdt.hour, zdt.minute, true
                                ).show()
                            }
                        ) { Text(Fmt.time(due)) }
                    }

                    SectionLabel("Repetir")
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Repeat.values().forEach { r ->
                            val isWeekdays = repeat == Repeat.WEEKLY && weekdays == 31
                            FilterChip(
                                selected = if (r == Repeat.WEEKLY) repeat == r && !isWeekdays else repeat == r,
                                onClick = {
                                    repeat = r
                                    if (r == Repeat.WEEKLY && weekdays == 31) weekdays = 0
                                },
                                label = { Text(r.label) }
                            )
                            if (r == Repeat.DAILY) {
                                FilterChip(
                                    selected = isWeekdays,
                                    onClick = {
                                        repeat = Repeat.WEEKLY
                                        weekdays = 31
                                    },
                                    label = { Text("Laborables (L–V)") }
                                )
                            }
                        }
                    }
                    if (repeat == Repeat.WEEKLY) {
                        Text(
                            "Días de la semana (vacío = el mismo día de la tarea)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("L", "M", "X", "J", "V", "S", "D").forEachIndexed { i, n ->
                                FilterChip(
                                    selected = (weekdays and (1 shl i)) != 0,
                                    onClick = { weekdays = weekdays xor (1 shl i) },
                                    label = { Text(n) }
                                )
                            }
                        }
                    }
                    if (repeat != Repeat.NONE && !(repeat == Repeat.WEEKLY && weekdays != 0)) {
                        val unit = when (repeat) {
                            Repeat.DAILY -> "día(s)"
                            Repeat.WEEKLY -> "semana(s)"
                            Repeat.MONTHLY -> "mes(es)"
                            else -> "año(s)"
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Cada")
                            OutlinedButton(
                                modifier = Modifier.padding(start = 10.dp),
                                onClick = { if (interval > 1) interval-- }
                            ) { Text("−") }
                            Text("$interval", modifier = Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.titleMedium)
                            OutlinedButton(onClick = { if (interval < 99) interval++ }) { Text("+") }
                            Text(unit, modifier = Modifier.padding(start = 10.dp))
                        }
                    }

                    SectionLabel("Avisos")
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        REMINDER_OPTIONS.forEach { (min, label) ->
                            FilterChip(
                                selected = min in reminders,
                                onClick = { reminders = if (min in reminders) reminders - min else reminders + min },
                                label = { Text(label) }
                            )
                        }
                    }

                    SectionLabel("Insistir hasta marcar como hecha")
                    Text(
                        "Tras la hora de la tarea, el aviso se repite hasta que la completes o la pospongas (máx. 30 veces).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        listOf(0 to "No", 5 to "Cada 5 min", 10 to "Cada 10 min", 15 to "Cada 15 min").forEach { (m, label) ->
                            FilterChip(selected = nag == m, onClick = { nag = m }, label = { Text(label) })
                        }
                    }

                    if (onDelete != null) {
                        TextButton(onClick = { confirmDelete = true }) {
                            Text("Eliminar tarea", color = MaterialTheme.colorScheme.error)
                        }
                    }
                    Box(Modifier.size(24.dp))
                }
            }
        }
    }

    if (confirmDelete && onDelete != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("¿Eliminar tarea?") },
            text = { Text(initial?.title ?: "") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) {
                    Text("Eliminar", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancelar") } }
        )
    }
}
