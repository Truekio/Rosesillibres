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
                    onAskBattery = { askBattery() }
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
