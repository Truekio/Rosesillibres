package com.truekio.tareas

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxWidth

class MainActivity : ComponentActivity() {
    private var openNew by mutableStateOf(false)
    private var importText by mutableStateOf<String?>(null)

    private fun toast(msg: String) = android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show()

    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) {
                val ok = runCatching {
                    contentResolver.openOutputStream(uri)!!.use { it.write(TaskRepository.exportJson().toByteArray(Charsets.UTF_8)) }
                }.isSuccess
                toast(if (ok) "Copia guardada" else "No se pudo guardar la copia")
            }
        }

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                val text = runCatching {
                    contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
                }.getOrNull()
                if (text != null && TaskRepository.parseBackup(text) != null) importText = text
                else toast("Ese archivo no es una copia válida")
            }
        }

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        TaskRepository.init(this)
        Notifier.ensureChannel(this)
        Scheduler.rescheduleAll(this)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        openNew = intent.getBooleanExtra("new", false)
        setContent {
            AppTheme {
                MainScreen(
                    openNew = openNew,
                    onNewConsumed = { openNew = false },
                    onAskBattery = { askBattery() },
                    onExport = { exportLauncher.launch("tareas-${java.time.LocalDate.now()}.json") },
                    onImport = { importLauncher.launch(arrayOf("*/*")) },
                    pendingImport = importText,
                    onImportDecision = { ok ->
                        val text = importText
                        importText = null
                        if (ok && text != null) {
                            TaskRepository.parseBackup(text)?.let {
                                TaskRepository.importBackup(it.first, it.second)
                                toast("Copia importada: ${it.second.size} tareas")
                            }
                        }
                    }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra("new", false)) openNew = true
    }

    private fun askBattery() {
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) {
            runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
    }
}

/** Diálogo que se abre desde la notificación para posponer o marcar como hecha. */
class SnoozeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TaskRepository.init(this)
        val id = intent.getStringExtra("id")
        val task = id?.let { TaskRepository.find(it) }
        if (id == null || task == null) {
            finish()
            return
        }
        setContent {
            AppTheme {
                AlertDialog(
                    onDismissRequest = { finish() },
                    title = { Text(task.title) },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Snooze.values().forEach { s ->
                                FilledTonalButton(
                                    modifier = Modifier.fillMaxWidth(),
                                    onClick = {
                                        TaskRepository.snooze(id, s)
                                        Notifier.dismiss(this@SnoozeActivity, id)
                                        finish()
                                    }
                                ) { Text("Posponer ${s.label.removePrefix("+")}") }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            TaskRepository.complete(id)
                            Notifier.dismiss(this@SnoozeActivity, id)
                            finish()
                        }) { Text("Hecho") }
                    },
                    dismissButton = { TextButton(onClick = { finish() }) { Text("Cerrar") } }
                )
            }
        }
    }
}
