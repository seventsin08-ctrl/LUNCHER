package com.luncher.home

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Gilbert's task list (from his desktop brain): add, list, complete — numbered, kept on this phone. */
object GilbertTasks {
    class Task(val id: Int, val title: String, val done: Boolean, val time: Long)
    private fun p(ctx: Context) = ctx.getSharedPreferences("gil_tasks", Context.MODE_PRIVATE)

    fun all(ctx: Context): List<Task> = runCatching {
        val a = JSONArray(p(ctx).getString("tasks", "[]"))
        (0 until a.length()).map { a.getJSONObject(it).let { o -> Task(o.optInt("id"), o.optString("t"), o.optBoolean("d"), o.optLong("w")) } }
    }.getOrDefault(emptyList())
    fun open(ctx: Context) = all(ctx).filter { !it.done }

    private fun save(ctx: Context, list: List<Task>) {
        val a = JSONArray()
        list.takeLast(200).forEach { a.put(JSONObject().put("id", it.id).put("t", it.title).put("d", it.done).put("w", it.time)) }
        p(ctx).edit().putString("tasks", a.toString()).apply()
    }

    fun add(ctx: Context, title: String): Int {
        val id = p(ctx).getInt("next", 1)
        p(ctx).edit().putInt("next", id + 1).apply()
        save(ctx, all(ctx) + Task(id, title.trim().replaceFirstChar { it.uppercase() }, false, System.currentTimeMillis()))
        return id
    }

    fun complete(ctx: Context, id: Int): Boolean {
        val list = all(ctx)
        if (list.none { it.id == id && !it.done }) return false
        save(ctx, list.map { if (it.id == id) Task(it.id, it.title, true, it.time) else it })
        return true
    }

    fun remove(ctx: Context, id: Int) = save(ctx, all(ctx).filter { it.id != id })
}
