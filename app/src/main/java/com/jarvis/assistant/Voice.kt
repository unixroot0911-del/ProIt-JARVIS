package com.jarvis.assistant

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.sqrt

/**
 * Voice in: Jarvis records the microphone itself (needs only Jarvis' own mic permission, no dependence on the
 * phone's speech service) and the brain turns the audio into text. Voice out: Android TTS, low deep pitch.
 */
class Voice(private val context: Context, private val prefs: Prefs) {

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    @Volatile private var finishNow = false

    init {
        tts = TextToSpeech(context) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                tts?.setPitch(0.75f)
                tts?.setSpeechRate(0.95f)
            }
        }
    }

    /** Ends the current recording early, as if the user had stopped talking. */
    fun finishRecording() { finishNow = true }

    /**
     * Records one utterance. Stops after about 1.3 s of silence once speech was heard, when [finishRecording] is called,
     * or after 20 s. Returns a WAV, or null if nobody spoke. Throws a readable error if the microphone cannot be used.
     */
    suspend fun record(maxMs: Int = 20000): ByteArray? = withContext(Dispatchers.IO) {
        finishNow = false
        val rate = 16000
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) throw IllegalStateException("This phone cannot record audio at 16 kHz.")

        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, rate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 4
            )
        } catch (e: Exception) {
            throw IllegalStateException("Cannot open the microphone: ${e.message}")
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            throw IllegalStateException("The microphone could not start. Check Jarvis' microphone permission, or close apps that use the mic.")
        }

        val pcm = ByteArrayOutputStream()
        try {
            rec.startRecording()
            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw IllegalStateException("Another app is using the microphone right now.")
            }
            val chunk = ShortArray(1600)   // 100 ms
            var elapsed = 0
            var speech = false
            var silentMs = 0
            var noise = 0.0
            var calibrated = 0

            while (isActive && elapsed < maxMs) {
                val n = rec.read(chunk, 0, chunk.size)
                if (n < 0) throw IllegalStateException("Microphone read error ($n).")
                if (n == 0) continue

                var sum = 0.0
                for (i in 0 until n) sum += chunk[i].toDouble() * chunk[i].toDouble()
                val rms = sqrt(sum / n)
                val ms = n * 1000 / rate
                elapsed += ms

                val bb = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until n) bb.putShort(chunk[i])
                pcm.write(bb.array())

                if (calibrated < 3) {              // first 300 ms: learn the room noise
                    noise += rms
                    calibrated++
                    if (calibrated == 3) noise = minOf(noise / 3.0, 1500.0)
                    continue
                }
                val threshold = maxOf(minOf(noise * 2.5, 2500.0), 500.0)
                if (rms > threshold) { speech = true; silentMs = 0 } else if (speech) silentMs += ms

                if (speech && (silentMs >= 1300 || finishNow)) break
                if (finishNow && !speech) break
                if (!speech && elapsed > 8000) return@withContext null
            }
            if (!speech) null else wav(pcm.toByteArray(), rate)
        } finally {
            try { rec.stop() } catch (e: Exception) { /* already stopped */ }
            rec.release()
        }
    }

    private fun wav(pcm: ByteArray, rate: Int): ByteArray {
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()); h.putInt(36 + pcm.size); h.put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()); h.putInt(16); h.putShort(1.toShort()); h.putShort(1.toShort())
        h.putInt(rate); h.putInt(rate * 2); h.putShort(2.toShort()); h.putShort(16.toShort())
        h.put("data".toByteArray()); h.putInt(pcm.size)
        return h.array() + pcm
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

    /** Waits until Jarvis has finished talking, so hands-free mode does not record its own voice. */
    suspend fun awaitSpeech(maxMs: Long = 25000) {
        delay(500)
        var waited = 500L
        while (tts?.isSpeaking == true && waited < maxMs) { delay(150); waited += 150 }
        delay(250)
    }

    fun shutdown() {
        finishNow = true
        tts?.stop()
        tts?.shutdown()
    }
}
