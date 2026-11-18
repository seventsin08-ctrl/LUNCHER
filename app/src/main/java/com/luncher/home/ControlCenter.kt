package com.luncher.home

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.MediaStore
import android.provider.Settings
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.dynamicanimation.animation.DynamicAnimation
import kotlin.math.roundToInt

/**
 * iPhone-style Control Center: glass tiles over a blurred Home Screen.
 *
 * Android doesn't let a launcher flip Wi-Fi, Bluetooth, mobile data or airplane mode by
 * itself, so those tiles open Android's own quick panels for that setting (one tap to
 * switch). Flashlight, brightness, volume, music, timer and Focus work right here.
 */
class ControlCenter(
    private val act: Activity,
    private val root: FrameLayout,
    private val music: () -> Triple<String, String, Bitmap?>?,
    private val musicPlaying: () -> Boolean,
    private val playPause: () -> Unit,
    private val next: () -> Unit,
    private val prev: () -> Unit,
    private val focusLabel: () -> String,
    private val onFocus: () -> Unit,
    private val onTimer: () -> Unit,
    private val onLock: () -> Unit,
    private val onBlur: (Boolean) -> Unit,
    private val haptics: () -> Boolean
) {
    private var layer: FrameLayout? = null
    private var torchOn = false
    val isShowing get() = layer != null

    private fun dp(v: Int) = (v * act.resources.displayMetrics.density).roundToInt()

    private fun tick(v: View) {
        if (haptics()) v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
    }

    private fun glass(radius: Int, active: Int? = null) = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        if (active != null) intArrayOf(active, active)
        else intArrayOf(Color.argb((70 * Glass.opacity).toInt().coerceIn(30, 200), 255, 255, 255),
            Color.argb((28 * Glass.opacity).toInt().coerceIn(12, 160), 255, 255, 255))
    ).apply {
        cornerRadius = dp(radius).toFloat()
        setStroke(maxOf(1, dp(1)), Color.argb(70, 255, 255, 255))
    }

    private fun circle(color: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }

    fun show(topInset: Int, bottomInset: Int) {
        if (layer != null) return
        val l = FrameLayout(act).apply {
            setBackgroundColor(Color.argb(110, 0, 0, 0))
            alpha = 0f
            isClickable = true
            setOnClickListener { hide() }
        }
        val panel = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), topInset + dp(46), dp(18), dp(18))
            isClickable = true
        }

        // Row 1: connectivity cluster + music.
        val row1 = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        val conn = GridLayout(act).apply {
            columnCount = 2
            background = glass(26)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        val airplane = Settings.Global.getInt(act.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
        val wifi = runCatching { act.applicationContext.getSystemService(WifiManager::class.java).isWifiEnabled }.getOrDefault(false)
        val bt = runCatching {
            act.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter?.isEnabled == true
        }.getOrDefault(false)
        conn.addView(roundButton("✈", if (airplane) 0xFFFF9F0A.toInt() else null) {
            openPanel(Settings.ACTION_AIRPLANE_MODE_SETTINGS, null)
        })
        conn.addView(roundButton("📶", if (wifi) 0xFF0A84FF.toInt() else null) {
            openPanel(Settings.ACTION_WIFI_SETTINGS, if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_WIFI else null)
        })
        conn.addView(roundButton("ᛒ", if (bt) 0xFF0A84FF.toInt() else null) {
            openPanel(Settings.ACTION_BLUETOOTH_SETTINGS, null)
        })
        conn.addView(roundButton("⇅", 0xFF30D158.toInt()) {
            openPanel(Settings.ACTION_DATA_ROAMING_SETTINGS,
                if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_INTERNET_CONNECTIVITY else null)
        })
        row1.addView(conn, LinearLayout.LayoutParams(0, dp(164), 1f).apply { marginEnd = dp(7) })

        val musicTile = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = glass(26)
            setPadding(dp(14), dp(14), dp(14), dp(12))
        }
        val info = music()
        val head = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val art = ImageView(act).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) =
                    outline.setRoundRect(0, 0, view.width, view.height, dp(9).toFloat())
            }
            clipToOutline = true
            if (info?.third != null) setImageBitmap(info.third)
            else { setImageResource(R.drawable.ic_music); setColorFilter(Color.WHITE); background = glass(9) }
        }
        head.addView(art, LinearLayout.LayoutParams(dp(42), dp(42)))
        head.addView(TextView(act).apply {
            text = if (info == null) "Not Playing" else "${info.first}\n${info.second}"
            setTextColor(Color.WHITE); textSize = 12f; maxLines = 3
            setPadding(dp(8), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        musicTile.addView(head)
        musicTile.addView(View(act), LinearLayout.LayoutParams(0, 0, 1f))
        val controls = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val playBtn = iconText(if (musicPlaying()) "⏸" else "▶", 24f)
        controls.addView(iconText("⏮", 20f).apply { setOnClickListener { tick(it); prev() } }, LinearLayout.LayoutParams(0, dp(36), 1f))
        controls.addView(playBtn.apply {
            setOnClickListener {
                tick(it); playPause()
                postDelayed({ text = if (musicPlaying()) "⏸" else "▶" }, 250)
            }
        }, LinearLayout.LayoutParams(0, dp(36), 1f))
        controls.addView(iconText("⏭", 20f).apply { setOnClickListener { tick(it); next() } }, LinearLayout.LayoutParams(0, dp(36), 1f))
        musicTile.addView(controls)
        row1.addView(musicTile, LinearLayout.LayoutParams(0, dp(164), 1f).apply { marginStart = dp(7) })
        panel.addView(row1)

        // Row 2: small buttons on the left, brightness + volume sliders on the right.
        val row2 = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        val leftCol = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        val smalls = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        smalls.addView(squareTile("🔒") { hide(); onLock() }, LinearLayout.LayoutParams(0, dp(72), 1f).apply { marginEnd = dp(7) })
        smalls.addView(squareTile("📷") {
            hide()
            runCatching { act.startActivity(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }, LinearLayout.LayoutParams(0, dp(72), 1f).apply { marginStart = dp(7) })
        leftCol.addView(smalls)
        val focus = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = glass(22)
            setPadding(dp(12), 0, dp(12), 0)
            setOnClickListener { tick(it); hide(); onFocus() }
        }
        focus.addView(TextView(act).apply {
            text = "🌙"; gravity = Gravity.CENTER; background = circle(0xFF5E5CE6.toInt()); textSize = 16f
        }, LinearLayout.LayoutParams(dp(38), dp(38)))
        focus.addView(TextView(act).apply {
            text = "Focus\n${focusLabel()}"; setTextColor(Color.WHITE); textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD); setPadding(dp(10), 0, 0, 0)
        })
        leftCol.addView(focus, LinearLayout.LayoutParams(MATCH_PARENT, dp(72)).apply { topMargin = dp(14) })
        row2.addView(leftCol, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = dp(7) })

        val sliders = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        sliders.addView(brightnessSlider(), LinearLayout.LayoutParams(0, dp(158), 1f).apply { marginEnd = dp(7) })
        sliders.addView(volumeSlider(), LinearLayout.LayoutParams(0, dp(158), 1f).apply { marginStart = dp(7) })
        row2.addView(sliders, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = dp(7) })
        panel.addView(row2, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(14) })

        // Row 3: flashlight, timer, calculator, voice recorder.
        val row3 = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        lateinit var torchTile: TextView
        torchTile = squareTile("🔦", if (torchOn) Color.WHITE else null) {
            if (setTorch(!torchOn)) torchTile.background = if (torchOn) glass(22, Color.WHITE) else glass(22)
        }
        row3.addView(torchTile, LinearLayout.LayoutParams(0, dp(72), 1f).apply { marginEnd = dp(7) })
        row3.addView(squareTile("⏱") { hide(); onTimer() }, LinearLayout.LayoutParams(0, dp(72), 1f).apply { setMargins(dp(7), 0, dp(7), 0) })
        row3.addView(squareTile("🧮") {
            hide()
            runCatching {
                act.startActivity(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALCULATOR)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.onFailure { toast("No calculator app found") }
        }, LinearLayout.LayoutParams(0, dp(72), 1f).apply { setMargins(dp(7), 0, dp(7), 0) })
        row3.addView(squareTile("🎙") {
            hide()
            runCatching {
                act.startActivity(Intent(MediaStore.Audio.Media.RECORD_SOUND_ACTION).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.onFailure { toast("No voice recorder app found") }
        }, LinearLayout.LayoutParams(0, dp(72), 1f).apply { marginStart = dp(7) })
        panel.addView(row3, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(14) })

        l.addView(panel, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP))
        root.addView(l, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        layer = l
        onBlur(true)
        if (haptics()) root.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        l.animate().alpha(1f).setDuration((220 * Motion.scale).toLong()).start()
        // Tiles drop in from the top with a spring, a touch staggered.
        for (i in 0 until panel.childCount) {
            val c = panel.getChildAt(i)
            c.alpha = 0f
            c.postDelayed({
                Motion.spring(c, DynamicAnimation.TRANSLATION_Y, 0f, Motion.BOUNCY, start = -dp(60).toFloat())
                Motion.alphaTo(c, 1f, Motion.SNAPPY)
            }, (i * 35L * Motion.scale).toLong())
        }
    }

    fun hide() {
        val l = layer ?: return
        layer = null
        onBlur(false)
        l.animate().alpha(0f).translationY(-dp(30).toFloat()).setDuration((200 * Motion.scale).toLong())
            .withEndAction { root.removeView(l) }.start()
    }

    private fun toast(s: String) = Toast.makeText(act, s, Toast.LENGTH_SHORT).show()

    private fun openPanel(fallback: String, panel: String?) {
        hide()
        val ok = panel != null && runCatching {
            act.startActivity(Intent(panel).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
        if (!ok) runCatching { act.startActivity(Intent(fallback).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    private fun iconText(t: String, size: Float) = TextView(act).apply {
        text = t; setTextColor(Color.WHITE); textSize = size; gravity = Gravity.CENTER
    }

    private fun roundButton(icon: String, active: Int?, onClick: () -> Unit): View {
        val v = TextView(act).apply {
            text = icon; textSize = 20f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            background = circle(active ?: Color.argb(70, 255, 255, 255))
            setOnClickListener { tick(it); onClick() }
        }
        v.layoutParams = GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED, 1f), GridLayout.spec(GridLayout.UNDEFINED, 1f))
            .apply { width = dp(60); height = dp(60); setMargins(dp(4), dp(4), dp(4), dp(4)) }
        return v
    }

    private fun squareTile(icon: String, active: Int? = null, onClick: () -> Unit): TextView = TextView(act).apply {
        text = icon; textSize = 24f; gravity = Gravity.CENTER
        background = if (active != null) glass(22, active) else glass(22)
        setOnClickListener { tick(it); onClick() }
    }

    private fun setTorch(on: Boolean): Boolean {
        val cm = act.getSystemService(CameraManager::class.java) ?: return false
        val id = runCatching {
            cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        }.getOrNull() ?: run { toast("No flashlight on this phone"); return false }
        return runCatching { cm.setTorchMode(id, on); torchOn = on }.isSuccess
    }

    /** Tall glass slider like iOS: drag up/down; the white fill shows the level. */
    private inner class GlassSlider(
        private val icon: String,
        private var value: Float,
        private val onChange: (Float) -> Unit
    ) : View(act) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; textSize = dp(20).toFloat() }
        init {
            background = glass(24)
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) =
                    outline.setRoundRect(0, 0, view.width, view.height, dp(24).toFloat())
            }
            clipToOutline = true
        }

        override fun onDraw(canvas: Canvas) {
            val h = height.toFloat()
            canvas.drawRect(0f, h * (1f - value), width.toFloat(), h, fill)
            text.color = if (value > 0.12f) 0xFF555555.toInt() else Color.WHITE
            canvas.drawText(icon, width / 2f, h - dp(16), text)
        }

        private var startY = 0f
        private var startV = 0f
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startY = e.y; startV = value; parent?.requestDisallowInterceptTouchEvent(true)
                    Motion.scaleTo(this, 1.04f, Motion.SNAPPY)
                }
                MotionEvent.ACTION_MOVE -> {
                    value = (startV + (startY - e.y) / height.coerceAtLeast(1)).coerceIn(0f, 1f)
                    onChange(value); invalidate()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    Motion.scaleTo(this, 1f, Motion.BOUNCY); tick(this)
                }
            }
            return true
        }
    }

    private fun brightnessSlider(): View {
        val cur = runCatching {
            Settings.System.getInt(act.contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
        }.getOrDefault(0.5f)
        var asked = false
        return GlassSlider("☀", cur) { v ->
            if (Settings.System.canWrite(act)) {
                runCatching {
                    Settings.System.putInt(act.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
                        Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                    Settings.System.putInt(act.contentResolver, Settings.System.SCREEN_BRIGHTNESS,
                        (v * 255).toInt().coerceIn(2, 255))
                }
            } else {
                // Until "Modify system settings" is allowed, at least brighten/dim this screen.
                val lp = act.window.attributes
                lp.screenBrightness = v.coerceIn(0.02f, 1f)
                act.window.attributes = lp
                if (!asked) {
                    asked = true
                    toast("Allow “Modify system settings” to change brightness everywhere")
                    runCatching {
                        act.startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                            Uri.parse("package:${act.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
            }
        }
    }

    private fun volumeSlider(): View {
        val audio = act.getSystemService(AudioManager::class.java)
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return GlassSlider("🔊", audio.getStreamVolume(AudioManager.STREAM_MUSIC) / max.toFloat()) { v ->
            runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, (v * max).roundToInt(), 0) }
        }
    }
}
