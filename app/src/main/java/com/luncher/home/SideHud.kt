package com.luncher.home

import android.app.Activity
import android.os.Build
import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.media.AudioManager
import android.text.TextPaint
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import kotlin.math.roundToInt

/**
 * The "side island": a black liquid shape on the left edge of the screen.
 * - Always rests as a small bump on the edge (tap it for a volume slider you can drag).
 * - Grows out for volume, Silent / Ring, Focus — and new messages (icon + sender + text;
 *   tap to open, swipe left to dismiss).
 * Its window is only as big as what it shows, so it never blocks the app underneath.
 */
class SideHud(private val act: Activity, private val settings: Settings, private val preview: Boolean = false) {
    private fun dp(v: Int) = (v * act.resources.displayMetrics.density).roundToInt()

    private enum class Kind { REST, VOLUME, STATUS, MESSAGE }
    /** Half the side island window's height, in dp. */
    private val HALF = 150

    private var wm: WindowManager? = null
    private var lp: WindowManager.LayoutParams? = null
    private var view: HudView? = null
    private val backToRest = Runnable { retract() }
    private var onOpen: (() -> Unit)? = null
    /** Set by the launcher: play / pause the current music (for "Tap the Sliver For → Play / Pause"). */
    var onPlayPause: (() -> Unit)? = null
    /** Called after you drag the side island somewhere new (the sidebar handle moves out of its way). */
    var onMoved: (() -> Unit)? = null

    private val audio get() = act.getSystemService(AudioManager::class.java)
    private val isRight get() = settings.sideRight
    private var createdRight: Boolean? = null

    /** The shape's colour (Settings › Side Island › Color and Visibility). */
    fun bodyColor(): Int {
        val base = when (settings.sideColor) {
            "glass" -> Color.rgb(36, 36, 40)
            "theme" -> IconFactory.blend(Color.BLACK, ThemeEngine.accent, 0.42f)
            "custom" -> settings.sideCustomColor
            else -> Color.BLACK
        }
        val a = (255 * settings.sideOpacity / 100f * (if (settings.sideColor == "glass") 0.82f else 1f)).toInt().coerceIn(40, 255)
        return Color.argb(a, Color.red(base), Color.green(base), Color.blue(base))
    }

    private fun windowY() = (act.resources.displayMetrics.heightPixels * settings.sideY / 100f).toInt() - dp(HALF)

    /** Show the resting bump (or remove everything if the side island is off). */
    fun rest() {
        if (!preview && (!settings.sideHud || !settings.sideRest)) { dismiss(); return }
        // Switched edges: rebuild the window on the other side.
        if (createdRight != null && createdRight != isRight) dismiss()
        // Moved along the edge: rebuild the chat button at the new place.
        if (histBtn != null && lp != null && lp!!.y != windowY()) hideHistoryButton(keep = true, immediate = true)
        lp?.let { l ->
            val y = windowY()
            if (l.y != y) { l.y = y; view?.let { v -> runCatching { wm?.updateViewLayout(v, l) } } }
        }
        view?.invalidate()
        val v = view ?: create() ?: return
        if (v.kind != Kind.REST) return
        v.kind = Kind.REST
        size(Kind.REST)
        v.growTo(1f)
        // The chat button lives with the resting side island and stays until you swipe it away.
        if (!preview && settings.sideHistory && histWanted) {
            val b = histBtn
            if (b == null || !b.isAttachedToWindow) { histBtn = null; v.postDelayed({ if (v.kind == Kind.REST && histBtn == null) showHistoryButton() }, 250) }
        }
    }

    fun volume(level: Float) = show(Kind.VOLUME) { it.glideLevel(level) }

    fun status(icons: List<Pair<Int, Int>>, label: String) = show(Kind.STATUS) {
        it.label = label
        it.icons = icons.map { (res, tint) -> act.getDrawable(res)!!.mutate().apply { setTint(tint) } }
    }

    /** A new message: app icon in the shape, sender and text beside it. */
    fun message(icon: Bitmap?, title: String, text: String, open: () -> Unit) = show(Kind.MESSAGE) {
        it.appIcon = icon; it.label = title; it.body = text
        onOpen = open
    }

    // ---------------------------------------------------------------- chat window

    private var card: View? = null
    private var cardLp: WindowManager.LayoutParams? = null

    /**
     * A chat came in: the side island shows the app icon and a small window slides out beside it
     * with the app's logo, the chat name and the latest messages (scroll to read more).
     * Tap a message to open that chat; ✕ or wait to close it.
     */
    fun chat(icon: Bitmap?, appLabel: String, chat: ChatInfo, open: () -> Unit) {
        hideHistory()
        hideHistoryButton(keep = true)
        lastIcon = icon
        hadChat = true
        message(icon, chat.title, chat.messages.lastOrNull()?.second ?: "", open)
        val v = view ?: return
        v.cardShown = true
        v.invalidate()
        showCard(icon, appLabel, chat, open)
        v.removeCallbacks(backToRest)
        v.postDelayed(backToRest, 9000)
    }

    private fun showCard(icon: Bitmap?, appLabel: String, chat: ChatInfo, open: () -> Unit) {
        hideCard(immediate = true)
        val hudLp = lp ?: return
        val w = wm ?: return
        val ctx = act
        val width = dp(268)
        val root = object : android.widget.LinearLayout(ctx) {
            // Touching the window keeps it open; letting go starts the timer again.
            override fun dispatchTouchEvent(e: MotionEvent): Boolean {
                val hv = view
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> hv?.removeCallbacks(backToRest)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (!holding) hv?.postDelayed(backToRest, 6000)
                }
                return super.dispatchTouchEvent(e)
            }
        }.apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(Color.argb(236, 20, 20, 22)); setStroke(maxOf(1, dp(1)), Color.argb(45, 255, 255, 255))
            }
            elevation = dp(10).toFloat()
        }
        // Header: platform logo + app name, close button
        val head = android.widget.LinearLayout(ctx).apply { orientation = android.widget.LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(android.widget.ImageView(ctx).apply {
            if (icon != null) setImageBitmap(icon)
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) =
                    outline.setRoundRect(0, 0, view.width, view.height, view.width * 0.26f)
            }
            clipToOutline = true
        }, android.widget.LinearLayout.LayoutParams(dp(18), dp(18)))
        head.addView(android.widget.TextView(ctx).apply {
            text = "${appLabel.uppercase()} · now"; textSize = 11f; setTextColor(0x99FFFFFF.toInt())
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD); setPadding(dp(7), 0, 0, 0)
        }, android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(android.widget.TextView(ctx).apply {
            text = "✕"; textSize = 12f; setTextColor(0xCCFFFFFF.toInt()); gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(0x33FFFFFF) }
            setOnClickListener { retract() }
        }, android.widget.LinearLayout.LayoutParams(dp(24), dp(24)))
        root.addView(head)
        root.addView(android.widget.TextView(ctx).apply {
            text = chat.title; textSize = 15f; setTextColor(Color.WHITE); maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            typeface = Typeface.create("sans-serif", Typeface.BOLD); setPadding(0, dp(8), 0, dp(6))
            setOnClickListener { retract(); open() }
        })
        // Scrollable list of the latest messages (newest at the bottom).
        val scroll = object : android.widget.ScrollView(ctx) {
            override fun onMeasure(wSpec: Int, hSpec: Int) =
                super.onMeasure(wSpec, View.MeasureSpec.makeMeasureSpec(dp(190), View.MeasureSpec.AT_MOST))
        }.apply { isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER }
        val list = android.widget.LinearLayout(ctx).apply { orientation = android.widget.LinearLayout.VERTICAL }
        val showNames = chat.messages.map { it.first }.filter { it.isNotBlank() }.toSet().size > 1
        for ((idx, pair) in chat.messages.withIndex()) {
            val (who, msg) = pair
            val med = if (settings.sideMedia) chat.media.getOrNull(idx) else null
            val mine = who == "You" || who.equals("me", true)
            val bubble = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(dp(11), dp(7), dp(11), dp(8))
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(16).toFloat(); setColor(if (mine) 0xFF0A84FF.toInt() else 0x2EFFFFFF)
                }
                setOnClickListener { retract(); open() }
            }
            if (showNames && who.isNotBlank() && !mine) bubble.addView(android.widget.TextView(ctx).apply {
                text = who; textSize = 11f; setTextColor(0xB3FFFFFF.toInt())
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            })
            if (med?.image != null) {
                // Photo right in the chat; tap to open it in the photo viewer.
                bubble.addView(android.widget.ImageView(ctx).apply {
                    setImageBitmap(med.image); adjustViewBounds = true; maxHeight = dp(200)
                    scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                    outlineProvider = object : android.view.ViewOutlineProvider() {
                        override fun getOutline(view: View, outline: android.graphics.Outline) =
                            outline.setRoundRect(0, 0, view.width, view.height, dp(12).toFloat())
                    }
                    clipToOutline = true
                    setOnClickListener { showViewer(med.image, open, med) }
                }, android.widget.LinearLayout.LayoutParams(dp(176), android.view.ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(2); bottomMargin = if (msg.isNotBlank()) dp(5) else 0
                })
            } else if (med != null) {
                // Video, voice message, PDF…: a chip that opens it in the app.
                bubble.addView(android.widget.TextView(ctx).apply {
                    text = "${med.emoji}  ${med.label} · View"; textSize = 13f; setTextColor(Color.WHITE)
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    setPadding(dp(10), dp(7), dp(10), dp(7))
                    background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(0x33FFFFFF) }
                    setOnClickListener { showViewer(null, open, med) }
                })
            }
            if (msg.isNotBlank()) bubble.addView(android.widget.TextView(ctx).apply { text = msg; textSize = 14f; setTextColor(Color.WHITE) })
            list.addView(bubble, android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(5); gravity = if (mine) Gravity.END else Gravity.START
            })
        }
        scroll.addView(list)
        root.addView(scroll, android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT))
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }

        // Reply right here (uses the app's own Reply, so it works for any chat app that has one).
        if (settings.sideReply && chat.reply != null) {
            val bar = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(0x26FFFFFF) }
                setPadding(dp(12), 0, dp(4), 0)
            }
            val input = android.widget.EditText(ctx).apply {
                hint = "Reply…"; setHintTextColor(0x80FFFFFF.toInt()); setTextColor(Color.WHITE); textSize = 14f
                background = null; maxLines = 3; setPadding(0, dp(8), 0, dp(8))
                imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEND
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                    android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            }
            val send = android.widget.TextView(ctx).apply {
                text = "↑"; textSize = 18f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
                background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(0xFF0A84FF.toInt()) }
            }
            fun doSend() {
                val t = input.text.toString().trim()
                if (t.isEmpty()) return
                if (chat.sendReply(act, t)) {
                    // Show it straight away as your (blue) message.
                    list.addView(android.widget.TextView(ctx).apply {
                        text = t; textSize = 14f; setTextColor(Color.WHITE); setPadding(dp(11), dp(7), dp(11), dp(8))
                        background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(0xFF0A84FF.toInt()) }
                    }, android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(5); gravity = Gravity.END })
                    input.setText("")
                    scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
                    root.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    if (centered) root.postDelayed({ returnCardHome() }, 1400)
                } else android.widget.Toast.makeText(act, "This app doesn't allow replies here", android.widget.Toast.LENGTH_SHORT).show()
            }
            send.setOnClickListener { doSend() }
            input.setOnEditorActionListener { _, _, _ -> doSend(); true }
            // Typing needs the keyboard: let this window take focus only while you reply.
            input.setOnTouchListener { v, e ->
                if (e.actionMasked == MotionEvent.ACTION_DOWN) startTyping(v as android.widget.EditText)
                false
            }
            bar.addView(input, android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            bar.addView(send, android.widget.LinearLayout.LayoutParams(dp(32), dp(32)))
            root.addView(bar, android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        }

        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(dp(320), View.MeasureSpec.AT_MOST))
        val center = hudLp.y + dp(HALF)
        val l = WindowManager.LayoutParams(width, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, hudLp.type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT).apply {
            gravity = (if (isRight) Gravity.END else Gravity.START) or Gravity.TOP
            x = dp(66)
            y = (center - root.measuredHeight / 2).coerceAtLeast(dp(30))
            token = hudLp.token
        }
        if (runCatching { w.addView(root, l) }.isFailure) return
        card = root; cardLp = l
        cardHomeX = l.x; cardHomeY = l.y; centered = false
        // Grows out of the side island.
        root.pivotX = if (isRight) width.toFloat() else 0f
        root.pivotY = root.measuredHeight / 2f
        root.alpha = 0f; root.scaleX = 0.5f; root.scaleY = 0.7f
        root.animate().alpha(1f).setDuration(180).start()
        Motion.scaleTo(root, 1f, Motion.BOUNCY)
    }

    /** True while you're typing a reply or looking at a photo: the chat window stays open. */
    private var holding = false

    private fun startTyping(input: android.widget.EditText) {
        val c = card ?: return
        val l = cardLp ?: return
        holding = true
        view?.removeCallbacks(backToRest)
        if (l.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0) {
            l.flags = l.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            l.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
            runCatching { wm?.updateViewLayout(c, l) }
        }
        input.post {
            input.requestFocus()
            act.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                ?.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
    }

    // ---------------------------------------------------------------- photo viewer (customisable)

    private var viewer: View? = null

    /** Opens a photo in a window under the chat (or centred / full screen — Settings › Chat Window). */
    fun showViewer(bmp: Bitmap?, open: (() -> Unit)?, media: ChatMedia? = null) {
        hideViewer(immediate = true)
        val hudLp = lp ?: return
        val w = wm ?: return
        val ctx = act
        val m = ctx.resources.displayMetrics
        holding = true
        view?.removeCallbacks(backToRest)
        // With a chat open, the photo/attachment window takes the middle and the chat slides under it.
        val stacked = card != null
        val pos = if (stacked) "center" else settings.viewerPos
        val full = pos == "full"
        val width = when { stacked -> (m.widthPixels * 0.86f).toInt(); pos == "below" -> dp(268); pos == "center" -> (m.widthPixels * 0.88f).toInt(); else -> m.widthPixels }
        val bg = when (settings.viewerBg) {
            "glass" -> Color.argb(205, 44, 44, 48)
            "theme" -> IconFactory.blend(Color.BLACK, ThemeEngine.accent, 0.35f) or 0xF0000000.toInt()
            "custom" -> settings.viewerColor
            else -> Color.argb(240, 12, 12, 14)
        }
        val root = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = if (full) 0f else dp(26).toFloat(); setColor(if (full) (bg and 0x00FFFFFF) or 0xF2000000.toInt() else bg)
                if (!full) setStroke(maxOf(1, dp(1)), 0x2EFFFFFF)
            }
            elevation = dp(12).toFloat()
        }
        val shape = settings.viewerShape
        // Top bar: what it is + ✕
        val top = FrameLayout(ctx)
        top.addView(android.widget.TextView(ctx).apply {
            text = media?.let { "${it.emoji}  ${it.label}" } ?: "📷  Photo"; textSize = 13f; setTextColor(0xCCFFFFFF.toInt())
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD); setPadding(dp(6), 0, 0, 0)
        }, FrameLayout.LayoutParams(android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.START or Gravity.CENTER_VERTICAL))
        top.addView(android.widget.TextView(ctx).apply {
            text = "✕"; textSize = 13f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply { setShape(android.graphics.drawable.GradientDrawable.OVAL); setColor(0x40FFFFFF) }
            setOnClickListener { hideViewer(toMiddle = true) }
        }, FrameLayout.LayoutParams(dp(30), dp(30), Gravity.END or Gravity.CENTER_VERTICAL))
        root.addView(top, android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, dp(34)).apply { bottomMargin = dp(8) })
        if (bmp == null) {
            // The app didn't share the file itself (e.g. Messenger's "Sent a photo"): a card to open it there.
            root.addView(android.widget.TextView(ctx).apply {
                text = media?.emoji ?: "📎"; textSize = 54f; gravity = Gravity.CENTER
                background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(0x1FFFFFFF) }
            }, android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, dp(170)))
            root.addView(android.widget.TextView(ctx).apply {
                text = "This app keeps the ${media?.label?.lowercase() ?: "file"} inside the app — open it there to see it."
                textSize = 13f; setTextColor(0x99FFFFFF.toInt()); gravity = Gravity.CENTER; setPadding(dp(8), dp(10), dp(8), 0)
            })
        }
        val img = android.widget.ImageView(ctx).apply {
            if (bmp != null) setImageBitmap(bmp)
            adjustViewBounds = shape != "circle"
            scaleType = if (shape == "circle") android.widget.ImageView.ScaleType.CENTER_CROP else android.widget.ImageView.ScaleType.FIT_CENTER
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    when (shape) {
                        "circle" -> outline.setOval(0, 0, view.width, view.height)
                        "square" -> outline.setRect(0, 0, view.width, view.height)
                        "squircle" -> outline.setRoundRect(0, 0, view.width, view.height, minOf(view.width, view.height) * 0.3f)
                        else -> outline.setRoundRect(0, 0, view.width, view.height, dp(18).toFloat())
                    }
                }
            }
            clipToOutline = true
            setOnClickListener { hideViewer() }
        }
        val maxH = if (full) (m.heightPixels * 0.75f).toInt() else dp(if (pos == "below") 260 else 420)
        if (bmp == null) Unit
        else if (shape == "circle") root.addView(img, android.widget.LinearLayout.LayoutParams(minOf(width - dp(20), maxH), minOf(width - dp(20), maxH)))
        else { img.maxHeight = maxH; root.addView(img, android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)) }
        val bar = android.widget.LinearLayout(ctx).apply { orientation = android.widget.LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        fun pill(t: String, fn: () -> Unit) = android.widget.TextView(ctx).apply {
            text = t; textSize = 13f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(dp(14), dp(7), dp(14), dp(7))
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(0x33FFFFFF) }
            setOnClickListener { fn() }
        }.also { bar.addView(it, android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(5), dp(10), dp(5), 0) }) }
        if (open != null) pill("Open in App") { hideViewer(); retract(); open() }
        root.addView(bar)
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((m.heightPixels * 0.62f).toInt(), View.MeasureSpec.AT_MOST))

        val cl = cardLp
        val c = card
        val l = WindowManager.LayoutParams(width, if (full) android.view.ViewGroup.LayoutParams.MATCH_PARENT else android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            hudLp.type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT).apply {
            token = hudLp.token
            when {
                stacked && cl != null && c != null -> {
                    // Middle of the screen; the chat window goes right under it.
                    gravity = (if (isRight) Gravity.END else Gravity.START) or Gravity.TOP
                    val total = root.measuredHeight + dp(12) + c.height
                    val topY = ((m.heightPixels - total) / 2).coerceAtLeast(dp(40))
                    x = (m.widthPixels - width) / 2; y = topY
                    moveCard((m.widthPixels - c.width) / 2, topY + root.measuredHeight + dp(12))
                }
                full || pos == "center" -> gravity = Gravity.CENTER
                cl != null && c != null -> {
                    gravity = (if (isRight) Gravity.END else Gravity.START) or Gravity.TOP
                    x = cl.x; y = cl.y + c.height + dp(10)
                }
                else -> { gravity = (if (isRight) Gravity.END else Gravity.START) or Gravity.TOP; x = dp(66); y = hudLp.y + dp(80) }
            }
        }
        if (runCatching { w.addView(root, l) }.isFailure) { holding = false; return }
        viewer = root
        // Opening animation (Settings › Chat Window › Photo Animation).
        root.cameraDistance = 9000f * m.density
        root.pivotX = if (pos == "below" && !stacked) (if (isRight) width.toFloat() else 0f) else width / 2f
        root.pivotY = 0f
        when (settings.viewerAnim) {
            "slide" -> { root.alpha = 0f; root.translationY = -dp(40).toFloat()
                root.animate().alpha(1f).translationY(0f).setDuration(320).setInterpolator(android.view.animation.PathInterpolator(0.2f, 0.9f, 0.1f, 1f)).start() }
            "fade" -> { root.alpha = 0f; root.animate().alpha(1f).setDuration(280).start() }
            "flip" -> { root.alpha = 0f; root.rotationX = -80f
                root.animate().alpha(1f).rotationX(0f).setDuration(480).setInterpolator(android.view.animation.OvershootInterpolator(1.1f)).start() }
            else -> { root.alpha = 0f; root.scaleX = 0.6f; root.scaleY = 0.6f
                root.animate().alpha(1f).setDuration(200).start(); Motion.scaleTo(root, 1f, Motion.BOUNCY) }
        }
        root.postDelayed({ if (viewer === root) hideViewer() }, 30_000)
    }

    /** Where the chat window normally sits (next to the side island). */
    private var cardHomeX = 0
    private var cardHomeY = 0
    /** The chat window is in the middle of the screen (after viewing a photo). */
    private var centered = false

    /** Glide the chat window to a new spot. */
    private fun moveCard(toX: Int, toY: Int) {
        val c = card ?: return
        val l = cardLp ?: return
        val fx = l.x; val fy = l.y
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 380; interpolator = android.view.animation.PathInterpolator(0.2f, 0.9f, 0.1f, 1f)
            addUpdateListener {
                val f = it.animatedValue as Float
                l.x = (fx + (toX - fx) * f).toInt(); l.y = (fy + (toY - fy) * f).toInt()
                if (c.isAttachedToWindow) runCatching { wm?.updateViewLayout(c, l) }
            }
            start()
        }
    }

    /** After replying from the middle, the chat window goes back beside the side island. */
    private fun returnCardHome() {
        if (!centered) return
        centered = false
        holding = false
        moveCard(cardHomeX, cardHomeY)
        view?.removeCallbacks(backToRest); view?.postDelayed(backToRest, 6000)
    }

    fun hideViewer(immediate: Boolean = false, toMiddle: Boolean = false) {
        val v = viewer ?: return
        viewer = null
        holding = false
        val c = card
        if (c != null && toMiddle) {
            // ✕ on the photo: the chat window moves to the middle so you can answer.
            val m = act.resources.displayMetrics
            centered = true
            holding = true
            moveCard((m.widthPixels - c.width) / 2, ((m.heightPixels - c.height) / 2).coerceAtLeast(dp(40)))
            view?.removeCallbacks(backToRest)
            // If you don't reply, it goes back to the side after a while.
            view?.postDelayed({ if (centered && viewer == null) returnCardHome() }, 12_000)
        } else if (c != null && !immediate) {
            if (centered || cardLp?.x != cardHomeX) { centered = true; returnCardHome() }
            else { view?.removeCallbacks(backToRest); view?.postDelayed(backToRest, 6000) }
        }
        if (immediate) { runCatching { wm?.removeViewImmediate(v) }; return }
        v.animate().alpha(0f).scaleX(0.9f).scaleY(0.9f).setDuration(180).withEndAction { runCatching { wm?.removeViewImmediate(v) } }.start()
    }

    /** Settings preview of the photo viewer with a sample picture. */
    fun previewViewer() {
        if (view == null) rest()
        val b = Bitmap.createBitmap(600, 420, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint().apply { shader = android.graphics.LinearGradient(0f, 0f, 600f, 420f,
            intArrayOf(0xFF2E3A59.toInt(), 0xFF8E5CF2.toInt(), 0xFFFF8A5C.toInt()), null, android.graphics.Shader.TileMode.CLAMP) }
        c.drawRect(0f, 0f, 600f, 420f, p)
        p.shader = null; p.color = 0x55FFFFFF; c.drawCircle(440f, 120f, 60f, p)
        showViewer(b, null, null)
    }

    private fun hideCard(immediate: Boolean = false) {
        hideViewer(immediate = true)
        holding = false
        val c = card ?: return
        card = null; cardLp = null
        view?.cardShown = false
        if (immediate) { runCatching { wm?.removeViewImmediate(c) }; return }
        c.animate().alpha(0f).scaleX(0.6f).scaleY(0.8f).setDuration(180)
            .withEndAction { runCatching { wm?.removeViewImmediate(c) } }.start()
    }

    private fun show(kind: Kind, fill: (HudView) -> Unit) {
        // The chat button is separate: it tucks away while volume / status / messages show.
        if (histBtn != null) { hideHistory(); hideHistoryButton(keep = true) }
        val v = view ?: create() ?: return
        val wasRest = v.kind == Kind.REST
        if (kind != Kind.MESSAGE) hideCard()
        v.kind = kind
        fill(v)
        size(kind)
        if (wasRest) v.grown = 0f
        v.invalidate()
        v.growTo(1f)
        v.removeCallbacks(backToRest)
        val ms = settings.sideMs.toLong()
        v.postDelayed(backToRest, if (kind == Kind.MESSAGE) ms + 3200L else ms)
        if (kind == Kind.MESSAGE) v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
    }

    /** Window size for each state: tiny strip at rest, wider only while something is showing. */
    private fun size(kind: Kind) {
        val l = lp ?: return
        val v = view ?: return
        l.width = when (kind) { Kind.REST -> dp(20); Kind.VOLUME -> dp(96); Kind.STATUS -> dp(220); Kind.MESSAGE -> dp(320) }
        l.flags = if (kind == Kind.STATUS) l.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            else l.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        runCatching { wm?.updateViewLayout(v, l) }
    }

    private fun create(): HudView? {
        val svc = LuncherAccessibility.instance
        val type = when {
            preview -> WindowManager.LayoutParams.TYPE_APPLICATION
            svc != null -> WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            android.provider.Settings.canDrawOverlays(act) -> WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else -> WindowManager.LayoutParams.TYPE_APPLICATION
        }
        if (type == WindowManager.LayoutParams.TYPE_APPLICATION && act.window?.decorView?.windowToken == null) return null
        val ctx = if (type == WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY) svc!! else act
        val w = ctx.getSystemService(WindowManager::class.java) ?: return null
        val v = HudView(act)
        val l = WindowManager.LayoutParams(dp(20), dp(HALF * 2), type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT).apply {
            gravity = (if (isRight) Gravity.END else Gravity.START) or Gravity.TOP
            y = windowY()
            if (type == WindowManager.LayoutParams.TYPE_APPLICATION) token = act.window.decorView.windowToken
            if (android.os.Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        if (runCatching { w.addView(v, l) }.isFailure) return null
        wm = w; lp = l; view = v; createdRight = isRight
        return v
    }

    /** Shrink back to the resting bump (or away if resting is off). */
    private fun retract() {
        hideCard()
        // After a chat closes (or you swipe it away), leave the little history button on the edge.
        if (hadChat && settings.sideHistory) { hadChat = false; histWanted = true }
        val v = view ?: return
        onOpen = null
        v.growTo(0f) {
            if (view !== v) return@growTo
            if (settings.sideHud && settings.sideRest) { v.kind = Kind.REST; size(Kind.REST); v.growTo(1f) }
            if (histWanted && histBtn == null) v.postDelayed({ if (v.kind == Kind.REST) showHistoryButton() }, 300)
            else dismiss()
        }
    }

    /** Demo for the Settings preview: grow out with the volume slider, then rest. */
    fun demo() {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        rest()
        view?.postDelayed({ volume(audio.getStreamVolume(AudioManager.STREAM_MUSIC) / max.toFloat()) }, 120)
    }

    // ---------------------------------------------------------------- chat history button + list

    /** Set by the launcher. */
    var historyProvider: (() -> List<ChatInfo>)? = null
    var appLookup: ((String) -> Pair<Bitmap?, String>?)? = null
    var openChat: ((ChatInfo) -> Unit)? = null

    private var lastIcon: Bitmap? = null
    private var hadChat = false
    private var unread = 0
    private var histBtn: View? = null
    private var histPanel: View? = null

    /** Called for every new message so the button's red number stays right. */
    fun countNew() { unread++; histWanted = true }

    /** Small round button (latest app's logo + red count) resting under the side island. */
    /** The chat button wants to be shown (a chat closed and you didn't swipe it away). */
    private var histWanted = true

    /**
     * The chat button: its own little liquid bump on the edge (separate from the volume / status
     * shape), with the latest app's logo and a red count. Tap for Recent Chats, swipe into the edge to hide.
     */
    fun showHistoryButton() {
        // The chat button is now part of the side island's own shape (under the volume slider).
        if (true) return
        if (!settings.sideHistory) return
        val hudLp = lp ?: return
        val w = wm ?: return
        histWanted = true
        // Only while the side island is resting — never on top of the volume slider or a message.
        if (view?.kind != null && view?.kind != Kind.REST) return
        hideHistoryButton(keep = true, immediate = true)
        val ctx = act
        val bubble = LiquidBubble(ctx)
        val icon = android.widget.ImageView(ctx).apply {
            val ic = lastIcon
            if (ic != null) setImageBitmap(ic) else { setImageResource(R.drawable.ic_chat); setColorFilter(Color.WHITE); setPadding(dp(4), dp(4), dp(4), dp(4)) }
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) = outline.setOval(0, 0, view.width, view.height)
            }
            clipToOutline = true
        }
        val box = FrameLayout(ctx)
        box.addView(icon, FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER))
        if (unread > 0) box.addView(android.widget.TextView(ctx).apply {
            text = if (unread > 9) "9+" else "$unread"; textSize = 9.5f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(0xFFFF3B30.toInt()) }
        }, FrameLayout.LayoutParams(dp(17), dp(17), Gravity.TOP or Gravity.END))
        bubble.addView(box, FrameLayout.LayoutParams(dp(42), dp(42), (if (isRight) Gravity.END else Gravity.START) or Gravity.CENTER_VERTICAL)
            .apply { if (isRight) rightMargin = dp(6) else leftMargin = dp(6) })
        var downX = 0f
        bubble.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; box.animate().scaleX(0.86f).scaleY(0.86f).setDuration(80).start() }
                MotionEvent.ACTION_UP -> {
                    Motion.scaleTo(box, 1f, Motion.BOUNCY)
                    val dx = e.rawX - downX
                    // Swipe it into the edge to put it away until the next message.
                    if (if (isRight) dx > dp(20) else dx < -dp(20)) { unread = 0; histWanted = false; hideHistory(); hideHistoryButton() }
                    else { bubble.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK); toggleHistory() }
                }
                MotionEvent.ACTION_CANCEL -> Motion.scaleTo(box, 1f, Motion.BOUNCY)
            }
            true
        }
        val l = WindowManager.LayoutParams(dp(60), dp(96), hudLp.type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT).apply {
            gravity = (if (isRight) Gravity.END else Gravity.START) or Gravity.TOP
            x = 0; y = hudLp.y + dp(HALF) + dp(62)
            token = hudLp.token
            if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        if (runCatching { w.addView(bubble, l) }.isFailure) return
        histBtn = bubble
        bubble.grow(true, null)
        box.alpha = 0f; box.scaleX = 0.4f; box.scaleY = 0.4f
        box.animate().alpha(1f).setStartDelay(120).setDuration(200).start()
        box.postDelayed({ Motion.scaleTo(box, 1f, Motion.BOUNCY) }, 120)
    }

    /** A black liquid bump growing out of the screen edge (same look as the side island). */
    private inner class LiquidBubble(ctx: Activity) : FrameLayout(ctx) {
        private var g = 0f
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val path = Path()
        private val m = android.graphics.Matrix()
        init { setWillNotDraw(false); clipChildren = false }

        fun grow(open: Boolean, end: (() -> Unit)?) {
            ValueAnimator.ofFloat(g, if (open) 1f else 0f).apply {
                duration = if (open) 460 else 220
                interpolator = if (open) android.view.animation.OvershootInterpolator(1.3f) else android.view.animation.AccelerateInterpolator()
                addUpdateListener { g = it.animatedValue as Float; invalidate() }
                if (end != null) addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(a: android.animation.Animator) = end()
                })
                start()
            }
        }

        override fun onDraw(c: Canvas) {
            if (g <= 0.001f) return
            val bw = dp(54) * g
            val h = dp(54).toFloat()
            val top = (height - h) / 2f; val bottom = top + h
            val f = dp(18) * g.coerceAtMost(1f)
            val rad = minOf(h / 2f, maxOf(bw, 1f) / 2f)
            path.reset()
            path.moveTo(0f, top - f)
            path.cubicTo(0f, top - f * 0.35f, f * 0.25f, top, f * 0.9f, top)
            path.lineTo(bw - rad, top)
            path.quadTo(bw, top, bw, top + rad)
            path.lineTo(bw, bottom - rad)
            path.quadTo(bw, bottom, bw - rad, bottom)
            path.lineTo(f * 0.9f, bottom)
            path.cubicTo(f * 0.25f, bottom, 0f, bottom + f * 0.35f, 0f, bottom + f)
            path.close()
            if (isRight) { m.setScale(-1f, 1f, width / 2f, 0f); path.transform(m) }
            p.color = bodyColor()
            c.drawPath(path, p)
        }
    }

    private fun hideHistoryButton(keep: Boolean = false, immediate: Boolean = false) {
        if (!keep) histWanted = false
        val b = histBtn ?: return
        histBtn = null
        if (immediate) { runCatching { wm?.removeViewImmediate(b) }; return }
        val lb = b as? LiquidBubble
        if (lb != null) {
            for (i in 0 until lb.childCount) lb.getChildAt(i).animate().alpha(0f).scaleX(0.4f).scaleY(0.4f).setDuration(140).start()
            lb.grow(false) { runCatching { wm?.removeViewImmediate(b) } }
        } else runCatching { wm?.removeViewImmediate(b) }
        b.postDelayed({ runCatching { wm?.removeViewImmediate(b) } }, 600)
    }

    private fun toggleHistory() {
        if (histPanel != null) hideHistory()
        else { view?.removeCallbacks(backToRest); showHistory() }
    }

    /** "Recent Chats": every conversation from all apps; tap one to open its chat window again. */
    private fun showHistory() {
        val hudLp = lp ?: return
        val w = wm ?: return
        val chats = historyProvider?.invoke().orEmpty()
        unread = 0
        val ctx = act
        val width = dp(286)
        val root = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(24).toFloat(); setColor(Color.argb(240, 20, 20, 22)); setStroke(maxOf(1, dp(1)), 0x2EFFFFFF)
            }
            elevation = dp(12).toFloat()
        }
        val head = android.widget.LinearLayout(ctx).apply { orientation = android.widget.LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(android.widget.TextView(ctx).apply {
            text = "Recent Chats"; textSize = 17f; setTextColor(Color.WHITE); typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }, android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(android.widget.TextView(ctx).apply {
            text = "✕"; textSize = 12f; setTextColor(0xCCFFFFFF.toInt()); gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(0x33FFFFFF) }
            setOnClickListener { hideHistory() }
        }, android.widget.LinearLayout.LayoutParams(dp(26), dp(26)))
        root.addView(head)
        val scroll = object : android.widget.ScrollView(ctx) {
            override fun onMeasure(wSpec: Int, hSpec: Int) =
                super.onMeasure(wSpec, View.MeasureSpec.makeMeasureSpec(dp(340), View.MeasureSpec.AT_MOST))
        }.apply { isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER }
        val list = android.widget.LinearLayout(ctx).apply { orientation = android.widget.LinearLayout.VERTICAL }
        if (chats.isEmpty()) list.addView(android.widget.TextView(ctx).apply {
            text = "No messages yet"; textSize = 14f; setTextColor(0x99FFFFFF.toInt()); setPadding(0, dp(14), 0, dp(10))
        })
        for (c in chats.asReversed()) {
            val app = appLookup?.invoke(c.pkg)
            val row = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), dp(9), dp(4), dp(9))
                background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x33FFFFFF), null,
                    android.graphics.drawable.ColorDrawable(Color.WHITE))
                setOnClickListener {
                    hideHistory()
                    chat(app?.first, app?.second ?: "Messages", c) { openChat?.invoke(c) }
                }
            }
            row.addView(android.widget.ImageView(ctx).apply {
                app?.first?.let { setImageBitmap(it) }
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: android.graphics.Outline) =
                        outline.setRoundRect(0, 0, view.width, view.height, view.width * 0.26f)
                }
                clipToOutline = true
            }, android.widget.LinearLayout.LayoutParams(dp(36), dp(36)))
            val texts = android.widget.LinearLayout(ctx).apply { orientation = android.widget.LinearLayout.VERTICAL; setPadding(dp(11), 0, 0, 0) }
            texts.addView(android.widget.TextView(ctx).apply {
                text = c.title; textSize = 14f; setTextColor(Color.WHITE); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            })
            val last = c.messages.lastOrNull()
            val lastMedia = c.media.lastOrNull()
            texts.addView(android.widget.TextView(ctx).apply {
                text = when {
                    !last?.second.isNullOrBlank() -> last!!.second
                    lastMedia != null -> "📎 ${lastMedia.label}"
                    else -> ""
                }
                textSize = 12.5f; setTextColor(0x99FFFFFF.toInt()); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            })
            row.addView(texts, android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(android.widget.TextView(ctx).apply {
                text = ago(c.time); textSize = 11f; setTextColor(0x80FFFFFF.toInt()); setPadding(dp(6), 0, 0, 0)
            })
            list.addView(row)
        }
        scroll.addView(list)
        root.addView(scroll, android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(dp(420), View.MeasureSpec.AT_MOST))
        val btnY = hudLp.y + dp(HALF) + dp(75) - dp(27)
        val l = WindowManager.LayoutParams(width, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, hudLp.type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT).apply {
            gravity = (if (isRight) Gravity.END else Gravity.START) or Gravity.TOP
            x = dp(58)
            y = (btnY + dp(27) - root.measuredHeight / 2).coerceIn(dp(30), act.resources.displayMetrics.heightPixels - root.measuredHeight - dp(30))
            token = hudLp.token
        }
        root.setOnTouchListener { _, e -> if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) hideHistory(); false }
        if (runCatching { w.addView(root, l) }.isFailure) return
        histPanel = root
        root.pivotX = if (isRight) width.toFloat() else 0f
        root.pivotY = root.measuredHeight / 2f
        root.alpha = 0f; root.scaleX = 0.6f; root.scaleY = 0.8f
        root.animate().alpha(1f).setDuration(180).start()
        Motion.scaleTo(root, 1f, Motion.BOUNCY)
        // Refresh the badge on the button.
        if (histBtn != null) { hideHistoryButton(); root.postDelayed({ if (histPanel === root) showHistoryButton() }, 180) }
        root.postDelayed({ if (histPanel === root) hideHistory() }, 45_000)
    }

    private fun hideHistory() {
        val p = histPanel ?: return
        histPanel = null
        view?.let { v -> if (v.kind == Kind.VOLUME) { v.removeCallbacks(backToRest); v.postDelayed(backToRest, 2200) } }
        p.animate().alpha(0f).scaleX(0.7f).scaleY(0.85f).setDuration(170).withEndAction { runCatching { wm?.removeViewImmediate(p) } }.start()
    }

    private fun ago(t: Long): String {
        val m = ((System.currentTimeMillis() - t) / 60000).coerceAtLeast(0)
        return when { m < 1 -> "now"; m < 60 -> "${m}m"; m < 1440 -> "${m / 60}h"; else -> "${m / 1440}d" }
    }

    fun dismiss() {
        runCatching { histPanel?.let { wm?.removeViewImmediate(it) } }; histPanel = null
        runCatching { histBtn?.let { wm?.removeViewImmediate(it) } }; histBtn = null
        hideCard(immediate = true)
        val v = view ?: return
        view = null; lp = null; createdRight = null
        runCatching { wm?.removeViewImmediate(v) }
    }

    private inner class HudView(ctx: Activity) : View(ctx) {
        var kind = Kind.REST
        var level = 0f
        var label: String? = null
        var body: String? = null
        var appIcon: Bitmap? = null
        var icons: List<Drawable> = emptyList()
        var grown = 0f
        var cardShown = false
        private var levelAnim: android.animation.ValueAnimator? = null
        /** The slider fill glides to the new level instead of jumping. */
        fun glideLevel(to: Float) {
            levelAnim?.cancel()
            if (grown < 0.05f) { level = to; return }
            levelAnim = android.animation.ValueAnimator.ofFloat(level, to).apply {
                duration = 180; interpolator = android.view.animation.DecelerateInterpolator()
                addUpdateListener { level = it.animatedValue as Float; invalidate() }
                start()
            }
        }
        private var spring: SpringAnimation? = null
        private val black = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        private val mirror = android.graphics.Matrix()
        private var restColor = 0
        /** x for something of width [w] placed at [x] from the edge, flipped on the right edge. */
        private fun mx(x: Float, w: Float = 0f) = if (isRight) width - x - w else x
        private fun flip() { if (isRight) { mirror.setScale(-1f, 1f, width / 2f, 0f); path.transform(mirror) } }
        private val glassFill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val glassRim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = maxOf(1f, dp(1).toFloat()) }
        private val badge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF3B30.toInt() }
        private val badgeText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textAlign = Paint.Align.CENTER; textSize = dp(10).toFloat(); typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        private val chatIcon = act.getDrawable(R.drawable.ic_chat)!!.mutate()
        private val clip = Path()
        /** Where the chat button sits (for taps), in view coordinates. */
        var chatSlot = RectF()

        /** Fill the shape: solid colour, or frosted glass with a soft shine and a light rim. */
        fun drawBody(c: Canvas) {
            if (settings.sideColor == "glass") {
                black.color = Color.argb((150 * settings.sideOpacity / 100f).toInt().coerceIn(60, 220), 22, 22, 26)
                c.drawPath(path, black)
                val b = android.graphics.RectF(); path.computeBounds(b, true)
                glassFill.shader = android.graphics.LinearGradient(b.left, b.top, b.right, b.bottom,
                    intArrayOf(0x40FFFFFF, 0x10FFFFFF, 0x24FFFFFF), floatArrayOf(0f, 0.55f, 1f), android.graphics.Shader.TileMode.CLAMP)
                c.drawPath(path, glassFill)
                glassRim.shader = android.graphics.LinearGradient(b.left, b.top, b.left, b.bottom,
                    intArrayOf(0x88FFFFFF.toInt(), 0x22FFFFFF, 0x55FFFFFF), null, android.graphics.Shader.TileMode.CLAMP)
                c.drawPath(path, glassRim)
            } else c.drawPath(path, black)
        }

        /** The chat button inside the shape: latest app's logo (or a chat icon) and the red count. */
        fun drawChatSlot(c: Canvas, bodyW: Float, slotTop: Float, a: Float) {
            val s = dp(36).toFloat()
            val cx = mx(bodyW / 2f); val cy = slotTop + dp(30)
            // Grows in a touch after the shape, for a smooth reveal.
            val k = ((a - 0.15f) / 0.85f).coerceIn(0f, 1f)
            val sz = s * (0.6f + 0.4f * k)
            chatSlot.set(cx - s / 2 - dp(6), cy - s / 2 - dp(6), cx + s / 2 + dp(6), cy + s / 2 + dp(6))
            val ic = lastIcon
            if (ic != null) {
                c.save()
                clip.reset(); clip.addCircle(cx, cy, sz / 2, Path.Direction.CW); c.clipPath(clip)
                val pp = Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = (255 * k).toInt() }
                r.set(cx - sz / 2, cy - sz / 2, cx + sz / 2, cy + sz / 2); c.drawBitmap(ic, null, r, pp)
                c.restore()
            } else {
                badge.color = 0x33FFFFFF; badge.alpha = (90 * k).toInt(); c.drawCircle(cx, cy, sz / 2, badge)
                val i = (sz * 0.56f).toInt()
                chatIcon.setBounds((cx - i / 2).toInt(), (cy - i / 2).toInt(), (cx + i / 2).toInt(), (cy + i / 2).toInt())
                chatIcon.alpha = (255 * k).toInt(); chatIcon.draw(c)
            }
            if (unread > 0) {
                badge.color = 0xFFFF3B30.toInt(); badge.alpha = (255 * k).toInt()
                val bx = cx + sz * 0.36f; val by = cy - sz * 0.36f
                c.drawCircle(bx, by, dp(8).toFloat(), badge)
                badgeText.alpha = (255 * k).toInt()
                c.drawText(if (unread > 9) "9+" else "$unread", bx, by + dp(4), badgeText)
            }
        }

        private fun flipRect() { if (isRight) r.set(width - r.right, r.top, width - r.left, r.bottom) }
        private val restPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var restShaderTop = -1f
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3A3A3C.toInt() }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF2F2F7.toInt() }
        private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = dp(16).toFloat(); typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setShadowLayer(dp(4).toFloat(), 0f, dp(1).toFloat(), 0xAA000000.toInt())
        }
        private val small = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xEEFFFFFF.toInt(); textSize = dp(13).toFloat()
            setShadowLayer(dp(4).toFloat(), 0f, dp(1).toFloat(), 0xAA000000.toInt())
        }
        private val path = Path()
        private val r = RectF()
        private val iconClip = Path()
        private val speaker = act.getDrawable(R.drawable.ic_speaker)!!.mutate()

        fun growTo(target: Float, end: (() -> Unit)? = null) {
            spring?.cancel()
            val holder = FloatValueHolder(grown)
            spring = SpringAnimation(holder).apply {
                spring = SpringForce(target).apply {
                    stiffness = if (target > 0f) 380f else 900f
                    dampingRatio = if (target > 0f) 0.66f else 1f
                }
                setMinimumVisibleChange(0.002f)
                addUpdateListener { _, v, _ -> grown = v.coerceAtLeast(0f); invalidate() }
                if (end != null) addEndListener { _, canceled, _, _ -> if (!canceled) end() }
                start()
            }
        }

        private fun shape(bodyW: Float, top: Float, bottom: Float, fillet: Float = dp(26).toFloat()) {
            // The curves where it leaves the edge must fit inside the window, or they get cut off.
            val f = minOf(fillet, width * 0.45f) * grown.coerceAtMost(1f)
            val rad = minOf(dp(20).toFloat(), bodyW / 2f)
            path.reset()
            path.moveTo(0f, top - f)
            path.cubicTo(0f, top - f * 0.35f, f * 0.25f, top, f * 0.9f, top)
            path.lineTo(bodyW - rad, top)
            path.quadTo(bodyW, top, bodyW, top + rad)
            path.lineTo(bodyW, bottom - rad)
            path.quadTo(bodyW, bottom, bodyW - rad, bottom)
            path.lineTo(f * 0.9f, bottom)
            path.cubicTo(f * 0.25f, bottom, 0f, bottom + f * 0.35f, 0f, bottom + f)
            path.close()
        }

        override fun onDraw(c: Canvas) {
            if (grown <= 0.001f) return
            black.color = bodyColor()
            val a = ((grown - 0.4f) / 0.6f).coerceIn(0f, 1f)
            when (kind) {
                Kind.REST -> {
                    // Resting: a soft black sliver hugging the edge like the phone's bezel,
                    // fading out at both ends — no corners, no outline.
                    val h = dp(120).toFloat()
                    val top = (height - h) / 2f
                    val w = dp(4) * grown
                    val col = bodyColor()
                    if (restShaderTop != top || restColor != col) {
                        restShaderTop = top; restColor = col
                        val solid = col or 0xFF000000.toInt()
                        val clear = solid and 0x00FFFFFF
                        restPaint.shader = android.graphics.LinearGradient(0f, top, 0f, top + h,
                            intArrayOf(clear, solid, solid, clear),
                            floatArrayOf(0f, 0.3f, 0.7f, 1f), android.graphics.Shader.TileMode.CLAMP)
                    }
                    restPaint.alpha = (android.graphics.Color.alpha(col) * 0.9f * grown).toInt().coerceIn(0, 255)
                    r.set(-w, top, w, top + h); flipRect()
                    c.drawRoundRect(r, w, w * 6f, restPaint)
                }
                Kind.VOLUME -> {
                    val bodyW = dp(58) * grown
                    val chat = settings.sideHistory && !preview
                    val sliderH = dp(150).toFloat()
                    val h = sliderH + (if (chat) dp(60) else 0)
                    val top = (height - h) / 2f; val bottom = top + sliderH
                    shape(bodyW, top, top + h); flip(); drawBody(c)
                    if (chat && a > 0f) drawChatSlot(c, bodyW, top + sliderH, a)
                    if (a <= 0f) return
                    val inset = dp(7).toFloat()
                    r.set(inset, top + inset, bodyW - inset, bottom - inset); flipRect()
                    val cr = dp(14).toFloat()
                    track.alpha = (255 * a).toInt(); c.drawRoundRect(r, cr, cr, track)
                    c.save(); c.clipRect(r.left, r.bottom - r.height() * level, r.right, r.bottom)
                    fill.alpha = (255 * a).toInt(); c.drawRoundRect(r, cr, cr, fill); c.restore()
                    val s = dp(22)
                    val sx = mx(bodyW / 2 - s / 2, s.toFloat()).toInt()
                    speaker.setBounds(sx, (r.bottom - dp(10) - s).toInt(), sx + s, (r.bottom - dp(10)).toInt())
                    speaker.setTint(if (level > 0.2f) 0xFF6E6E73.toInt() else Color.WHITE)
                    speaker.alpha = (255 * a).toInt(); speaker.draw(c)
                }
                Kind.STATUS -> {
                    val bodyW = dp(46) * grown
                    val h = dp(44) + dp(40) * (icons.size - 1).coerceAtLeast(0).toFloat()
                    val top = (height - h) / 2f; val bottom = top + h
                    shape(bodyW, top, bottom); flip(); drawBody(c)
                    if (a <= 0f) return
                    val s = dp(24); var y = top + dp(10)
                    for (d in icons) {
                        val dx0 = mx(bodyW / 2 - s / 2, s.toFloat()).toInt()
                        d.setBounds(dx0, y.toInt(), dx0 + s, (y + s).toInt())
                        d.alpha = (255 * a).toInt(); d.draw(c); y += dp(40)
                    }
                    label?.let {
                        text.alpha = (255 * a).toInt()
                        c.drawText(it, mx(bodyW + dp(14), text.measureText(it)), (top + bottom) / 2f + dp(6), text)
                    }
                }
                Kind.MESSAGE -> {
                    val bodyW = dp(54) * grown
                    val h = dp(62).toFloat(); val top = (height - h) / 2f; val bottom = top + h
                    shape(bodyW, top, bottom); flip(); drawBody(c)
                    if (a <= 0f) return
                    val s = dp(36).toFloat()
                    val ix = mx((bodyW - s) / 2f, s); val iy = (top + bottom - s) / 2f
                    appIcon?.let { bmp ->
                        c.save()
                        iconClip.reset(); iconClip.addRoundRect(ix, iy, ix + s, iy + s, s * 0.24f, s * 0.24f, Path.Direction.CW)
                        c.clipPath(iconClip)
                        val p = Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = (255 * a).toInt() }
                        r.set(ix, iy, ix + s, iy + s); c.drawBitmap(bmp, null, r, p)
                        c.restore()
                    }
                    if (cardShown) return
                    val tx = bodyW + dp(12); val maxW = (width - tx - dp(8)).coerceAtLeast(dp(40).toFloat())
                    text.alpha = (255 * a).toInt(); small.alpha = (240 * a).toInt()
                    val t = TextUtils.ellipsize(label ?: "", text, maxW, TextUtils.TruncateAt.END).toString()
                    val b = TextUtils.ellipsize(body ?: "", small, maxW, TextUtils.TruncateAt.END).toString()
                    c.drawText(t, mx(tx, text.measureText(t)), (top + bottom) / 2f - dp(3), text)
                    c.drawText(b, mx(tx, small.measureText(b)), (top + bottom) / 2f + dp(15), small)
                }
            }
        }

        // ---- touch: tap the bump for volume, drag to change it; tap a message to open, swipe left to dismiss
        private var downX = 0f
        private var downY = 0f
        private var startLevel = 0f
        private var dragging = false
        private var moving = false
        private var startY = 0
        /** Long-press: pick the side island up and drag it up or down the edge. */
        private val startMove = Runnable {
            if (!dragging) { moving = true; performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) }
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; dragging = false; moving = false; startLevel = level
                    startY = lp?.y ?: 0
                    removeCallbacks(backToRest)
                    postDelayed(startMove, 450)
                }
                MotionEvent.ACTION_MOVE -> if (moving) {
                    val l = lp ?: return true
                    l.y = (startY + (e.rawY - downY)).toInt()
                        .coerceIn(-dp(60), act.resources.displayMetrics.heightPixels - dp(200))
                    runCatching { wm?.updateViewLayout(this, l) }
                } else if (kind == Kind.VOLUME) {
                    val dy = downY - e.rawY
                    if (kotlin.math.abs(dy) > dp(6)) { dragging = true; removeCallbacks(startMove) }
                    if (dragging) {
                        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                        val lv = (startLevel + dy / dp(150)).coerceIn(0f, 1f)
                        val steps = (lv * max).roundToInt()
                        if (steps != audio.getStreamVolume(AudioManager.STREAM_MUSIC)) {
                            runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, steps, 0) }
                            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        }
                        level = lv; invalidate()
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    removeCallbacks(startMove)
                    if (moving) {
                        // Save where you dropped it (as % of the screen height).
                        moving = false
                        val h = act.resources.displayMetrics.heightPixels.toFloat()
                        settings.sideY = (((lp?.y ?: 0) + dp(HALF)) / h * 100f).roundToInt().coerceIn(5, 90)
                        onMoved?.invoke()
                        performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                        postDelayed(backToRest, 1200)
                        return true
                    }
                    val dx = e.rawX - downX
                    if (kind == Kind.VOLUME && !dragging && chatSlot.contains(e.x, e.y)) {
                        performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                        toggleHistory()
                        return true
                    }
                    when {
                        kind == Kind.MESSAGE && (if (isRight) dx > dp(30) else dx < -dp(30)) -> retract()
                        kind == Kind.MESSAGE && kotlin.math.abs(dx) < dp(10) -> { val o = onOpen; retract(); o?.invoke() }
                        kind == Kind.REST -> {
                            performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                            when (settings.sideTap) {
                                "volume" -> {
                                    val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                                    volume(audio.getStreamVolume(AudioManager.STREAM_MUSIC) / max.toFloat())
                                }
                                "music" -> onPlayPause?.invoke()
                            }
                            return true
                        }
                        else -> {}
                    }
                    postDelayed(backToRest, 1800)
                }
            }
            return true
        }
    }
}
