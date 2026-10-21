package ai.softride.app.engine

import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import ai.softride.app.signals.LiveVehicle.Companion.TAG
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.Locale
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Earcon (0.3 s) + short TTS phrase under transient, ducking audio focus.
 * TTS: Pleos TextToSpeech in ON_DEVICE mode (no voice leaves the car); if it cannot initialise,
 * the platform TTS engine is used so the cue still plays.
 */
class Speaker(context: Context) {
    private val ctx = context.applicationContext
    private val am = ctx.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attrs).setOnAudioFocusChangeListener { Log.i(TAG, "focus change $it +${since()}ms") }.build()

    private var pleos: TextToSpeech? = null
    @Volatile private var pleosReady = false
    private var platform: android.speech.tts.TextToSpeech? = null
    @Volatile var engine = "none"; private set

    private val earcon: ShortArray = run {
        val rate = 24_000; val n = (rate * 0.3).toInt()
        ShortArray(n) { i ->
            val s = i.toDouble() / rate
            val f = if (s < 0.12) 880.0 else 1174.7                 // two soft notes, A5 → D6
            val local = if (s < 0.12) s else s - 0.12              // each note: quick attack, soft decay
            val env = (1 - exp(-local * 200)) * exp(-local * 12)
            (sin(2 * PI * f * s) * env * 0.35 * Short.MAX_VALUE).toInt().toShort()
        }
    }

    fun start() {
        runCatching {
            pleos = TextToSpeech(ctx, Mode.ON_DEVICE).also { tts ->
                tts.initialize(object : OnInitializeListener {
                    override fun onSuccess() {
                        runCatching { tts.setLocale(Locale.KOREA); tts.setAudioAttributes(attrs) }
                        pleosReady = true; engine = "pleos"
                        Log.i(TAG, "Pleos TTS ready")
                        warmUp()
                    }
                    override fun onFailure(type: ErrorType) { Log.w(TAG, "Pleos TTS init failed: $type"); startPlatform() }
                })
                tts.addEventListener(object : EventListener {
                    override fun onStart() { Log.i(TAG, "tts onStart +${since()}ms") }
                    override fun onReady() { Log.i(TAG, "tts onReady +${since()}ms") }
                    override fun onDone() { release(); finished() }
                    override fun onError(type: ErrorType) { Log.w(TAG, "Pleos TTS error: $type"); release() }
                    override fun onStop() { release() }
                    override fun onUpdatedRms(rms: Double) {}
                })
            }
        }.onFailure { Log.w(TAG, "Pleos TTS: ${it.message}"); startPlatform() }
    }

    private fun startPlatform() {
        if (platform != null) return
        main.post {
            platform = android.speech.tts.TextToSpeech(ctx) { st ->
                if (st == android.speech.tts.TextToSpeech.SUCCESS) {
                    platform?.language = Locale.KOREA
                    platform?.setAudioAttributes(attrs)
                    platform?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                        override fun onStart(id: String?) {}
                        override fun onDone(id: String?) { release(); finished() }
                        @Deprecated("") override fun onError(id: String?) { release() }
                    })
                    if (!pleosReady) engine = "platform"
                }
            }
        }
    }

    /**
     * Plays earcon + phrase. Returns false (and plays nothing) when another stream is active
     * or focus is not granted.
     */
    fun cue(phrase: String, withEarcon: Boolean = true, onDone: (() -> Unit)? = null): Boolean {
        // Skip only when another stream holds focus. (isMusicActive was dropped: on the AVD the turn-signal
        // ticker made it true and silenced exactly the cue the signal is meant to confirm.)
        t0 = android.os.SystemClock.elapsedRealtime()
        val fr = am.requestAudioFocus(focus)
        Log.i(TAG, "cue focus result=$fr +${since()}ms")
        if (fr != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { Log.i(TAG, "cue skipped: focus denied"); return false }
        pendingDone = onDone
        if (withEarcon) playEarcon()
        // Warm on-device TTS needs 0.15–0.35 s from speak() to sound on the AVD, so speak 150 ms into the 0.3 s
        // earcon: the phrase lands 0–0.2 s after the earcon ends and never overlaps it.
        main.postDelayed({ say(phrase) }, if (withEarcon) 150 else 0)
        main.postDelayed({ release() }, 6_000)                // safety net if no done-callback arrives
        Log.i(TAG, "cue: $phrase via $engine")
        return true
    }

    /**
     * Loads the on-device voice before the first cue. On the AVD the first synthesis after boot started
     * 1.3–3 s late (earcon heard, phrase seconds later); later cues start ~0.2 s after the earcon.
     * A blank utterance makes the engine load without producing sound.
     */
    fun warmUp() {
        if (!pleosReady) return
        t0 = android.os.SystemClock.elapsedRealtime()
        Log.i(TAG, "tts warm-up")
        runCatching { pleos?.speak(" ", true) }.onFailure { Log.w(TAG, "warm-up: ${it.message}") }
    }

    @Volatile private var t0 = 0L
    private fun since() = android.os.SystemClock.elapsedRealtime() - t0

    private fun say(text: String) {
        Log.i(TAG, "tts speak() +${since()}ms")
        if (pleosReady) runCatching { pleos?.speak(text, true); return }.onFailure { Log.w(TAG, "speak: ${it.message}") }
        platform?.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "cue")
    }

    private fun playEarcon() {
        val track = AudioTrack.Builder().setAudioAttributes(attrs)
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(24_000)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(earcon.size * 2).setTransferMode(AudioTrack.MODE_STATIC).build()
        track.write(earcon, 0, earcon.size)
        track.play()
        Log.i(TAG, "earcon play +${since()}ms")
        main.postDelayed({ track.release() }, 600)
    }

    private fun release() { am.abandonAudioFocusRequest(focus) }

    @Volatile private var pendingDone: (() -> Unit)? = null
    private fun finished() { pendingDone?.let { pendingDone = null; main.post(it) } }

    fun stop() {
        runCatching { pleos?.release() }
        platform?.shutdown()
    }
}
