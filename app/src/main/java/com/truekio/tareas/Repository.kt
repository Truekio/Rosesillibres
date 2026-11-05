package com.truekio.tareas

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import androidx.glance.appwidget.updateAll

object TaskRepository {
    private var ctx: Context? = null

    private val _tasks = MutableStateFlow<List<Task>>(emptyList())
    val tasks: StateFlow<List<Task>> = _tasks.asStateFlow()

    private val _categories = MutableStateFlow<List<Category>>(emptyList())
    val categories: StateFlow<List<Category>> = _categories.asStateFlow()

    @Synchronized
    fun init(context: Context) {
        if (ctx != null) return
        ctx = context.applicationContext
        load()
    }

    private fun file() = File(ctx!!.filesDir, "data.json")

    private fun defaultCategories() = listOf(
        Category("personal", "Personal", PALETTE[0]),
        Category("trabajo", "Trabajo", PALETTE[7]),
        Category("salud", "Salud", PALETTE[2]),
        Category("casa", "Casa", PALETTE[3])
    )

    private fun load() {
        var cats: List<Category> = emptyList()
        var ts: List<Task> = emptyList()
        val f = file()
        if (f.exists()) {
            runCatching {
                val root = JSONObject(f.readText())
                val ca = root.getJSONArray("categories")
                cats = List(ca.length()) { categoryFromJson(ca.getJSONObject(it)) }
                val ta = root.getJSONArray("tasks")
                ts = List(ta.length()) { taskFromJson(ta.getJSONObject(it)) }
            }
        }
        if (cats.isEmpty()) cats = defaultCategories()
        _categories.value = cats
        _tasks.value = ts
    }

    private fun save() {
        val root = JSONObject()
            .put("categories", JSONArray(_categories.value.map { it.toJson() }))
            .put("tasks", JSONArray(_tasks.value.map { it.toJson() }))
        val f = file()
        val tmp = File(f.parentFile, "data.json.tmp")
        tmp.writeText(root.toString())
        if (!tmp.renameTo(f)) {
            f.writeText(root.toString())
            tmp.delete()
        }
    }

    private fun changed(task: Task?) {
        save()
        val c = ctx!!
        if (task != null) Scheduler.schedule(c, task)
        WidgetUpdater.update(c)
    }

    fun exportJson(): String = JSONObject()
        .put("app", "tareas")
        .put("version", 1)
        .put("categories", JSONArray(_categories.value.map { it.toJson() }))
        .put("tasks", JSONArray(_tasks.value.map { it.toJson() }))
        .toString(2)

    /** Devuelve (categorías, tareas) si el texto es una copia válida; null si no. */
    fun parseBackup(text: String): Pair<List<Category>, List<Task>>? = runCatching {
        val root = JSONObject(text)
        val ca = root.getJSONArray("categories")
        val ta = root.getJSONArray("tasks")
        val cats = List(ca.length()) { categoryFromJson(ca.getJSONObject(it)) }
        val ts = List(ta.length()) { taskFromJson(ta.getJSONObject(it)) }
        Pair(cats, ts)
    }.getOrNull()

    @Synchronized
    fun importBackup(cats: List<Category>, ts: List<Task>) {
        val c = ctx!!
        _tasks.value.forEach { Scheduler.cancel(c, it.id) }
        _categories.value = if (cats.isEmpty()) defaultCategories() else cats
        _tasks.value = ts
        save()
        ts.forEach { Scheduler.schedule(c, it) }
        WidgetUpdater.update(c)
    }

    fun category(id: String): Category? = _categories.value.firstOrNull { it.id == id }

    fun find(id: String): Task? = _tasks.value.firstOrNull { it.id == id }

    @Synchronized
    fun upsert(t: Task) {
        val list = _tasks.value
        _tasks.value = if (list.any { it.id == t.id }) list.map { if (it.id == t.id) t else it } else list + t
        changed(t)
    }

    @Synchronized
    fun delete(id: String) {
        _tasks.value = _tasks.value.filter { it.id != id }
        Scheduler.cancel(ctx!!, id)
        changed(null)
    }

    /** Marca como hecha; si es recurrente, pasa al siguiente vencimiento. */
    @Synchronized
    fun complete(id: String) {
        val t = find(id) ?: return
        if (t.repeat != Repeat.NONE && !t.done) {
            upsert(t.copy(due = Recur.next(t, System.currentTimeMillis())))
        } else {
            upsert(t.copy(done = true))
        }
    }

    @Synchronized
    fun reopen(id: String) {
        val t = find(id) ?: return
        upsert(t.copy(done = false))
    }

    @Synchronized
    fun snooze(id: String, s: Snooze) {
        val t = find(id) ?: return
        upsert(t.copy(due = Recur.snoozed(t, s, System.currentTimeMillis()), done = false))
    }

    @Synchronized
    fun upsertCategory(c: Category) {
        val list = _categories.value
        _categories.value = if (list.any { it.id == c.id }) list.map { if (it.id == c.id) c else it } else list + c
        changed(null)
    }

    @Synchronized
    fun deleteCategory(id: String) {
        val rest = _categories.value.filter { it.id != id }
        if (rest.isEmpty()) return
        _categories.value = rest
        _tasks.value = _tasks.value.map { if (it.categoryId == id) it.copy(categoryId = rest.first().id) else it }
        changed(null)
    }
}

object WidgetUpdater {
    fun update(context: Context) {
        val app = context.applicationContext
        CoroutineScope(Dispatchers.Default).launch {
            runCatching { TaskWidget().updateAll(app) }
        }
    }
}
