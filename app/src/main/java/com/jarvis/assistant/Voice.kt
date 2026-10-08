package com.jarvis.assistant

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import java.util.Locale

/** Speech in (on-device recognizer where available) and speech out (Android TTS, low deep pitch). */
class Voice(private val context: Context, private val prefs: Prefs) {

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    init {
        tts = TextToSpeech(context) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                tts?.setPitch(0.75f)
                tts?.setSpeechRate(0.95f)
            }
        }
    }

    fun listen(onPartial: (String) -> Unit, onResult: (String) -> Unit, onFail: (String) -> Unit) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onFail("Speech recognition is not available on this phone.")
            return
        }
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).also { r ->
            r.setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (text.isNullOrBlank()) onFail("I did not catch that.") else onResult(text)
                }
                override fun onPartialResults(partial: Bundle?) {
                    partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(onPartial)
                }
                override fun onError(error: Int) {
                    onFail(when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "I did not hear anything."
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is missing."
                        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech needs a connection right now."
                        else -> "Speech error ($error)."
                    })
                }
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, prefs.speechLocale)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        recognizer?.startListening(intent)
    }

    fun stopListening() {
        recognizer?.stopListening()
    }

    /** Picks the TTS language from the reply text: Arabic script means Arabic voice, otherwise English. */
    fun speak(text: String) {
        if (!prefs.speakReplies || !ttsReady) return
        val arabic = text.any { it in '؀'..'ۿ' }
        val locale = if (arabic) Locale("ar") else Locale.ENGLISH
        val r = tts?.setLanguage(locale)
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            tts?.setLanguage(Locale.getDefault())
        }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis")
    }

    fun shutdown() {
        recognizer?.destroy()
        tts?.stop()
        tts?.shutdown()
    }
}
