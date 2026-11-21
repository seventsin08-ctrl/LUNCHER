package com.luncher.home

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Gilbert's brain room (🧠 in his chat): his face inside a glowing calibration ring, what his
 * brain is, his memory and archive — and the button to give him a (new) brain, which plays a
 * calibration sequence while it loads.
 */
class GilbertBrainRoom(private val act: Activity, private val s: Settings, private val onPick: () -> Unit) {
    private fun dp(v: Int) = (v * act.resources.displayMetrics.density).roundToInt()
    private val tint = if (ThemeEngine.colorful) ThemeEngine.accent else 0xFF8E8E93.toInt()
    val view = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(0, dp(6), 0, dp(4)) }
    private val ring = Ring()
    private val face = IslandFace(act).apply { configure(s) }
    private val status = TextView(act).apply { textSize = 15f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER }
    private val detail = TextView(act).apply { textSize = 12.5f; setTextColor(0x99FFFFFF.toInt()); gravity = Gravity.CENTER }
    private val steps = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(4)) }
    private val pickBtn = pill("", 0xFF0A84FF.toInt()) { onPick() }

    init {
        val stage = FrameLayout(act)
        stage.addView(ring, FrameLayout.LayoutParams(dp(190), dp(190), Gravity.CENTER))
        stage.addView(FrameLayout(act).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.BLACK); setStroke(dp(2), ThemeEngine.alpha(tint, 160)) }
            addView(face, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }, FrameLayout.LayoutParams(dp(118), dp(118), Gravity.CENTER))
        view.addView(stage, LinearLayout.LayoutParams(MATCH_PARENT, dp(200)))
        view.addView(status)
        view.addView(detail, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(2) })
        view.addView(steps, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, dp(8), 0, 0) }
        row.addView(pickBtn)
        view.addView(row)
        refresh()
    }

    private fun pill(t: String, c: Int, fn: () -> Unit) = TextView(act).apply {
        text = t; textSize = 14f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        setPadding(dp(18), dp(10), dp(18), dp(10))
        background = GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(c) }
        setOnClickListener { SoundFX.play(act, "tick"); fn() }
    }

    /** Show what he's running on right now. */
    fun refresh() {
        val has = GilbertBrain.installed(act)
        val name = GilbertBrain.modelName(act)
        status.text = when {
            has && GilbertBrain.crashed(act) -> "Brain switched off ⚠️"
            has && GilbertBrain.loaded -> "Brain online"
            has -> "Brain installed"
            else -> "No brain yet — memory mode"
        }
        detail.text = buildString {
            if (has) append("${name ?: "Brain"} · ${GilbertBrain.sizeMb(act)} MB\n")
            append("Memory: ${GilbertMemory.facts(act).size} facts · Archive: ${GilbertArchive.count(act)} messages (${GilbertArchive.days(act).size} days)")
        }
        pickBtn.text = if (has) "Change Brain" else "Give Gilbert a Brain"
        ring.level = if (has && GilbertBrain.loaded) 1f else if (has) 0.6f else 0.25f
    }

    /** The calibration sequence: ring sweeps, steps tick off, he loads, then wakes up happy. */
    fun calibrate() {
        steps.removeAllViews()
        face.visibility = View.INVISIBLE; face.visibility = View.VISIBLE   // restart: sphere → face
        face.react("thinking", 60_000)
        ring.spin = true
        status.text = "Calibrating…"
        val list = listOf(
            "Waking up neurons",
            "Reading memory (${GilbertMemory.facts(act).size} facts)",
            "Indexing archive (${GilbertArchive.count(act)} messages)",
            "Loading brain (${GilbertBrain.sizeMb(act)} MB)",
            "Tuning voice & personality")
        val rows = list.map { t ->
            TextView(act).apply { text = "○  $t"; textSize = 13.5f; setTextColor(0x80FFFFFF.toInt()); setPadding(0, dp(3), 0, dp(3)) }
                .also { steps.addView(it); it.alpha = 0f }
        }
        GilbertBrain.warmUp(act)
        var i = 0
        fun next() {
            if (i >= rows.size) {
                ring.spin = false; ring.level = 1f
                face.react("excited", 2500)
                status.text = if (GilbertBrain.loaded) "Brain online ✨" else "Ready (memory mode)"
                SoundFX.play(act, "success")
                view.postDelayed({ refresh() }, 1500)
                return
            }
            val r = rows[i]
            r.animate().alpha(1f).setDuration(180).start()
            r.setTextColor(Color.WHITE); r.text = "◌  ${list[i]}…"
            SoundFX.play(act, "tick")
            ring.level = (i + 1f) / (rows.size + 1)
            val wait = if (i == 3) 0L else 520L
            val done = {
                r.text = "✓  ${list[i]}"; r.setTextColor(IconFactory.blend(0xFF30D158.toInt(), Color.WHITE, 0.2f))
                i++; next()
            }
            if (i == 3) {
                // Wait for the real brain to finish loading (up to ~60 s).
                var waited = 0
                val poll = object : Runnable { override fun run() {
                    if (GilbertBrain.loaded || !GilbertBrain.installed(act) || GilbertBrain.lastError != null || waited > 60_000) done()
                    else { waited += 300; view.postDelayed(this, 300) }
                } }
                view.postDelayed(poll, 400)
            } else view.postDelayed({ done() }, wait)
        }
        view.postDelayed({ next() }, 300)
    }

    /** Glowing ring: a progress arc plus little orbiting sparks while calibrating. */
    private inner class Ring : View(act) {
        var level = 0.25f; set(v) { field = v; invalidate() }
        var spin = false; set(v) { field = v; invalidate() }
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(4).toFloat(); color = 0x22FFFFFF }
        private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(4).toFloat(); strokeCap = Paint.Cap.ROUND; color = tint }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = tint }
        private val r = RectF()
        private var shown = 0f
        override fun onDraw(c: Canvas) {
            val pad = dp(8).toFloat()
            r.set(pad, pad, width - pad, height - pad)
            c.drawOval(r, track)
            shown += (level - shown) * 0.12f
            val t = System.nanoTime() / 1e9
            val start = if (spin) ((t * 140) % 360).toFloat() else -90f
            c.drawArc(r, start, 360f * shown, false, arc)
            if (spin) for (k in 0 until 6) {
                val a = t * (1.6 + k * 0.2) + k
                val rad = width / 2f - pad
                dot.alpha = (120 + 120 * sin(t * 3 + k)).toInt().coerceIn(40, 255)
                c.drawCircle(width / 2f + (rad * cos(a)).toFloat(), height / 2f + (rad * sin(a)).toFloat(), dp(3).toFloat(), dot)
            }
            if (spin || kotlin.math.abs(level - shown) > 0.002f) postInvalidateOnAnimation()
        }
    }
}
