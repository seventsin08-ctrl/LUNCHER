package com.luncher.home

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.util.Locale

/**
 * Gilbert's voice in his console: a calm British male voice (like a butler-AI) that greets you
 * and talks you through what's on screen. Uses the phone's text-to-speech (Google's voices
 * work best — Settings › Accessibility › Text-to-speech).
 */
object GilbertNarrator {
    private var tts: TextToSpeech? = null
    private var ready = false
    private val pending = ArrayList<String>()
    private val main = Handler(Looper.getMainLooper())
    /** Told (main thread) when he starts / stops talking: the line, or null when quiet. */
    var onSpeaking: ((String?) -> Unit)? = null

    fun start(ctx: Context) {
        if (tts != null) return
        tts = TextToSpeech(ctx.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (!ready) return@TextToSpeech
            tts?.let { t ->
                pickVoice(t)
                t.setSpeechRate(0.98f); t.setPitch(0.88f)
                t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) { val line = lines[id]; main.post { onSpeaking?.invoke(line) } }
                    override fun onDone(id: String?) { lines.remove(id); main.post { if (!speaking()) onSpeaking?.invoke(null) } }
                    @Deprecated("") override fun onError(id: String?) { lines.remove(id); main.post { onSpeaking?.invoke(null) } }
                })
                val q = pending.toList(); pending.clear()
                q.forEach { say(ctx, it, flush = false) }
            }
        }
    }

    /** British male voice if the phone has one (Google: en-gb-x-rjs / gbd / gbb). */
    private fun pickVoice(t: TextToSpeech) {
        runCatching {
            val voices = t.voices?.filter { it.locale.language == "en" && !it.isNetworkConnectionRequired } ?: emptyList()
            val uk = voices.filter { it.locale.country == "GB" }
            val pick = listOf("rjs", "gbd", "gbb").firstNotNullOfOrNull { k -> uk.firstOrNull { it.name.contains(k, true) } }
                ?: uk.firstOrNull { it.name.contains("male", true) && !it.name.contains("female", true) }
                ?: uk.maxByOrNull { it.quality }
            if (pick != null) t.voice = pick
            else if (t.isLanguageAvailable(Locale.UK) >= TextToSpeech.LANG_AVAILABLE) t.language = Locale.UK
        }
    }

    private val lines = HashMap<String, String>()
    private var n = 0
    fun speaking() = runCatching { tts?.isSpeaking == true }.getOrDefault(false)

    /** Say [text]. [flush] cuts off whatever he was saying. */
    fun say(ctx: Context, text: String, flush: Boolean = true) {
        if (!Settings(ctx).gilbertSpeaks || text.isBlank()) return
        val clean = text.replace(Regex("[\\p{So}\\p{Cn}✨•·]"), " ").replace(Regex("\\s+"), " ").trim()
        start(ctx)
        val t = tts
        if (t == null || !ready) { if (flush) pending.clear(); pending += clean; return }
        val id = "g${n++}"; lines[id] = clean
        t.speak(clean, if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
            Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1f) }, id)
    }

    fun stop() { pending.clear(); runCatching { tts?.stop() }; onSpeaking?.invoke(null) }

    // ------------------------------------------------------------------ what he says
    /** How he addresses you (Settings › Gilbert › Call Me). */
    fun title(ctx: Context): String = when (val t = Settings(ctx).gilbertTitle) {
        "name" -> GilbertTalk.ownerName(ctx) ?: "sir"
        else -> t
    }

    fun greeting(ctx: Context): String {
        val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val part = when (h) { in 5..11 -> "Good morning"; in 12..17 -> "Good afternoon"; in 18..21 -> "Good evening"; else -> "Working late" }
        return "$part, ${title(ctx)}."
    }

    fun plural(n: Int, one: String, many: String = one + "s") = "$n ${if (n == 1) one else many}"
}
