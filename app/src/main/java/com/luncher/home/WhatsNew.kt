package com.luncher.home

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * Every update's changes, shown once after the launcher updates ("What's New"), and any time
 * from Settings › General. Add the new version's notes at the top of [notes] with each release.
 */
object WhatsNew {
    /** Launcher number → what changed in it. */
    val notes: Map<Int, List<String>> = linkedMapOf(
        89 to listOf("Tools are back in the Dynamic Island: hidden around the front camera — tap it to open the tools island",
            "Camera added to the island tools; the separate floating tools bar is gone"),
        88 to listOf("Tools on the side can look like the Sidebar: a panel with labelled tiles (Settings › Island Tools › On the Side)",
            "The tools can't dock on the Sidebar's side"),
        87 to listOf("Only one tools bar: the island no longer has its own pull-down controls"),
        86 to listOf("Tools bar never covers anything: it steps aside for the Sidebar and the open side island, and slides below the open Dynamic Island"),
        85 to listOf("What's New: every update's changes show once after updating (and any time in Settings › General)",
            "The “hello” screen plays once after every update"),
        84 to listOf("📷 Camera in the tools bar"),
        83 to listOf("Tools circle opening animations: Ripple, Blossom, Spin or Fade",
            "Push any part of the tools bar past a side and it becomes the half circle on that edge"),
        82 to listOf("Edge half circle is always complete — even while you drag it",
            "Tools spread evenly around a centred ✕, smoother opening and docking"),
        81 to listOf("Drag the tools bar to a side: it blossoms into a half circle with ✕ in the middle"),
        80 to listOf("In the launcher's Settings the tools bar turns into a ✕ that closes Settings"),
        79 to listOf("Tools bar always on screen — Home Screen and over every app",
            "Arrow to fold it into a small tab and open it again"),
        78 to listOf("Move the tools bar anywhere (hold, then drag)",
            "Tools bar size, colour, opacity, shape and angle in Settings"),
        77 to listOf("Classic Settings design is back; ⚙ in the tools opens the launcher's Settings"),
        74 to listOf("Tools when nothing is playing: Flashlight, Brightness, Silent, Game Mode, Lock Screen, Settings"),
        73 to listOf("Now Playing widget: keep the last song, oblong shape (optional)"),
        72 to listOf("Smoother Dynamic Island animations", "Move the open Sidebar (hold, then drag)"),
        71 to listOf("Smoother Sidebar and side island opening and closing"),
        70 to listOf("Sidebar is only as tall as its apps", "All AI features removed"),
        68 to listOf("Smooth switching between apps in Notifications history"),
        67 to listOf("Notifications history: every message kept, latest 3 per chat, blue dots for new, filters per app, Clear"),
        66 to listOf("Connected headphones (with battery) and phone temperature in the side island"),
        65 to listOf("Island, Sidebar, side island and chat windows make room for each other"),
        64 to listOf("Resize chat and Message History windows with a corner grip"),
        63 to listOf("Choose how many recent apps show in the side island, and their size"),
        62 to listOf("Recent apps under the side island — scroll and tap to switch apps")
    )

    /** Notes for every version after [from] up to [to] (newest first). */
    fun between(from: Int, to: Int): List<Pair<Int, List<String>>> =
        notes.entries.filter { it.key in (from + 1)..to }.sortedByDescending { it.key }.map { it.key to it.value }

    /** The card: "What's New in Apple Launcher N" with each version's changes, and Continue. */
    fun show(act: Activity, root: FrameLayout, from: Int, to: Int, onDone: (() -> Unit)? = null) {
        fun dp(v: Int) = (v * act.resources.displayMetrics.density).roundToInt()
        val items = between(from, to).ifEmpty { between(to - 1, to) }.ifEmpty { listOf(to to listOf("Improvements and fixes")) }
        val layer = FrameLayout(act).apply {
            setBackgroundColor(Color.argb(150, 0, 0, 0)); isClickable = true
        }
        val card = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(24), dp(22), dp(18))
            background = GradientDrawable().apply {
                cornerRadius = dp(30).toFloat(); setColor(0xF21C1C1E.toInt()); setStroke(maxOf(1, dp(1)), 0x33FFFFFF)
            }
            elevation = dp(16).toFloat()
        }
        card.addView(TextView(act).apply {
            text = "What's New"; textSize = 28f; setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        })
        card.addView(TextView(act).apply {
            text = "Apple Launcher $to"; textSize = 14f; setTextColor(0x99FFFFFF.toInt()); setPadding(0, dp(2), 0, dp(14))
        })
        val list = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        for ((ver, lines) in items) {
            list.addView(TextView(act).apply {
                text = "VERSION $ver"; textSize = 12f; setTextColor(ThemeEngine.accent.takeIf { ThemeEngine.colorful } ?: 0xFF0A84FF.toInt())
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD); letterSpacing = 0.08f
                setPadding(0, dp(10), 0, dp(6))
            })
            for (line in lines) list.addView(TextView(act).apply {
                text = "•  $line"; textSize = 15f; setTextColor(0xE6FFFFFF.toInt()); setLineSpacing(0f, 1.15f)
                setPadding(0, dp(3), 0, dp(3))
            })
        }
        val scroll = object : ScrollView(act) {
            override fun onMeasure(w: Int, h: Int) = super.onMeasure(w,
                View.MeasureSpec.makeMeasureSpec((act.resources.displayMetrics.heightPixels * 0.5f).toInt(), View.MeasureSpec.AT_MOST))
        }.apply { isVerticalScrollBarEnabled = false; addView(list) }
        card.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        lateinit var close: () -> Unit
        card.addView(TextView(act).apply {
            text = "Continue"; textSize = 17f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(0xFF0A84FF.toInt()) }
            setOnClickListener { close() }
        }, LinearLayout.LayoutParams(MATCH_PARENT, dp(52)).apply { topMargin = dp(18) })
        layer.addView(card, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.CENTER).apply {
            leftMargin = dp(18); rightMargin = dp(18)
        })
        close = {
            card.animate().alpha(0f).scaleX(0.92f).scaleY(0.92f).setDuration(200).start()
            layer.animate().alpha(0f).setDuration(240).withEndAction { root.removeView(layer); onDone?.invoke() }.start()
        }
        root.addView(layer, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        layer.alpha = 0f; layer.animate().alpha(1f).setDuration(260).start()
        card.scaleX = 0.88f; card.scaleY = 0.88f; card.alpha = 0f; card.translationY = dp(30).toFloat()
        card.animate().alpha(1f).translationY(0f).setDuration(280).start()
        Motion.scaleTo(card, 1f, Motion.BOUNCY)
    }

    /** This build's launcher number (e.g. 85). */
    fun current(act: Activity): Int = runCatching {
        act.packageManager.getPackageInfo(act.packageName, 0).versionName?.toInt()
    }.getOrNull() ?: 0
}
