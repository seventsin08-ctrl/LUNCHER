package com.luncher.home

import android.Manifest
import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Shader
import android.graphics.Typeface
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * "Voice activation": listens for the phrase, then shows a holographic orb at the bottom of the
 * screen (with a chime) and opens whatever app you name — "open Messenger", "open Spotify"…
 *
 * To save battery it only wakes Android's speech recogniser when it hears someone talking.
 */
class VoiceService : Service() {
    companion object {
        private const val CHANNEL = "voice"
        private const val ID = 7302
        @Volatile var running = false

        fun hasMic(ctx: Context) = ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        /** Start or stop listening to match the setting. Call while the launcher is on screen. */
        fun sync(ctx: Context) {
            val s = Settings(ctx)
            val want = s.voiceOn && hasMic(ctx) && SpeechRecognizer.isRecognitionAvailable(ctx)
            if (want) runCatching { ctx.startForegroundService(Intent(ctx, VoiceService::class.java)) }
            else runCatching { ctx.stopService(Intent(ctx, VoiceService::class.java)) }
        }

        private val WAKE = listOf("voice activation", "voice activate", "voice activated", "voice activations")
    }

    private val main = Handler(Looper.getMainLooper())
    private var recorder: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile private var listening = false
    private var recognizer: SpeechRecognizer? = null
    private var busy = false
    private var overlay: VoiceOverlay? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        goForeground()
        startWatching()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        running = false
        stopWatching()
        runCatching { recognizer?.destroy() }
        overlay?.close()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ 1. hear someone talking

    /** Cheap loudness check on the mic; when it sounds like speech, hand over to the recogniser. */
    private fun startWatching() {
        if (listening || busy) return
        if (!hasMic(this)) { stopSelf(); return }
        val rate = 16000
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = runCatching {
            @Suppress("MissingPermission")
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(min, rate / 5) * 2)
        }.getOrNull() ?: return
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); main.postDelayed({ startWatching() }, 3000); return }
        recorder = rec
        listening = true
        rec.startRecording()
        thread = Thread {
            val buf = ShortArray(rate / 20) // 50 ms
            var loud = 0
            var noise = 400.0
            while (listening) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) continue
                var sum = 0.0
                for (i in 0 until n) sum += buf[i].toDouble() * buf[i]
                val rms = sqrt(sum / n)
                // Follow the room's background noise so a quiet room and a busy one both work.
                noise = noise * 0.97 + minOf(rms, noise * 1.5) * 0.03
                if (rms > maxOf(900.0, noise * 3.2)) loud++ else loud = maxOf(0, loud - 1)
                if (loud >= 3) {
                    listening = false
                    main.post { stopWatching(); recognize(command = false) }
                }
            }
        }.apply { isDaemon = true; start() }
    }

    private fun stopWatching() {
        listening = false
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        recorder = null
    }

    // ------------------------------------------------------------------ 2. understand it

    private fun recognize(command: Boolean) {
        busy = true
        val r = recognizer ?: (if (Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(this))
            SpeechRecognizer.createOnDeviceSpeechRecognizer(this) else SpeechRecognizer.createSpeechRecognizer(this))
            .also { recognizer = it }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { if (command) overlay?.status("Listening…") }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) { overlay?.level((rmsdB + 2f) / 12f) }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onPartialResults(partial: Bundle?) {
                val t = partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
                if (command) overlay?.heard(t)
            }
            override fun onError(error: Int) = done(emptyList(), command)
            override fun onResults(results: Bundle?) =
                done(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty(), command)
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        runCatching { r.startListening(i) }.onFailure { done(emptyList(), command) }
    }

    private fun done(results: List<String>, command: Boolean) {
        val all = results.map { it.lowercase() }
        if (!command) {
            val woke = all.any { t -> WAKE.any { t.contains(it) } }
            if (!woke) { busy = false; main.postDelayed({ startWatching() }, 250); return }
            // Said it all in one go? "voice activation open messenger"
            val rest = all.firstNotNullOfOrNull { t -> WAKE.firstOrNull { t.contains(it) }?.let { w -> t.substringAfter(w).trim() } }
            // Hand over to the assistant screen (timers, messages, calls, notes, report…),
            // then go back to waiting for the wake word once it closes.
            AssistantActivity.open(this, rest?.takeIf { it.length > 2 })
            waitForAssistant()
            return
        }
        handle(all)
    }

    private fun waitForAssistant() {
        main.postDelayed({
            if (AssistantActivity.active) waitForAssistant()
            else { busy = false; main.postDelayed({ startWatching() }, 400) }
        }, 1200)
    }

    // ------------------------------------------------------------------ 3. do it

    private fun handle(all: List<String>) {
        val target = all.firstNotNullOfOrNull { t ->
            val m = Regex("(?:open|launch|start|go to)\\s+(.+)").find(t) ?: return@firstNotNullOfOrNull null
            m.groupValues[1].trim().removeSuffix(" app").removePrefix("the ")
        }
        if (target == null) {
            overlay?.status(if (all.isEmpty()) "Didn't catch that" else "Say “open” and an app name")
            finishSoon(1600)
            return
        }
        val app = findApp(target)
        if (app == null) {
            overlay?.status("Couldn't find “$target”")
            finishSoon(1800)
            return
        }
        overlay?.status("Opening ${app.second}")
        main.postDelayed({
            runCatching {
                startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(app.first)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
            }
            finishSoon(150)
        }, 450)
    }

    /** Best match for a spoken app name (handles "messenger", "face book", small mishearings). */
    private fun findApp(spoken: String): Pair<android.content.ComponentName, String>? {
        val pm = packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { android.content.ComponentName(it.activityInfo.packageName, it.activityInfo.name) to it.loadLabel(pm).toString() }
            .filter { it.first.packageName != packageName }
        fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9]"), "")
        val q = norm(spoken)
        if (q.isEmpty()) return null
        apps.firstOrNull { norm(it.second) == q }?.let { return it }
        apps.firstOrNull { norm(it.second).startsWith(q) || q.startsWith(norm(it.second)) }?.let { return it }
        apps.firstOrNull { norm(it.second).contains(q) }?.let { return it }
        return apps.map { it to distance(norm(it.second), q) }.filter { it.second <= maxOf(1, q.length / 4) }
            .minByOrNull { it.second }?.first
    }

    private fun distance(a: String, b: String): Int {
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length)
            d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
        return d[a.length][b.length]
    }

    private fun showOverlay() {
        overlay?.close()
        overlay = VoiceOverlay(this) { finishSoon(0) }.also { it.show() }
        playChime()
    }

    private fun finishSoon(ms: Long) {
        main.postDelayed({
            overlay?.close(); overlay = null
            busy = false
            main.postDelayed({ startWatching() }, 400)
        }, ms)
    }

    /** A soft two-note chime, made on the fly (no sound file needed). */
    private fun playChime() {
        Thread {
            runCatching {
                val rate = 44100
                val notes = listOf(880.0 to 0.16, 1318.5 to 0.32)
                val total = notes.sumOf { (it.second * rate).toInt() }
                val pcm = ShortArray(total)
                var o = 0
                for ((f, dur) in notes) {
                    val n = (dur * rate).toInt()
                    for (i in 0 until n) {
                        val t = i / rate.toDouble()
                        val env = minOf(1.0, i / (rate * 0.01)) * Math.exp(-t * 7.0)
                        val v = (sin(2 * PI * f * t) * 0.6 + sin(2 * PI * f * 2 * t) * 0.15) * env
                        pcm[o + i] = (v * 0.5 * Short.MAX_VALUE).toInt().toShort()
                    }
                    o += n
                }
                val track = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(pcm.size * 2).setTransferMode(AudioTrack.MODE_STATIC).build()
                track.write(pcm, 0, pcm.size)
                track.play()
                Thread.sleep(800)
                track.release()
            }
        }.start()
    }

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Voice activation", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Listening for “voice activation”"; setShowBadge(false)
            })
        }
        val open = PendingIntent.getActivity(this, 1,
            Intent(this, SettingsActivity::class.java).putExtra("page", "general").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_cc_mic)
            .setContentTitle("Voice activation is on")
            .setContentText("Say “voice activation”, then “open” and an app")
            .setContentIntent(open).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 30) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(ID, n)
    }
}

/**
 * The listening screen: the display fades to black from the bottom and a holographic orb
 * (spinning 3D rings, ticks and sparks, like a sci-fi interface) floats in a small panel.
 */
class VoiceOverlay(private val ctx: Context, private val onCancel: () -> Unit) {
    private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).roundToInt()
    private var wm: WindowManager? = null
    private var root: FrameLayout? = null
    private val orb = OrbView(ctx)
    private val title = TextView(ctx)
    private val sub = TextView(ctx)

    fun show() {
        val svc = LuncherAccessibility.instance
        val type = when {
            svc != null -> WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            android.provider.Settings.canDrawOverlays(ctx) -> WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else -> return
        }
        val w = (svc ?: ctx).getSystemService(WindowManager::class.java) ?: return
        val r = FrameLayout(ctx).apply {
            // Black fading up from the bottom.
            background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(0xF2000000.toInt(), 0xCC000000.toInt(), 0x00000000))
            setOnTouchListener { _, e -> if (e.actionMasked == MotionEvent.ACTION_UP) onCancel(); true }
        }
        val panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16), dp(10), dp(16), dp(28))
        }
        panel.addView(orb, LinearLayout.LayoutParams(dp(230), dp(230)))
        title.apply { text = "Listening…"; textSize = 20f; setTextColor(Color.WHITE); typeface = Typeface.create("sans-serif-medium", Typeface.BOLD); gravity = Gravity.CENTER }
        sub.apply { text = "Say “open” and an app — like “open Messenger”"; textSize = 14f; setTextColor(0x99FFFFFF.toInt()); gravity = Gravity.CENTER }
        panel.addView(title, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(4) })
        panel.addView(sub, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(6) })
        r.addView(panel, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
        val lp = WindowManager.LayoutParams(MATCH_PARENT, (ctx.resources.displayMetrics.heightPixels * 0.62f).toInt(), type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.BOTTOM }
        if (runCatching { w.addView(r, lp) }.isFailure) return
        wm = w; root = r
        r.alpha = 0f
        r.animate().alpha(1f).setDuration(350).start()
        panel.translationY = dp(80).toFloat()
        panel.animate().translationY(0f).setDuration(520).setInterpolator(android.view.animation.PathInterpolator(0.2f, 0.9f, 0.1f, 1f)).start()
        orb.scaleX = 0.4f; orb.scaleY = 0.4f
        orb.animate().scaleX(1f).scaleY(1f).setDuration(700).setInterpolator(android.view.animation.OvershootInterpolator(1.2f)).start()
        // Never stays stuck on screen.
        r.postDelayed({ if (root === r) onCancel() }, 12_000)
    }

    fun status(t: String) { title.text = t }
    fun heard(t: String) { sub.text = "“$t”" }
    fun level(v: Float) { orb.level = v.coerceIn(0f, 1f) }

    fun close() {
        val r = root ?: return
        root = null
        r.animate().alpha(0f).setDuration(260).withEndAction { runCatching { wm?.removeViewImmediate(r) } }.start()
        r.postDelayed({ runCatching { wm?.removeViewImmediate(r) } }, 600)
    }
}

/** Holographic orb: tilted 3D rings with tick marks, an inner core and radial sparks, all spinning. */
class OrbView(ctx: Context) : View(ctx) {
    var level = 0f
    private var t = 0f
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1000; repeatCount = ValueAnimator.INFINITE
        addUpdateListener { t += 0.016f; invalidate() }
    }
    private class Ring(val r: Float, val tiltX: Float, val tiltY: Float, val speed: Float, val color: Int, val dashes: Int)
    private val rings = listOf(
        Ring(1.00f, 70f, 10f, 0.35f, 0xFFFF9F2E.toInt(), 0),
        Ring(0.92f, 20f, 60f, -0.5f, 0xFF3FC8FF.toInt(), 48),
        Ring(0.84f, 55f, -35f, 0.7f, 0xFFFFB347.toInt(), 0),
        Ring(0.74f, -30f, 75f, -0.8f, 0xFF5AE0FF.toInt(), 36),
        Ring(0.62f, 80f, 40f, 1.1f, 0xFFFF8A1E.toInt(), 0),
        Ring(0.50f, 10f, -70f, -1.3f, 0xFF8EEBFF.toInt(), 24),
        Ring(0.34f, 45f, 25f, 1.6f, 0xFFFFD27A.toInt(), 0)
    )

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
        if (Build.VERSION.SDK_INT >= 29) { ringPaint.blendMode = BlendMode.PLUS; dotPaint.blendMode = BlendMode.PLUS }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); anim.start() }
    override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }

    /** Rotate a point on a unit circle in 3D and project it with a little perspective. */
    private fun project(a: Float, ring: Ring, out: FloatArray, cx: Float, cy: Float, rad: Float) {
        val spin = t * ring.speed
        var x = cos(a + spin) * ring.r
        var y = sin(a + spin) * ring.r
        var z = 0f
        val ax = Math.toRadians((ring.tiltX + t * 9f).toDouble()).toFloat()
        val ay = Math.toRadians((ring.tiltY + t * 13f).toDouble()).toFloat()
        val y1 = y * cos(ax) - z * sin(ax); val z1 = y * sin(ax) + z * cos(ax); y = y1; z = z1
        val x2 = x * cos(ay) + z * sin(ay); val z2 = -x * sin(ay) + z * cos(ay); x = x2; z = z2
        val p = 1f / (1.9f - z * 0.45f)
        out[0] = cx + x * rad * p * 1.9f
        out[1] = cy + y * rad * p * 1.9f
        out[2] = z
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val pulse = 1f + level * 0.12f + sin(t * 2.2f) * 0.015f
        val rad = minOf(width, height) * 0.42f * pulse
        val pt = FloatArray(3)
        // Radial sparks (data streaks) around the orb.
        for (i in 0 until 64) {
            val a = i / 64f * 2 * PI.toFloat() + t * 0.15f
            val inner = rad * (1.08f + 0.06f * sin(t * 3 + i))
            val len = rad * (0.06f + 0.10f * ((sin(t * 2.6f + i * 1.7f) + 1f) / 2f) + level * 0.12f)
            dotPaint.color = if (i % 3 == 0) 0x6654D6FF else 0x55FFA040
            dotPaint.strokeWidth = 2.2f
            c.drawLine(cx + cos(a) * inner, cy + sin(a) * inner, cx + cos(a) * (inner + len), cy + sin(a) * (inner + len), dotPaint)
        }
        // Rings
        for (ring in rings) {
            path.reset()
            val steps = 96
            for (k in 0..steps) {
                project(k / steps.toFloat() * 2 * PI.toFloat(), ring, pt, cx, cy, rad)
                if (k == 0) path.moveTo(pt[0], pt[1]) else path.lineTo(pt[0], pt[1])
            }
            ringPaint.color = ring.color
            ringPaint.alpha = 70
            ringPaint.strokeWidth = 9f
            c.drawPath(path, ringPaint)
            ringPaint.alpha = 230
            ringPaint.strokeWidth = 2.4f
            c.drawPath(path, ringPaint)
            if (ring.dashes > 0) for (k in 0 until ring.dashes) {
                project(k / ring.dashes.toFloat() * 2 * PI.toFloat(), ring, pt, cx, cy, rad)
                dotPaint.color = ring.color
                dotPaint.alpha = (140 + 100 * (pt[2] + 1f) / 2f).toInt().coerceIn(0, 255)
                c.drawCircle(pt[0], pt[1], 2.6f + pt[2] * 1.2f, dotPaint)
            }
        }
        // Core glow
        val core = rad * (0.12f + level * 0.08f)
        dotPaint.shader = android.graphics.RadialGradient(cx, cy, core * 3f, intArrayOf(0xFFFFFFFF.toInt(), 0x8854D6FF.toInt(), 0x00000000), null, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, core * 3f, dotPaint)
        dotPaint.shader = null
    }
}
