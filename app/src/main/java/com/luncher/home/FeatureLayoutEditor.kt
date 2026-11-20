package com.luncher.home

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * "Adjust the Features": a small live phone with your wallpaper where you drag the Dynamic
 * Island, the Sidebar handle, the Side Island tab and the Game Ball to where you want them.
 * Side items snap to the nearest edge, like on iPhone. Saved as you let go.
 */
object FeatureLayoutEditor {
    fun show(act: Activity, root: FrameLayout, s: Settings, onDone: () -> Unit) {
        val dm = act.resources.displayMetrics
        fun dp(v: Float) = (v * dm.density).roundToInt()
        val W = dm.widthPixels.toFloat(); val H = dm.heightPixels.toFloat()

        val layer = FrameLayout(act).apply { setBackgroundColor(0xF2000000.toInt()); isClickable = true }
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        // Header
        val head = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(20f), dp(44f), dp(20f), dp(8f)) }
        head.addView(TextView(act).apply { text = "Adjust the Features"; textSize = 22f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD },
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        lateinit var close: () -> Unit
        val doneBtn = TextView(act).apply {
            text = "Done"; textSize = 15f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(16f), dp(8f), dp(16f), dp(8f))
            background = GradientDrawable().apply { cornerRadius = dp(18f).toFloat(); setColor(0xFF0A84FF.toInt()) }
            setOnClickListener { close() }
        }
        head.addView(doneBtn)
        col.addView(head, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        col.addView(TextView(act).apply {
            text = "Drag each part (Island, Tools button, Sidebar, Side Island, Game Ball). Side parts snap to the nearest edge."; textSize = 13f; setTextColor(0x99FFFFFF.toInt())
            gravity = Gravity.CENTER; setPadding(dp(20f), 0, dp(20f), dp(12f))
        })

        // The phone
        val f = (W * 0.62f) / W
        val pw = (W * f).toInt(); val ph = (H * f).toInt()
        val phone = FrameLayout(act).apply {
            clipChildren = true
            background = GradientDrawable().apply { cornerRadius = dp(30f).toFloat(); setColor(0xFF101012.toInt()); setStroke(dp(4f), 0xFF3A3A3C.toInt()) }
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) = o.setRoundRect(0, 0, v.width, v.height, dp(30f).toFloat())
            }
        }
        // Wallpaper behind (if the phone lets us read it).
        runCatching { android.app.WallpaperManager.getInstance(act).drawable }.getOrNull()?.let { d ->
            phone.addView(ImageView(act).apply { setImageDrawable(d); scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0.75f },
                FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
        // A faint grid of app icons so you can see what the parts sit over.
        val grid = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10f), (ph * 0.12f).toInt(), dp(10f), 0) }
        repeat(5) {
            grid.addView(LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                repeat(s.columns) { addView(View(act).apply {
                    background = GradientDrawable().apply { cornerRadius = dp(6f).toFloat(); setColor(0x33FFFFFF) }
                }, LinearLayout.LayoutParams(0, (pw / s.columns * 0.55f).toInt(), 1f).apply { setMargins(dp(5f), dp(6f), dp(5f), dp(6f)) }) }
            })
        }
        phone.addView(grid, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        fun chip(label: String, w: Int, h: Int, shape: GradientDrawable): FrameLayout {
            val v = FrameLayout(act).apply { background = shape; elevation = dp(4f).toFloat() }
            v.addView(TextView(act).apply { text = label; textSize = 8f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD },
                FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            phone.addView(v, FrameLayout.LayoutParams(w, h))
            return v
        }
        fun pill(c: Int, r: Float) = GradientDrawable().apply { cornerRadius = r; setColor(c); setStroke(dp(1f), 0x66FFFFFF) }

        /** Make a part draggable. [place] puts it from settings; [save] stores the new spot (x,y in real-screen px). */
        fun draggable(v: View, place: () -> Unit, save: (Float, Float) -> Unit) {
            var dx = 0f; var dy = 0f
            v.setOnTouchListener { view, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { dx = e.rawX - view.x; dy = e.rawY - view.y
                        view.animate().scaleX(1.12f).scaleY(1.12f).setDuration(120).start(); view.parent.requestDisallowInterceptTouchEvent(true) }
                    MotionEvent.ACTION_MOVE -> {
                        view.x = (e.rawX - dx).coerceIn(0f, (pw - view.width).toFloat())
                        view.y = (e.rawY - dy).coerceIn(0f, (ph - view.height).toFloat())
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        save((view.x + view.width / 2f) / f, (view.y + view.height / 2f) / f)
                        Motion.scaleTo(view, 1f, Motion.BOUNCY)
                        SoundFX.play(act, "tick")
                        place()
                    }
                }
                true
            }
        }

        // 1) Dynamic Island / Gilbert
        val iw = (dp(120f) * f).toInt().coerceAtLeast(dp(46f)); val ih = (dp(34f) * f).toInt().coerceAtLeast(dp(18f))
        val island = chip("Island", iw, ih, pill(Color.BLACK, ih / 2f))
        val placeIsland = {
            island.animate().x((W / 2f + dp(s.islandX.toFloat())) * f - iw / 2f)
                .y((dp(28f) + dp(s.islandNudge.toFloat())) * f).setDuration(220).start(); Unit
        }
        draggable(island, placeIsland) { x, y ->
            s.islandX = ((x - W / 2f) / dm.density).roundToInt().coerceIn(-160, 160)
            s.islandNudge = ((y - dp(28f) - ih / f / 2f) / dm.density).roundToInt().coerceIn(-20, 400)
        }

        // 2) Sidebar handle
        val sw = dp(9f); val sh = (dp(90f) * f).toInt()
        val side = chip("", sw, sh, pill(0xCCFFFFFF.toInt(), sw / 2f))
        val sideLabel = TextView(act).apply { text = "Sidebar"; textSize = 9f; setTextColor(Color.WHITE) }
        phone.addView(sideLabel, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        val placeSide = {
            val x = if (s.sidebarRight) pw - sw - dp(2f) else dp(2f)
            val y = dp(s.sidebarY.toFloat()) * f
            side.animate().x(x.toFloat()).y(y).setDuration(220).start()
            sideLabel.animate().x(if (s.sidebarRight) x - dp(42f).toFloat() else x + sw + dp(4f).toFloat()).y(y + sh / 2f - dp(7f)).setDuration(220).start(); Unit
        }
        draggable(side, placeSide) { x, y ->
            s.sidebarRight = x > W / 2f
            s.sidebarY = ((y - sh / f / 2f) / dm.density).roundToInt().coerceIn(40, (H / dm.density).toInt() - 120)
        }

        // 3) Side Island tab (left by default)
        val tw = dp(14f); val th = (dp(70f) * f).toInt().coerceAtLeast(dp(28f))
        val tab = chip("", tw, th, pill(Color.BLACK, tw / 2f))
        val tabLabel = TextView(act).apply { text = "Side Island"; textSize = 9f; setTextColor(Color.WHITE) }
        phone.addView(tabLabel, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        val placeTab = {
            val x = if (s.sideRight) pw - tw else 0
            val y = ph * s.sideY / 100f
            tab.animate().x(x.toFloat()).y(y).setDuration(220).start()
            tabLabel.animate().x(if (s.sideRight) x - dp(56f).toFloat() else x + tw + dp(4f).toFloat()).y(y + th / 2f - dp(7f)).setDuration(220).start(); Unit
        }
        draggable(tab, placeTab) { x, y ->
            s.sideRight = x > W / 2f
            s.sideY = ((y - th / f / 2f) / H * 100).roundToInt().coerceIn(5, 90)
        }

        // 4) Game Ball
        val bs = (dp(52f) * f).toInt().coerceAtLeast(dp(26f))
        val ball = chip("🎮", bs, bs, GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.BLACK); setStroke(dp(1f), 0x88FFFFFF.toInt()) })
        val placeBall = {
            ball.animate().x(if (s.gameBallRight) (pw - bs - dp(3f)).toFloat() else dp(3f).toFloat())
                .y(ph * s.gameBallY).setDuration(220).start(); Unit
        }
        draggable(ball, placeBall) { x, y -> s.gameBallRight = x > W / 2f; s.gameBallY = ((y - bs / f / 2f) / H).coerceIn(0.03f, 0.9f) }

        // 5) Tools button (the ring by the camera)
        val tsz = dp(22f)
        val tools = chip("◉", tsz, tsz, GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x33000000); setStroke(dp(2f), Color.WHITE) })
        val toolsLabel = TextView(act).apply { text = "Tools"; textSize = 9f; setTextColor(Color.WHITE) }
        phone.addView(toolsLabel, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        val placeTools = {
            val x = (W / 2f + dp(s.hotspotDx.toFloat())) * f - tsz / 2f
            val y = (dp(14f) + dp(s.hotspotDy.toFloat())) * f
            tools.animate().x(x).y(y.coerceAtLeast(0f)).setDuration(220).start()
            toolsLabel.animate().x(x + tsz + dp(3f)).y(y.coerceAtLeast(0f) + dp(3f)).setDuration(220).start(); Unit
        }
        draggable(tools, placeTools) { x, y ->
            s.hotspotDx = ((x - W / 2f) / dm.density).roundToInt().coerceIn(-150, 150)
            s.hotspotDy = ((y - dp(14f) - tsz / f / 2f) / dm.density).roundToInt().coerceIn(-30, 80)
        }

        col.addView(phone, LinearLayout.LayoutParams(pw, ph))
        val reset = TextView(act).apply {
            text = "Reset Positions"; textSize = 14f; setTextColor(0xFFFF453A.toInt()); setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
            setOnClickListener {
                s.islandX = 0; s.islandNudge = 0; s.sidebarRight = true; s.sidebarY = 260; s.sideRight = false; s.sideY = 30
                s.gameBallRight = true; s.gameBallY = 0.3f; s.hotspotDx = -12; s.hotspotDy = -10
                placeIsland(); placeSide(); placeTab(); placeBall(); placeTools(); SoundFX.play(act, "pop")
            }
        }
        col.addView(reset)
        layer.addView(android.widget.ScrollView(act).apply { addView(col) }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(layer, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        phone.post { placeIsland(); placeSide(); placeTab(); placeBall(); placeTools() }

        close = {
            SoundFX.play(act, "close")
            s.islandX = s.islandX   // bump the settings version so everything re-places itself
            SidebarService.sync(act)
            layer.animate().alpha(0f).setDuration(200).withEndAction { root.removeView(layer); onDone() }.start()
        }
        SoundFX.play(act, "open")
        layer.alpha = 0f; layer.animate().alpha(1f).setDuration(200).start()
        phone.scaleX = 0.85f; phone.scaleY = 0.85f; Motion.scaleTo(phone, 1f, Motion.FLUID)
    }
}
