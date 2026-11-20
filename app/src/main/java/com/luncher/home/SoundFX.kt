package com.luncher.home

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * Small, soft sound effects made on the fly (no sound files): pops, ticks, a little chirp for
 * Gilbert, a blip for new notifications and a sparkly chime for "hello". Quiet by design, and
 * silent when the phone is on silent / vibrate or the effects are turned off.
 */
object SoundFX {
    private val exec = Executors.newSingleThreadExecutor()
    /** During Do Not Disturb the effects play like media (which DND lets through). */
    @Volatile private var useMedia = false
    private const val RATE = 44100

    fun play(ctx: Context, name: String) {
        val s = Settings(ctx)
        if (!s.soundFx) return
        val am = ctx.getSystemService(AudioManager::class.java)
        // Do Not Disturb on? Still play (Settings › Sounds › Sounds in Do Not Disturb). Otherwise respect silent / vibrate.
        val dnd = runCatching { ctx.getSystemService(android.app.NotificationManager::class.java).currentInterruptionFilter }
            .getOrDefault(1) > android.app.NotificationManager.INTERRUPTION_FILTER_ALL
        val allowDnd = dnd && s.soundsInDnd
        if (!allowDnd && am != null && am.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        useMedia = allowDnd
        val vol = s.soundFxVolume / 100f
        exec.execute { runCatching { render(name, vol) } }
    }

    private fun render(name: String, vol: Float) {
        // (frequency Hz, length s, start s) notes, softly enveloped and mixed.
        val notes: List<Triple<Double, Double, Double>> = when (name) {
            "pop" -> listOf(Triple(880.0, 0.07, 0.0))
            "tick" -> listOf(Triple(2200.0, 0.025, 0.0))
            "open" -> listOf(Triple(660.0, 0.08, 0.0), Triple(990.0, 0.10, 0.05))
            "close" -> listOf(Triple(990.0, 0.07, 0.0), Triple(660.0, 0.09, 0.05))
            "blip" -> listOf(Triple(1318.5, 0.09, 0.0), Triple(1760.0, 0.12, 0.07))
            "chirp" -> listOf(Triple(1046.5, 0.06, 0.0), Triple(1318.5, 0.06, 0.06), Triple(1568.0, 0.10, 0.12))
            "happy" -> listOf(Triple(784.0, 0.09, 0.0), Triple(1046.5, 0.09, 0.08), Triple(1318.5, 0.16, 0.16))
            "sad" -> listOf(Triple(659.3, 0.14, 0.0), Triple(523.3, 0.22, 0.12))
            "hello" -> listOf(                                     // sparkly rising arpeggio
                Triple(523.3, 0.35, 0.0), Triple(659.3, 0.35, 0.12), Triple(784.0, 0.35, 0.24),
                Triple(1046.5, 0.5, 0.36), Triple(1568.0, 0.6, 0.52), Triple(2093.0, 0.7, 0.68))
            "success" -> listOf(Triple(880.0, 0.08, 0.0), Triple(1174.7, 0.16, 0.07))
            else -> listOf(Triple(880.0, 0.06, 0.0))
        }
        val total = notes.maxOf { it.second + it.third }
        val n = (RATE * (total + 0.05)).toInt()
        val mix = FloatArray(n)
        for ((f, len, start) in notes) {
            val s0 = (RATE * start).toInt(); val ln = (RATE * len).toInt()
            for (i in 0 until ln) {
                val idx = s0 + i; if (idx >= n) break
                val tt = i / RATE.toDouble()
                val attack = min(1.0, i / (RATE * 0.004))
                val env = attack * exp(-4.5 * i / ln)
                // A touch of the octave makes it sound glassy, like iOS.
                val v = sin(2 * PI * f * tt) + 0.25 * sin(4 * PI * f * tt)
                mix[idx] += (v * env).toFloat()
            }
        }
        val peak = mix.maxOf { kotlin.math.abs(it) }.coerceAtLeast(1f)
        val pcm = ShortArray(n) { (mix[it] / peak * 9000 * vol).toInt().toShort() }
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(if (useMedia) AudioAttributes.USAGE_MEDIA else AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(pcm.size * 2).setTransferMode(AudioTrack.MODE_STATIC).build()
        track.write(pcm, 0, pcm.size)
        track.play()
        Thread.sleep(((total + 0.1) * 1000).toLong())
        track.release()
    }
}
