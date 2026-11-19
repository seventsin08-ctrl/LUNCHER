package com.luncher.home

import android.app.Activity
import android.os.Build
import android.animation.ValueAnimator
import android.content.Context
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
    /** Taller window only while the volume shape (with recent apps) is open. */
    private val TALL_HALF = 250
    private fun halfH() = (lp?.height ?: dp(HALF * 2)) / 2

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

    private fun windowY() = (act.resources.displayMetrics.heightPixels * settings.sideY / 100f).toInt() - halfH()

    /** Show the resting bump (or remove everything if the side island is off). */
    fun rest() {
        if (!preview && (!settings.sideHud || !settings.sideRest)) { dismiss(); return }
        // Switched edges: rebuild the window on the other side.
        if (createdRight != null && createdRight != isRight) dismiss()
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
    }

    fun volume(level: Float) = show(Kind.VOLUME) {
        it.glideLevel(level)
        // Extension under the chat button: your recently used apps (scrollable).
        // Refresh at most every 3 s so holding the volume key stays smooth.
        val now = android.os.SystemClock.uptimeMillis()
        if (now - recentsAt > 3000) {
            recentsAt = now
            it.recents = if (settings.sideRecents && !preview) runCatching { recentProvider?.invoke() }.getOrNull().orEmpty().take(15) else emptyList()
            it.recentScroll = 0f
        }
    }
    private var recentsAt = 0L

    fun status(icons: List<Pair<Int, Int>>, label: String) = show(Kind.STATUS) {
        it.label = label
        it.icons = icons.map { (res, tint) -> act.getDrawable(res)!!.mutate().apply { setTint(tint) } }
    }

    /** A new message: app icon in the shape, sender and text beside it. */
    fun message(icon: Bitmap?, title: String, text: String, open: () -> Unit) = show(Kind.MESSAGE) {
        it.appIcon = icon; it.label = title; it.body = text
        onOpen = open
    }

    // ---------------------------------------------------------------- chat windows (one per app)

    /** One chat window. Each app (Messenger, Telegram, WhatsApp…) gets its own, open side by side. */
    private inner class Card(val pkg: String, val root: android.widget.LinearLayout, val lp: WindowManager.LayoutParams) {
        var holding = false
        val close = Runnable { if (!holding) hideCard(pkg) }
        fun restartTimer(ms: Long = 12_000) { root.removeCallbacks(close); root.postDelayed(close, ms) }
    }
    private val cards = LinkedHashMap<String, Card>()

    /**
     * A chat came in: the side island pops with the app icon and that app's chat window opens
     * (or updates). Other apps' windows stay open too, stacked under each other.
     */
    fun chat(icon: Bitmap?, appLabel: String, chat: ChatInfo, open: () -> Unit) {
        hideHistory()
        lastIcon = icon
        message(icon, chat.title, chat.messages.lastOrNull()?.second ?: "", open)
        view?.let { it.cardShown = settings.sideChatCard; it.invalidate() }
        if (settings.sideChatCard) showCard(icon, appLabel, chat, open)
    }

    private fun showCard(icon: Bitmap?, appLabel: String, chat: ChatInfo, open: () -> Unit) {
        val hudLp = lp ?: return
        val w = wm ?: return
        val ctx = act
        val m = ctx.resources.displayMetrics
        val (sw, sh) = settings.winSize("chat")
        val width = if (sw > 0) dp(sw).coerceAtMost(m.widthPixels - dp(16)) else dp(272)
        // Same app again: replace its window in the same spot.
        val old = cards[chat.pkg]
        val keepX = old?.lp?.x; val keepY = old?.lp?.y
        if (old != null) hideCard(chat.pkg, immediate = true)
        lateinit var me: Card
        val root = object : android.widget.LinearLayout(ctx) {
            // Touching the window keeps it open; letting go starts its timer again.
            override fun dispatchTouchEvent(e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> root2(this)?.root?.removeCallbacks(root2(this)?.close)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> root2(this)?.let { if (!it.holding) it.restartTimer(9000) }
                }
                return super.dispatchTouchEvent(e)
            }
        }.apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(12))
            background = cardBackground(dp(24).toFloat())
            elevation = dp(10).toFloat()
        }
        // Header (drag it to move the window): logo, app name, history, close
        val head = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        head.addView(roundIcon(icon, dp(20)), android.widget.LinearLayout.LayoutParams(dp(20), dp(20)))
        head.addView(android.widget.TextView(ctx).apply {
            text = "${appLabel.uppercase()} · ${ago(chat.time)}"; textSize = 11f; setTextColor(0x99FFFFFF.toInt())
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD); setPadding(dp(7), 0, 0, 0); maxLines = 1
        }, android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(circleButton("🕘") { showHistory() }, android.widget.LinearLayout.LayoutParams(dp(26), dp(26)).apply { marginEnd = dp(6) })
        head.addView(circleButton("✕") { hideCard(chat.pkg) }, android.widget.LinearLayout.LayoutParams(dp(26), dp(26)))
        root.addView(head)
        root.addView(android.widget.TextView(ctx).apply {
            text = chat.title; textSize = 15f; setTextColor(Color.WHITE); maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            typeface = Typeface.create("sans-serif", Typeface.BOLD); setPadding(0, dp(6), 0, dp(6))
            setOnClickListener { hideCard(chat.pkg); open() }
        })
        // Messages (scroll for more; newest at the bottom)
        val scroll = MaxScroll(ctx, if (sh > 0) dp(sh) else dp(170))
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
            }
            if (showNames && who.isNotBlank() && !mine) bubble.addView(android.widget.TextView(ctx).apply {
                text = who; textSize = 11f; setTextColor(0xB3FFFFFF.toInt())
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            })
            if (med != null) bubble.addView(attachmentChip(med, open))
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
        // Reply box (the app's own Reply, so it works for any chat app that has one)
        if (settings.sideReply && chat.reply != null) root.addView(replyBar(chat, list, scroll) { me },
            android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        val chatGrip = resizeGrip()
        root.addView(chatGrip, gripLp())

        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(m.heightPixels, View.MeasureSpec.AT_MOST))
        val h = root.measuredHeight
        // Where it goes: same spot when updating; beside the side island when it's the first;
        // otherwise under the last window (or above the first one if there's no room).
        val center = hudLp.y + hudLp.height / 2
        var y: Int; var x = dp(66)
        if (keepX != null && keepY != null) { x = keepX; y = keepY }
        else if (cards.isEmpty()) y = (center - h / 2).coerceAtLeast(dp(40))
        else {
            val last = cards.values.last()
            y = last.lp.y + last.root.height.coerceAtLeast(dp(120)) + dp(10)
            if (y + h > m.heightPixels - dp(50)) {
                val first = cards.values.first()
                y = first.lp.y - h - dp(10)
                if (y < dp(40)) { y = dp(60) + cards.size * dp(34); x = dp(66) + cards.size * dp(14) }
            }
        }
        val l = WindowManager.LayoutParams(width, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, hudLp.type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT).apply {
            gravity = (if (isRight) Gravity.END else Gravity.START) or Gravity.TOP
            this.x = x; this.y = y
            token = hudLp.token
        }
        if (runCatching { w.addView(root, l) }.isFailure) return
        me = Card(chat.pkg, root, l)
        cards[chat.pkg] = me
        cardOf[root] = me
        dragBy(head, root, l, onStart = { root.removeCallbacks(me.close) }, onEnd = { if (!me.holding) me.restartTimer(9000) })
        resizeBy(chatGrip, root, l, scroll, "chat", minW = dp(220))
        me.restartTimer()
        // Grows out of the side island (a soft slide when it's an extra window).
        root.pivotX = if (isRight) width.toFloat() else 0f
        root.pivotY = h / 2f
        root.alpha = 0f; root.scaleX = 0.5f; root.scaleY = 0.7f
        root.animate().alpha(1f).setDuration(180).start()
        Motion.scaleTo(root, 1f, Motion.BOUNCY)
    }

    private val cardOf = HashMap<View, Card>()

    /** A ScrollView whose height limit can change (window resizing). */
    private class MaxScroll(ctx: Context, var maxH: Int) : android.widget.ScrollView(ctx) {
        init { isVerticalScrollBarEnabled = true; isScrollbarFadingEnabled = true; overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS }
        override fun onMeasure(wSpec: Int, hSpec: Int) =
            super.onMeasure(wSpec, View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST))
    }

    /** The little corner grip at the bottom of a window: drag it to resize. */
    private fun resizeGrip(): View = object : View(act) {
        private val pnt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x80FFFFFF.toInt(); strokeWidth = dp(2).toFloat(); strokeCap = Paint.Cap.ROUND
        }
        override fun onDraw(c: Canvas) {
            // Pill in the middle + diagonal lines in the corner (like a window resize handle)
            val cx = width / 2f; val cy = height / 2f
            pnt.alpha = 70; c.drawLine(cx - dp(16), cy, cx + dp(16), cy, pnt)
            pnt.alpha = 140
            val r = width - dp(4).toFloat(); val b = height - dp(3).toFloat()
            c.drawLine(r - dp(12), b, r, b - dp(12), pnt)
            c.drawLine(r - dp(6), b, r, b - dp(6), pnt)
        }
    }.apply { contentDescription = "Resize window" }

    private fun gripLp() = android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, dp(18)).apply {
        topMargin = dp(4)
    }

    /** Drag the grip: right/left = wider/narrower, down/up = taller/shorter. The size is remembered. */
    private fun resizeBy(grip: View, win: View, l: WindowManager.LayoutParams, scroll: MaxScroll, key: String, minW: Int) {
        val m = act.resources.displayMetrics
        var sx = 0f; var sy = 0f; var sw = 0; var sh = 0; var ox = 0; var oy = 0; var resizing = false
        grip.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    sx = e.rawX; sy = e.rawY; sw = win.width; sh = scroll.maxH; ox = l.x; oy = l.y; resizing = false
                    grip.parent?.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - sx).toInt(); val dy = (e.rawY - sy).toInt()
                    if (!resizing && kotlin.math.abs(dx) + kotlin.math.abs(dy) > dp(4)) {
                        resizing = true; grip.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    }
                    if (resizing) {
                        val nw = (sw + dx).coerceIn(minW, m.widthPixels - dp(16))
                        val nh = (sh + dy).coerceIn(dp(70), (m.heightPixels * 0.8f).toInt())
                        val gw = nw - sw; val gh = nh - sh
                        l.width = nw
                        // Keep the top-left corner where it is, whatever the window is anchored to.
                        val hg = l.gravity and Gravity.HORIZONTAL_GRAVITY_MASK
                        l.x = when {
                            hg == Gravity.CENTER_HORIZONTAL -> ox + gw / 2
                            (l.gravity and Gravity.END) == Gravity.END -> ox - gw
                            else -> ox
                        }
                        if ((l.gravity and Gravity.VERTICAL_GRAVITY_MASK) == Gravity.CENTER_VERTICAL) l.y = oy + gh / 2
                        scroll.maxH = nh; scroll.requestLayout()
                        runCatching { wm?.updateViewLayout(win, l) }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (resizing) {
                    val d = m.density
                    settings.setWinSize(key, (l.width / d).toInt(), (scroll.maxH / d).toInt())
                    grip.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                }
            }
            true
        }
    }
    private fun root2(v: View) = cardOf[v]

    private fun cardBackground(r: Float) = android.graphics.drawable.GradientDrawable().apply {
        cornerRadius = r
        if (settings.sideColor == "glass") {
            colors = intArrayOf(Color.argb(215, 52, 52, 58), Color.argb(225, 24, 24, 28))
            orientation = android.graphics.drawable.GradientDrawable.Orientation.TL_BR
            setStroke(maxOf(1, dp(1)), 0x55FFFFFF)
        } else { setColor(Color.argb(238, 20, 20, 22)); setStroke(maxOf(1, dp(1)), Color.argb(45, 255, 255, 255)) }
    }

    private fun roundIcon(bmp: Bitmap?, size: Int) = android.widget.ImageView(act).apply {
        if (bmp != null) setImageBitmap(bmp) else { setImageResource(R.drawable.ic_chat); setColorFilter(Color.WHITE) }
        outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) =
                outline.setRoundRect(0, 0, view.width, view.height, view.width * 0.26f)
        }
        clipToOutline = true
    }

    private fun circleButton(t: String, fn: () -> Unit) = android.widget.TextView(act).apply {
        text = t; textSize = 12f; setTextColor(0xEEFFFFFF.toInt()); gravity = Gravity.CENTER
        background = android.graphics.drawable.GradientDrawable().apply {
            setShape(android.graphics.drawable.GradientDrawable.OVAL); setColor(0x33FFFFFF)
        }
        setOnClickListener { it.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK); fn() }
    }

    /** Photo / video / voice / file: Download it (when the app shared it), otherwise open it in the app. */
    private fun attachmentChip(med: ChatMedia, open: () -> Unit) = android.widget.TextView(act).apply {
        val canSave = (med.image != null || med.uri != null) && Build.VERSION.SDK_INT >= 29
        text = if (canSave) "${med.emoji}  ${med.label}   ⬇ Download" else "${med.emoji}  ${med.label} · Open in app"
        textSize = 13f; setTextColor(Color.WHITE)
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        setPadding(dp(10), dp(7), dp(10), dp(7))
        background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(0x33FFFFFF) }
        setOnClickListener {
            if (canSave) {
                val ok = download(med)
                if (ok) { text = "${med.emoji}  ${med.label}   ✓ Saved"; performHapticFeedback(HapticFeedbackConstants.CONFIRM) }
                android.widget.Toast.makeText(act, if (ok) "Saved to Downloads › Apple Launcher" else "Couldn't save — open it in the app",
                    android.widget.Toast.LENGTH_SHORT).show()
            } else open()
        }
    }

    /** Save an attachment to Downloads / Apple Launcher. */
    private fun download(med: ChatMedia): Boolean = runCatching {
        if (Build.VERSION.SDK_INT < 29) return false
        val r = act.contentResolver
        val mime = when {
            med.mime.startsWith("image/") -> "image/jpeg"
            med.mime.endsWith("/*") -> med.mime.replace("*", "mp4").replace("audio/mp4", "audio/mp4")
            else -> med.mime
        }
        val ext = when { mime.startsWith("image/") -> "jpg"; mime.startsWith("video/") -> "mp4"; mime.startsWith("audio/") -> "m4a"
            mime.contains("pdf") -> "pdf"; else -> "bin" }
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "AppleLauncher_${System.currentTimeMillis()}.$ext")
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mime)
            put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS + "/Apple Launcher")
        }
        val out = r.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
        var done = false
        r.openOutputStream(out)?.use { os ->
            val u = med.uri
            if (u != null) done = runCatching { r.openInputStream(u)!!.use { it.copyTo(os) }; true }.getOrDefault(false)
            if (!done) med.image?.let { done = it.compress(Bitmap.CompressFormat.JPEG, 95, os) }
        }
        if (!done) r.delete(out, null, null)
        done
    }.getOrDefault(false)

    private fun replyBar(chat: ChatInfo, list: android.widget.LinearLayout, scroll: android.widget.ScrollView, card: () -> Card): View {
        val ctx = act
        val bar = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(0x26FFFFFF) }
            setPadding(dp(12), 0, dp(4), 0)
        }
        val input = android.widget.EditText(ctx).apply {
            hint = "Reply…"; setHintTextColor(0x80FFFFFF.toInt()); setTextColor(Color.WHITE); textSize = 14f
            background = null; maxLines = 3; setPadding(0, dp(8), 0, dp(8))
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEND
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        val send = android.widget.TextView(ctx).apply {
            text = "↑"; textSize = 18f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            background = android.graphics.drawable.GradientDrawable().apply {
                setShape(android.graphics.drawable.GradientDrawable.OVAL); setColor(0xFF0A84FF.toInt())
            }
        }
        fun doSend() {
            val t = input.text.toString().trim()
            if (t.isEmpty()) return
            if (chat.sendReply(act, t)) {
                list.addView(android.widget.TextView(ctx).apply {
                    text = t; textSize = 14f; setTextColor(Color.WHITE); setPadding(dp(11), dp(7), dp(11), dp(8))
                    background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(0xFF0A84FF.toInt()) }
                }, android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(5); gravity = Gravity.END })
                input.setText("")
                scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
                bar.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                val c = card(); c.holding = false; c.restartTimer(10_000)
            } else android.widget.Toast.makeText(act, "This app doesn't allow replies here", android.widget.Toast.LENGTH_SHORT).show()
        }
        send.setOnClickListener { doSend() }
        input.setOnEditorActionListener { _, _, _ -> doSend(); true }
        input.setOnTouchListener { v, e -> if (e.actionMasked == MotionEvent.ACTION_DOWN) startTyping(card(), v as android.widget.EditText); false }
        bar.addView(input, android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(send, android.widget.LinearLayout.LayoutParams(dp(32), dp(32)))
        return bar
    }

    /** Typing needs the keyboard: let that one window take focus only while you reply. */
    private fun startTyping(c: Card, input: android.widget.EditText) {
        c.holding = true
        c.root.removeCallbacks(c.close)
        val l = c.lp
        if (l.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0) {
            l.flags = l.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            l.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
            runCatching { wm?.updateViewLayout(c.root, l) }
        }
        input.post {
            input.requestFocus()
            act.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                ?.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
    }

    /**
     * Every window (chat windows, Message History) can be moved: hold its top bar and drag.
     */
    private fun dragBy(handle: View, win: View, l: WindowManager.LayoutParams, onStart: (() -> Unit)? = null, onEnd: (() -> Unit)? = null) {
        var sx = 0f; var sy = 0f; var ox = 0; var oy = 0; var moved = false
        handle.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ox = l.x; oy = l.y; moved = false; onStart?.invoke() }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - sx; val dy = e.rawY - sy
                    if (!moved && kotlin.math.abs(dx) + kotlin.math.abs(dy) > dp(6)) {
                        moved = true
                        win.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        win.animate().scaleX(1.03f).scaleY(1.03f).setDuration(120).start()
                    }
                    if (moved) {
                        val fromEnd = (l.gravity and Gravity.END) == Gravity.END
                        l.x = ox + (if (fromEnd) -dx else dx).toInt()
                        l.y = oy + dy.toInt()
                        runCatching { wm?.updateViewLayout(win, l) }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (moved) win.animate().scaleX(1f).scaleY(1f).setDuration(160).start()
                    onEnd?.invoke()
                }
            }
            true
        }
    }

    private fun hideCard(pkg: String, immediate: Boolean = false) {
        val c = cards.remove(pkg) ?: return
        cardOf.remove(c.root)
        c.root.removeCallbacks(c.close)
        if (cards.isEmpty()) view?.let { it.cardShown = false; it.invalidate() }
        if (immediate) { runCatching { wm?.removeViewImmediate(c.root) }; return }
        c.root.animate().alpha(0f).scaleX(0.6f).scaleY(0.8f).setDuration(180)
            .withEndAction { runCatching { wm?.removeViewImmediate(c.root) } }.start()
    }

    private fun hideAllCards(immediate: Boolean = false) { for (k in cards.keys.toList()) hideCard(k, immediate) }

    private fun show(kind: Kind, fill: (HudView) -> Unit) {
        val v = view ?: create() ?: return
        val wasRest = v.kind == Kind.REST
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
        // Grow/shrink the height around the same centre (taller only for volume + recent apps).
        val newH = dp(if (kind == Kind.VOLUME && v.recents.isNotEmpty()) TALL_HALF * 2 else HALF * 2)
        if (newH != l.height) { val c = l.y + l.height / 2; l.height = newH; l.y = c - newH / 2 }
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
        recentsAt = 0L
        return v
    }

    /** Shrink back to the resting bump (or away if resting is off). */
    private fun retract() {
        // Chat windows have their own timers; only the side island shape goes back to rest.
        val v = view ?: return
        onOpen = null
        v.growTo(0f) {
            if (view !== v) return@growTo
            if (settings.sideHud && settings.sideRest) { v.kind = Kind.REST; size(Kind.REST); v.growTo(1f) }
            else if (cards.isEmpty() && histPanel == null) dismiss()
        }
    }

    /** Demo for the Settings preview: grow out with the volume slider, then rest. */
    fun demo() {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        rest()
        view?.postDelayed({ volume(audio.getStreamVolume(AudioManager.STREAM_MUSIC) / max.toFloat()) }, 120)
    }

    // ---------------------------------------------------------------- message history (middle window)

    /** Recently used apps for the side island's extension: (icon, open). */
    var recentProvider: (() -> List<Pair<Bitmap?, () -> Unit>>)? = null

    /** Set by the launcher. */
    var historyProvider: (() -> List<ChatInfo>)? = null
    var appLookup: ((String) -> Pair<Bitmap?, String>?)? = null
    var openChat: ((ChatInfo) -> Unit)? = null

    private var lastIcon: Bitmap? = null
    private var unread = 0
    private var histPanel: View? = null

    /** Called for every new message so the chat button's red number stays right. */
    fun countNew() { unread++; view?.invalidate() }

    /** Open Message History from anywhere (e.g. Settings). */
    fun openHistory() { if (view == null) rest(); showHistory() }

    private fun toggleHistory() {
        if (histPanel != null) hideHistory()
        else { view?.removeCallbacks(backToRest); showHistory() }
    }

    /**
     * Message History in the middle of the screen: each app's logo with its conversations
     * listed under it (scroll for more), and how many apps at the bottom. Tap a conversation
     * to open its chat window. Drag the top bar to move it.
     */
    private fun showHistory() {
        hideHistory()
        val hudLp = lp ?: return
        val w = wm ?: return
        val ctx = act
        val m = ctx.resources.displayMetrics
        unread = 0
        view?.invalidate()
        val chats = historyProvider?.invoke().orEmpty()
        // Group by app, newest app first; newest conversation first inside each.
        val groups = chats.groupBy { it.pkg }.entries.sortedByDescending { e -> e.value.maxOf { it.time } }
        val (sw, sh) = settings.winSize("hist")
        val width = if (sw > 0) dp(sw).coerceAtMost(m.widthPixels - dp(16)) else minOf((m.widthPixels * 0.88f).toInt(), dp(380))
        val root = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = cardBackground(dp(28).toFloat())
            elevation = dp(14).toFloat()
        }
        val head = android.widget.LinearLayout(ctx).apply { orientation = android.widget.LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(4), 0, dp(8)) }
        head.addView(android.widget.TextView(ctx).apply {
            text = "Message History"; textSize = 19f; setTextColor(Color.WHITE); typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }, android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(circleButton("✕") { hideHistory() }, android.widget.LinearLayout.LayoutParams(dp(30), dp(30)))
        root.addView(head)
        // Scrollable list (drag the corner grip to make the window bigger or smaller)
        val scroll = MaxScroll(ctx, if (sh > 0) dp(sh) else (m.heightPixels * 0.55f).toInt())
        val list = android.widget.LinearLayout(ctx).apply { orientation = android.widget.LinearLayout.VERTICAL }
        if (groups.isEmpty()) list.addView(android.widget.TextView(ctx).apply {
            text = "No messages yet"; textSize = 14f; setTextColor(0x99FFFFFF.toInt()); setPadding(0, dp(16), 0, dp(16)); gravity = Gravity.CENTER
        })
        for ((pkg, convs) in groups) {
            val app = appLookup?.invoke(pkg)
            // App row: logo, name, how many chats
            val appRow = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(10), 0, dp(4))
            }
            appRow.addView(roundIcon(app?.first, dp(34)), android.widget.LinearLayout.LayoutParams(dp(34), dp(34)))
            appRow.addView(android.widget.TextView(ctx).apply {
                text = app?.second ?: pkg; textSize = 16f; setTextColor(Color.WHITE)
                typeface = Typeface.create("sans-serif", Typeface.BOLD); setPadding(dp(10), 0, 0, 0)
            }, android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            appRow.addView(android.widget.TextView(ctx).apply {
                text = "${convs.size}"; textSize = 12f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setPadding(dp(8), dp(2), dp(8), dp(2))
                background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(0x33FFFFFF) }
            })
            list.addView(appRow)
            // That app's conversations, under its logo
            for (c in convs.sortedByDescending { it.time }) {
                val row = android.widget.LinearLayout(ctx).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(44), dp(8), dp(4), dp(8))
                    background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x33FFFFFF), null,
                        android.graphics.drawable.ColorDrawable(Color.WHITE))
                    setOnClickListener {
                        hideHistory()
                        chat(app?.first, app?.second ?: "Messages", c) { openChat?.invoke(c) }
                    }
                }
                val texts = android.widget.LinearLayout(ctx).apply { orientation = android.widget.LinearLayout.VERTICAL }
                texts.addView(android.widget.TextView(ctx).apply {
                    text = c.title; textSize = 14f; setTextColor(Color.WHITE); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                })
                val last = c.messages.lastOrNull()
                val lm = c.media.lastOrNull()
                texts.addView(android.widget.TextView(ctx).apply {
                    text = when { !last?.second.isNullOrBlank() -> last!!.second; lm != null -> "${lm.emoji} ${lm.label}"; else -> "" }
                    textSize = 12.5f; setTextColor(0x99FFFFFF.toInt()); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                })
                row.addView(texts, android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(android.widget.TextView(ctx).apply { text = ago(c.time); textSize = 11f; setTextColor(0x80FFFFFF.toInt()); setPadding(dp(6), 0, 0, 0) })
                list.addView(row)
            }
            list.addView(View(ctx).apply { setBackgroundColor(0x22FFFFFF) },
                android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, maxOf(1, dp(1) / 2)).apply { topMargin = dp(6) })
        }
        scroll.addView(list)
        root.addView(scroll, android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT))
        // Counter: how many apps are in the history
        root.addView(android.widget.TextView(ctx).apply {
            val n = groups.size
            text = if (n == 1) "1 app" else "$n apps"; textSize = 12f; setTextColor(0x99FFFFFF.toInt()); gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, 0)
        }, android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT))
        val histGrip = resizeGrip()
        root.addView(histGrip, gripLp())

        val l = WindowManager.LayoutParams(width, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, hudLp.type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.CENTER
            token = hudLp.token
        }
        if (runCatching { w.addView(root, l) }.isFailure) return
        histPanel = root
        dragBy(head, root, l)
        resizeBy(histGrip, root, l, scroll, "hist", minW = dp(240))
        root.alpha = 0f; root.scaleX = 0.85f; root.scaleY = 0.85f
        root.animate().alpha(1f).setDuration(200).start()
        Motion.scaleTo(root, 1f, Motion.BOUNCY)
        root.postDelayed({ if (histPanel === root) hideHistory() }, 90_000)
    }

    private fun hideHistory() {
        val p = histPanel ?: return
        histPanel = null
        view?.let { v -> if (v.kind == Kind.VOLUME) { v.removeCallbacks(backToRest); v.postDelayed(backToRest, 2200) } }
        p.animate().alpha(0f).scaleX(0.9f).scaleY(0.9f).setDuration(170).withEndAction { runCatching { wm?.removeViewImmediate(p) } }.start()
    }

    private fun ago(t: Long): String {
        val m = ((System.currentTimeMillis() - t) / 60000).coerceAtLeast(0)
        return when { m < 1 -> "now"; m < 60 -> "${m}m"; m < 1440 -> "${m / 60}h"; else -> "${m / 1440}d" }
    }

    fun dismiss() {
        runCatching { histPanel?.let { wm?.removeViewImmediate(it) } }; histPanel = null
        hideAllCards(immediate = true)
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

        // ---- recently used apps (scrollable column under the chat button)
        var recents: List<Pair<Bitmap?, () -> Unit>> = emptyList()
        var recentScroll = 0f
        var recentArea = RectF()
        private val iconS get() = dp(when (settings.sideRecentsSize) { "s" -> 28; "l" -> 40; else -> 34 }).toFloat()
        private val rowH get() = iconS + dp(8)
        private val divider = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF; strokeWidth = maxOf(1f, dp(1) * 0.8f) }

        fun recentsHeight(): Float = if (recents.isEmpty()) 0f
            else minOf(recents.size, settings.sideRecentsShown.coerceIn(1, 5)) * rowH + dp(12)
        private fun maxScroll() = (recents.size * rowH + dp(12) - recentsHeight()).coerceAtLeast(0f)

        fun drawRecents(c: Canvas, bodyW: Float, areaTop: Float, areaH: Float, a: Float) {
            val k = ((a - 0.25f) / 0.75f).coerceIn(0f, 1f)
            recentArea.set(mx(0f, bodyW), areaTop, mx(0f, bodyW) + bodyW, areaTop + areaH)
            divider.alpha = (70 * k).toInt()
            c.drawLine(mx(dp(12).toFloat()), areaTop + 1, mx(bodyW - dp(12)), areaTop + 1, divider)
            c.save()
            c.clipRect(recentArea)
            val s = iconS
            val cx = mx(bodyW / 2f)
            val pp = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
            for ((i, item) in recents.withIndex()) {
                val cy = areaTop + dp(6) + rowH * i + rowH / 2 - recentScroll
                if (cy < areaTop - rowH || cy > areaTop + areaH + rowH) continue
                // Icons fade near the top / bottom edge of the scroll area.
                val edge = minOf(cy - areaTop, areaTop + areaH - cy).coerceAtLeast(0f)
                pp.alpha = (255 * k * (edge / dp(18)).coerceIn(0.25f, 1f)).toInt()
                val bmp = item.first ?: continue
                clip.reset(); clip.addRoundRect(cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2, s * 0.26f, s * 0.26f, Path.Direction.CW)
                c.save(); c.clipPath(clip)
                r.set(cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2); c.drawBitmap(bmp, null, r, pp)
                c.restore()
            }
            c.restore()
        }

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
                    val chatH = if (chat) dp(60).toFloat() else 0f
                    val recH = recentsHeight()
                    val h = sliderH + chatH + recH
                    val top = (height - h) / 2f; val bottom = top + sliderH
                    shape(bodyW, top, top + h); flip(); drawBody(c)
                    if (chat && a > 0f) drawChatSlot(c, bodyW, top + sliderH, a)
                    if (recH > 0f && a > 0f) drawRecents(c, bodyW, top + sliderH + chatH, recH, a)
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
        private var inRecents = false
        private var startScroll = 0f
        /** Long-press: pick the side island up and drag it up or down the edge. */
        private val startMove = Runnable {
            if (!dragging) { moving = true; performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) }
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    inRecents = kind == Kind.VOLUME && recents.isNotEmpty() && recentArea.contains(e.x, e.y)
                    startScroll = recentScroll
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
                } else if (inRecents) {
                    val dy = downY - e.rawY
                    if (kotlin.math.abs(dy) > dp(6)) { dragging = true; removeCallbacks(startMove) }
                    if (dragging) {
                        val before = (recentScroll / rowH).toInt()
                        recentScroll = (startScroll + dy).coerceIn(0f, maxScroll())
                        if ((recentScroll / rowH).toInt() != before) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        invalidate()
                    }
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
                        settings.sideY = (((lp?.y ?: 0) + halfH()) / h * 100f).roundToInt().coerceIn(5, 90)
                        onMoved?.invoke()
                        performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                        postDelayed(backToRest, 1200)
                        return true
                    }
                    val dx = e.rawX - downX
                    if (inRecents && !dragging) {
                        // Tap an app: jump straight to it.
                        val i = ((e.y - recentArea.top - dp(6) + recentScroll) / rowH).toInt()
                        recents.getOrNull(i)?.let { item ->
                            performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                            item.second()
                            retract()
                            return true
                        }
                    }
                    if (inRecents) { postDelayed(backToRest, 4000); return true }
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
