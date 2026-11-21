package com.luncher.home

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.audiofx.PresetReverb
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.io.File
import java.util.Locale

/**
 * Gilbert's voice in his console: a calm British voice (pick one in his console › 06 SYSTEM),
 * slightly slower and deeper, with an optional "AI" room echo so he sounds like he's coming
 * through speakers. Uses the phone's text-to-speech.
 */
object GilbertNarrator {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var app: Context? = null
    private val main = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<String>()
    private var busy = false
    private var current: String? = null
    private var player: MediaPlayer? = null
    private var reverb: PresetReverb? = null
    private var n = 0
    /** Told (main thread) when he starts / stops talking: the line, or null when quiet. */
    var onSpeaking: ((String?) -> Unit)? = null

    fun start(ctx: Context) {
        if (tts != null) return
        app = ctx.applicationContext
        tts = TextToSpeech(ctx.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (!ready) return@TextToSpeech
            tts?.let { t ->
                applyVoice(t)
                t.setSpeechRate(0.96f); t.setPitch(0.86f)
                t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) { if (id?.startsWith("p") == true) main.post { onSpeaking?.invoke(current) } }
                    override fun onDone(id: String?) {
                        main.post {
                            if (id?.startsWith("f") == true) play(File(app!!.cacheDir, "$id.wav")) else { busy = false; next() }
                        }
                    }
                    @Deprecated("") override fun onError(id: String?) { main.post { busy = false; next() } }
                })
            }
            main.post { next() }
        }
    }

    // ------------------------------------------------------------------ voices
    private fun englishVoices(t: TextToSpeech): List<Voice> = runCatching {
        t.voices?.filter { it.locale.language == "en" && !it.isNetworkConnectionRequired && !it.features.contains("notInstalled") }
            ?.sortedWith(compareBy({ if (it.locale.country == "GB") 0 else 1 }, { it.name })) ?: emptyList()
    }.getOrDefault(emptyList())

    /** British voices first (name → nice label), for the picker. */
    fun voices(): List<Pair<String, String>> {
        val t = tts ?: return emptyList()
        if (!ready) return emptyList()
        return englishVoices(t).take(14).mapIndexed { i, v ->
            val c = when (v.locale.country) { "GB" -> "British"; "US" -> "American"; "AU" -> "Australian"; "IN" -> "Indian"; else -> v.locale.displayCountry }
            val tag = Regex("-x-([a-z]{3})").find(v.name)?.groupValues?.get(1)?.uppercase() ?: "${i + 1}"
            v.name to "$c $tag"
        }
    }

    private fun applyVoice(t: TextToSpeech) {
        runCatching {
            val all = englishVoices(t)
            val chosen = app?.let { Settings(it).gilbertVoice }?.takeIf { it.isNotEmpty() }
            val uk = all.filter { it.locale.country == "GB" }
            val pick = all.firstOrNull { it.name == chosen }
                ?: listOf("rjs", "gbd", "gbb").firstNotNullOfOrNull { k -> uk.firstOrNull { it.name.contains(k, true) } }
                ?: uk.firstOrNull { it.name.contains("male", true) && !it.name.contains("female", true) }
                ?: uk.maxByOrNull { it.quality }
            if (pick != null) t.voice = pick
            else if (t.isLanguageAvailable(Locale.UK) >= TextToSpeech.LANG_AVAILABLE) t.language = Locale.UK
        }
    }

    /** Switch voice and play a sample line. */
    fun useVoice(ctx: Context, name: String) {
        Settings(ctx).gilbertVoice = name
        tts?.let { applyVoice(it) }
        say(ctx, "${greeting(ctx)} All systems are online.")
    }

    // ------------------------------------------------------------------ speaking
    fun speaking() = busy

    /** Say [text]. [flush] cuts off whatever he was saying. */
    fun say(ctx: Context, text: String, flush: Boolean = true) {
        if (!Settings(ctx).gilbertSpeaks || text.isBlank()) return
        val clean = text.replace(Regex("[\\p{So}\\p{Cn}✨•·]"), " ").replace(Regex("\\s+"), " ").trim()
        start(ctx)
        if (flush) { queue.clear(); halt() }
        queue.addLast(clean)
        if (!busy) next()
    }

    private fun next() {
        val t = tts ?: return
        if (!ready || busy) return
        val line = queue.removeFirstOrNull() ?: run { onSpeaking?.invoke(null); return }
        busy = true; current = line
        val ctx = app ?: return
        val echo = Settings(ctx).gilbertEcho
        if (echo) {
            // Render to a file, then play it with a room echo.
            val id = "f${n++}"
            val f = File(ctx.cacheDir, "$id.wav")
            val r = runCatching { t.synthesizeToFile(line, Bundle(), f, id) }.getOrDefault(TextToSpeech.ERROR)
            if (r == TextToSpeech.SUCCESS) return
        }
        t.speak(line, TextToSpeech.QUEUE_FLUSH, Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1f) }, "p${n++}")
    }

    private fun play(f: File) {
        val line = current
        val mp = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                setDataSource(f.path); prepare()
            }
        }.getOrNull()
        if (mp == null) { f.delete(); busy = false; tts?.let { queue.addFirst(line ?: return); Settings(app!!).gilbertEcho = false }; next(); return }
        // The "AI" sound: a small-room echo mixed in.
        runCatching {
            val rv = reverb ?: PresetReverb(1, 0).also { reverb = it }
            rv.preset = PresetReverb.PRESET_MEDIUMROOM; rv.enabled = true
            mp.attachAuxEffect(rv.id); mp.setAuxEffectSendLevel(0.55f)
        }
        player = mp
        mp.setOnCompletionListener { it.release(); f.delete(); if (player === it) player = null; busy = false; next() }
        mp.setOnErrorListener { p, _, _ -> p.release(); f.delete(); player = null; busy = false; next(); true }
        onSpeaking?.invoke(line)
        mp.start()
    }

    private fun halt() {
        runCatching { tts?.stop() }
        player?.let { runCatching { it.stop(); it.release() } }; player = null
        busy = false
    }

    fun stop() { queue.clear(); halt(); onSpeaking?.invoke(null) }

    // ------------------------------------------------------------------ what he says
    /** How he addresses you (Settings › Gilbert › Call Me). */
    fun title(ctx: Context): String {
        val name = ownerName(ctx)
        return when (val t = Settings(ctx).gilbertTitle) {
            "name" -> name ?: "sir"
            // Like a composed AI assistant: your name mostly, "sir" now and then.
            "auto" -> if (name != null && Math.random() < 0.7) name else "sir"
            else -> t
        }
    }

    /** Your name: from "About you" in his console (Notes), else your contact card. */
    fun ownerName(ctx: Context): String? =
        ctx.getSharedPreferences("gil_profile", Context.MODE_PRIVATE).getString("name", null)?.trim()?.takeIf { it.isNotEmpty() }
            ?: GilbertTalk.ownerName(ctx)

    fun greeting(ctx: Context): String {
        val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val part = when (h) { in 5..11 -> "Good morning"; in 12..17 -> "Good afternoon"; in 18..21 -> "Good evening"; else -> "Working late" }
        return "$part, ${title(ctx)}."
    }

    fun plural(n: Int, one: String, many: String = one + "s") = "$n ${if (n == 1) one else many}"
}
