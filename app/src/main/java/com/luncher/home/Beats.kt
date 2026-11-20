package com.luncher.home

import android.content.Context
import android.content.pm.PackageManager
import android.media.audiofx.Visualizer
import android.os.Handler
import android.os.Looper
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Feels the music: listens to what the phone is playing (Android's audio visualizer — it hears
 * the phone's own output, needs the "Microphone / Audio" permission, records nothing) and reports
 * the bass punch (beats) and 4 frequency bands for the island's bars.
 * Without the permission it falls back to a soft, steady pulse.
 */
object Beats {
    /** 0..1 per band (low → high), smoothed. */
    val bands = FloatArray(4)
    /** Called on every detected beat with its strength (0..1). */
    val listeners = LinkedHashSet<(Float) -> Unit>()
    @Volatile var enabled = true
    var live = false; private set

    private var vis: Visualizer? = null
    private val main = Handler(Looper.getMainLooper())
    private var avg = 0f
    private var lastBeat = 0L
    private var users = 0

    fun allowed(ctx: Context) = ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Start listening (call when music plays and something shows the beat). */
    fun start(ctx: Context) {
        users++
        if (!enabled || vis != null) return
        if (!allowed(ctx)) { startFallback(); return }
        vis = runCatching {
            Visualizer(0).apply {
                captureSize = Visualizer.getCaptureSizeRange()[1].coerceAtMost(1024)
                setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(v: Visualizer?, w: ByteArray?, rate: Int) {}
                    override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, rate: Int) { fft?.let { analyse(it) } }
                }, (Visualizer.getMaxCaptureRate() * 0.9).toInt(), false, true)
                this.enabled = true
            }
        }.getOrNull()
        live = vis != null
        if (vis == null) startFallback()
    }

    fun stop() {
        users = (users - 1).coerceAtLeast(0)
        if (users > 0) return
        runCatching { vis?.enabled = false; vis?.release() }
        vis = null; live = false
        main.removeCallbacks(fallback)
        for (i in bands.indices) bands[i] = 0f
    }

    private fun analyse(fft: ByteArray) {
        val n = fft.size / 2
        fun mag(k: Int): Float { val re = fft[2 * k].toFloat(); val im = fft[2 * k + 1].toFloat(); return hypot(re, im) }
        // Rough bands for a 44.1 kHz stream: bass, low-mid, mid, high.
        val edges = intArrayOf(1, 4, 12, 40, min(n - 1, 160))
        for (b in 0 until 4) {
            var s = 0f; var c = 0
            for (k in edges[b] until edges[b + 1]) { s += mag(k); c++ }
            val v = (s / max(1, c) / 60f).coerceIn(0f, 1f)
            bands[b] = bands[b] * 0.55f + v * 0.45f
        }
        // Beat: bass jumps clearly above its recent average.
        val bass = bands[0]
        avg = avg * 0.92f + bass * 0.08f
        val now = System.currentTimeMillis()
        if (bass > avg * 1.35f && bass > 0.18f && now - lastBeat > 230) {
            lastBeat = now
            val strength = ((bass - avg) / max(0.05f, avg)).coerceIn(0.2f, 1f)
            main.post { for (l in listeners.toList()) runCatching { l(strength) } }
        }
    }

    /** No permission: a soft heartbeat-like pulse (~100 BPM) so the island still breathes with the music. */
    private val fallback = object : Runnable {
        var k = 0
        override fun run() {
            k++
            val s = if (k % 4 == 0) 0.7f else 0.35f
            for (i in bands.indices) bands[i] = 0.3f + 0.4f * s
            for (l in listeners.toList()) runCatching { l(s) }
            main.postDelayed(this, 600)
        }
    }
    private fun startFallback() { main.removeCallbacks(fallback); main.postDelayed(fallback, 600) }
}
