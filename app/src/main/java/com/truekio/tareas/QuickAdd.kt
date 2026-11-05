@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.truekio.tareas

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID

/** Alta rápida desde el widget: título, momento y categoría en un diálogo. */
class QuickAddActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TaskRepository.init(this)
        setContent {
            AppTheme {
                QuickAdd(
                    onClose = { finish() },
                    onMore = {
                        startActivity(
                            Intent(this, MainActivity::class.java)
                                .putExtra("new", true)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        )
                        finish()
                    }
                )
            }
        }
    }
}

private fun quickOptions(): List<Pair<String, Long>> {
    val z = ZoneId.systemDefault()
    val now = ZonedDateTime.now(z)
    val list = mutableListOf("En 1 hora" to now.plusHours(1).withSecond(0).withNano(0).toInstant().toEpochMilli())
    val tonight = now.toLocalDate().atTime(20, 0).atZone(z)
    if (tonight.isAfter(now.plusMinutes(30))) list.add("Hoy 20:00" to tonight.toInstant().toEpochMilli())
    list.add("Mañana 09:00" to now.toLocalDate().plusDays(1).atTime(9, 0).atZone(z).toInstant().toEpochMilli())
    return list
}

@Composable
fun QuickAdd(onClose: () -> Unit, onMore: () -> Unit) {
    val cats by TaskRepository.categories.collectAsState()
    var title by remember { mutableStateOf("") }
    var catId by remember { mutableStateOf(cats.first().id) }
    val options = remember { quickOptions() }
    var sel by remember { mutableIntStateOf(0) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) {
        focus.requestFocus()
        keyboard?.show()
    }

    fun save() {
        if (title.isBlank()) return
        TaskRepository.upsert(
            Task(
                id = UUID.randomUUID().toString(),
                title = title.trim(),
                categoryId = catId,
                due = options[sel].second,
                reminders = listOf(0)
            )
        )
        onClose()
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Nueva tarea") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Tarea") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { save() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus)
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    options.forEachIndexed { i, (label, _) ->
                        FilterChip(selected = sel == i, onClick = { sel = i }, label = { Text(label) })
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    cats.forEach { c ->
                        FilterChip(
                            selected = catId == c.id,
                            onClick = { catId = c.id },
                            label = { Text(c.name) },
                            leadingIcon = { Box(Modifier.size(10.dp).clip(CircleShape).background(Color(c.color))) }
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = title.isNotBlank(), onClick = { save() }) { Text("Añadir") } },
        dismissButton = {
            Row {
                TextButton(onClick = onMore) { Text("Más opciones") }
                TextButton(onClick = onClose) { Text("Cancelar") }
            }
        }
    )
}
