package com.luncher.home

import android.content.Context

/** Things you copied from chat windows (newest first), kept for the clipboard strip. */
object Clips {
    private const val KEY = "clip_history"
    private const val MAX = 12

    fun list(ctx: Context): MutableList<String> = runCatching {
        val arr = org.json.JSONArray(ctx.getSharedPreferences("luncher", Context.MODE_PRIVATE).getString(KEY, "[]"))
        (0 until arr.length()).map { arr.getString(it) }.toMutableList()
    }.getOrDefault(mutableListOf())

    private fun save(ctx: Context, l: List<String>) {
        val arr = org.json.JSONArray(); l.take(MAX).forEach { arr.put(it) }
        ctx.getSharedPreferences("luncher", Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }

    fun add(ctx: Context, text: String) {
        val t = text.trim(); if (t.isEmpty()) return
        val l = list(ctx); l.remove(t); l.add(0, t); save(ctx, l)
    }

    fun remove(ctx: Context, text: String) { val l = list(ctx); l.remove(text); save(ctx, l) }
}
