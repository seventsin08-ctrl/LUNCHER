package com.luncher.home

import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.dynamicanimation.animation.DynamicAnimation
import kotlin.math.roundToInt

/**
 * A live copy of the Dynamic Island shown at the top of Settings while you edit it:
 * it moves, resizes, restyles and replays the pop-up animation as you change things.
 * It never takes touches, so the settings underneath still work.
 */
class IslandPreview(ctx: Context) : FrameLayout(ctx) {
    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    // Live values (sliders update these while you drag, before they're saved).
    var placement = "below"
    var nudge = 0
    var x = 0
    var scale = 100
    var style = "black"
    var anim = "spring"
    var statusTop = 0
    var cutTop = 0

    private var shown = false
    private val pill = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), 0, dp(12), 0)
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) =
                outline.setRoundRect(0, 0, view.width, view.height, view.height / 2f)
        }
        clipToOutline = true
        elevation = dp(6).toFloat()
    }

    init {
        isClickable = false
        isFocusable = false
        clipChildren = false
        val art = View(ctx).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFFB98CFF.toInt(), 0xFF6D7BFF.toInt()))
                .apply { cornerRadius = dp(6).toFloat() }
        }
        pill.addView(art, LinearLayout.LayoutParams(dp(22), dp(22)))
        pill.addView(TextView(ctx).apply {
            text = "Preview"; setTextColor(Color.WHITE); textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8) })
        pill.addView(BarsView(ctx).apply { color = 0xFFFF9F0A.toInt() }, LinearLayout.LayoutParams(dp(22), dp(16)))
        addView(pill, LayoutParams(dp(170), dp(36), Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        pill.visibility = View.INVISIBLE
        pill.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> v.pivotX = v.width / 2f; v.pivotY = 0f }
    }

    fun load(s: Settings) {
        placement = s.islandPlacement; nudge = s.islandNudge; x = s.islandX
        scale = s.islandScale; style = s.islandStyle; anim = s.islandAnim
    }

    /** Move / resize / restyle to match the current values. */
    fun refresh(s: Settings) {
        ThemeEngine.resolve(context, s)
        pill.background = GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            when (style) {
                "glass" -> { setColor(Color.argb(175, 18, 18, 20)); setStroke(maxOf(1, dp(1)), Color.argb(80, 255, 255, 255)) }
                "outline" -> { setColor(Color.BLACK); setStroke(maxOf(2, dp(2)), ThemeEngine.alpha(ThemeEngine.accent, 220)) }
                "color" -> setColor(IconFactory.blend(Color.BLACK, ThemeEngine.accent, 0.38f))
                else -> setColor(Color.BLACK)
            }
        }
        val band = if (cutTop > 0) cutTop else statusTop
        val base = if (placement == "statusbar") ((band - dp(36)) / 2).coerceAtLeast(dp(3)) else statusTop + dp(4)
        val lp = pill.layoutParams as LayoutParams
        lp.topMargin = (base + dp(nudge)).coerceAtLeast(dp(2))
        pill.layoutParams = lp
        if (shown) {
            pill.translationX = dp(x).toFloat()
            pill.scaleX = scale / 100f; pill.scaleY = scale / 100f
        }
    }

    fun show(s: Settings) {
        refresh(s)
        if (shown) return
        shown = true
        pill.visibility = View.VISIBLE
        playIn()
    }

    fun hide() {
        if (!shown) return
        shown = false
        playOut { pill.visibility = View.INVISIBLE }
    }

    /** Replay the chosen pop-up animation (out, then back in). */
    fun replay(s: Settings) {
        refresh(s)
        if (!shown) { show(s); return }
        playOut { postDelayed({ if (shown) playIn() }, 180) }
    }

    private fun playIn() {
        val sc = scale / 100f
        pill.animate().cancel()
        pill.translationX = dp(x).toFloat()
        pill.translationY = 0f
        pill.rotationX = 0f
        pill.alpha = 0f
        when (anim) {
            "fade" -> { pill.scaleX = sc; pill.scaleY = sc; pill.animate().alpha(1f).setDuration((320 * Motion.scale).toLong()).start() }
            "slide" -> {
                pill.scaleX = sc; pill.scaleY = sc
                Motion.spring(pill, DynamicAnimation.TRANSLATION_Y, 0f, Motion.BOUNCY, start = -dp(60).toFloat())
                Motion.alphaTo(pill, 1f, Motion.SNAPPY)
            }
            "3d" -> {
                pill.cameraDistance = 9000f * resources.displayMetrics.density
                pill.scaleX = sc * 0.9f; pill.scaleY = sc * 0.9f
                pill.rotationX = -85f
                pill.animate().rotationX(0f).scaleX(sc).scaleY(sc).alpha(1f)
                    .setInterpolator(android.view.animation.OvershootInterpolator(1.4f))
                    .setDuration((520 * Motion.scale).toLong()).start()
            }
            "zoom" -> {
                pill.scaleX = 0f; pill.scaleY = 0f
                pill.animate().scaleX(sc).scaleY(sc).alpha(1f).setInterpolator(DecelerateInterpolator(2f))
                    .setDuration((280 * Motion.scale).toLong()).start()
            }
            else -> {
                pill.scaleX = 0.5f * sc; pill.scaleY = 0.5f * sc
                Motion.scaleTo(pill, sc, Motion.BOUNCY)
                Motion.alphaTo(pill, 1f, Motion.SNAPPY)
            }
        }
    }

    private fun playOut(end: () -> Unit) {
        val sc = scale / 100f
        val a = pill.animate().alpha(0f).setDuration((200 * Motion.scale).toLong())
        when (anim) {
            "fade" -> {}
            "slide" -> a.translationY(-dp(60).toFloat())
            "3d" -> a.rotationX(-85f).scaleX(0.85f * sc).scaleY(0.85f * sc)
            else -> a.scaleX(0.6f * sc).scaleY(0.6f * sc)
        }
        a.withEndAction(end).start()
    }
}
