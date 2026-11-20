package com.luncher.home

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Gilbert's ears and voice: tap 🎤 and speak (Android's speech recognition turns it into text),
 * and he answers out loud with the phone's text-to-speech.
 */
object GilbertVoice {
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var recognizer: SpeechRecognizer? = null

    fun available(act: Activity) = SpeechRecognizer.isRecognitionAvailable(act)

    /** Listen once. [partial] shows words as you speak; [done] gets the final text (or null). */
    fun listen(act: Activity, partial: (String) -> Unit, done: (String?) -> Unit) {
        stopListening()
        val r = runCatching { SpeechRecognizer.createSpeechRecognizer(act) }.getOrNull() ?: run { done(null); return }
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(t: Int, p: Bundle?) {}
            override fun onError(e: Int) { done(null); stopListening() }
            override fun onPartialResults(b: Bundle?) {
                b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(partial)
            }
            override fun onResults(b: Bundle?) {
                done(b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull())
                stopListening()
            }
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        runCatching { r.startListening(i) }.onFailure { done(null) }
    }

    fun stopListening() { runCatching { recognizer?.destroy() }; recognizer = null }

    /** Say something out loud (warm, slightly British like a butler). */
    fun speak(act: Activity, text: String) {
        val clean = text.replace(Regex("[\\p{So}\\p{Cn}•⚠️📋📝✨🙂]"), "").replace("\n", ". ").trim()
        if (clean.isEmpty()) return
        val t = tts
        if (t != null && ttsReady) { t.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "gil"); return }
        tts = TextToSpeech(act.applicationContext) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                tts?.let { s ->
                    if (s.isLanguageAvailable(Locale.UK) >= TextToSpeech.LANG_AVAILABLE) s.language = Locale.UK
                    s.setSpeechRate(1.05f); s.setPitch(0.95f)
                    s.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "gil")
                }
            }
        }
    }

    fun shutUp() { runCatching { tts?.stop() } }
}
