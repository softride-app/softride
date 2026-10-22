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
import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
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

    /** The 0.3 s earcon at [rate] Hz, [ch] interleaved channels. */
    private fun earconAt(rate: Int, ch: Int = 1): ShortArray {
        val n = (rate * 0.3).toInt()
        return ShortArray(n * ch) { k ->
            val s = (k / ch).toDouble() / rate
            val f = if (s < 0.12) 880.0 else 1174.7                 // two soft notes, A5 → D6
            val local = if (s < 0.12) s else s - 0.12              // each note: quick attack, soft decay
            val env = (1 - exp(-local * 200)) * exp(-local * 12)
            (sin(2 * PI * f * s) * env * 0.35 * Short.MAX_VALUE).toInt().toShort()
        }
    }
    private val earcon: ShortArray = earconAt(24_000)

    /** A pre-generated voice clip, decoded to 16-bit PCM. */
    private class Clip(val pcm: ShortArray, val rate: Int, val ch: Int)

    @Volatile private var clips: Map<String, Clip> = emptyMap()
    @Volatile private var clipsDir = ""
    private var loadingDir = ""
    @Volatile private var clipTrack: AudioTrack? = null

    /** Decodes every clip of [dir] (assets/voice/<dir>/NN.ogg listed in assets/voice/index.json) on a worker thread. */
    @Synchronized private fun loadClips(dir: String) {
        if (dir == clipsDir || dir == loadingDir) return
        loadingDir = dir
        Thread {
            val t = android.os.SystemClock.elapsedRealtime()
            val map = runCatching {
                val idx = JSONObject(ctx.assets.open("voice/index.json").use { String(it.readBytes(), Charsets.UTF_8) })
                idx.keys().asSequence().mapNotNull { text -> decode("voice/$dir/${idx.getString(text)}")?.let { text to it } }.toMap()
            }.onFailure { Log.w(TAG, "voice index: ${it.message}") }.getOrDefault(emptyMap())
            synchronized(this) {
                if (voice.dir == dir) { clips = map; clipsDir = dir }
                loadingDir = ""
            }
            Log.i(TAG, "voice clips '$dir': ${map.size} loaded in ${android.os.SystemClock.elapsedRealtime() - t} ms")
            if (voice.dir != dir) loadClips(voice.dir)
        }.start()
    }

    private fun decode(path: String): Clip? = runCatching {
        val bytes = ctx.assets.open(path).use { it.readBytes() }
        val ex = MediaExtractor()
        ex.setDataSource(object : MediaDataSource() {
            override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
                if (position >= bytes.size) return -1
                val n = minOf(size, bytes.size - position.toInt())
                System.arraycopy(bytes, position.toInt(), buffer, offset, n); return n
            }
            override fun getSize() = bytes.size.toLong()
            override fun close() {}
        })
        try {
            val fmt = ex.getTrackFormat(0); ex.selectTrack(0)
            val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            try {
                codec.configure(fmt, null, null, 0); codec.start()
                var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE); var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                val out = ByteArrayOutputStream(); val info = MediaCodec.BufferInfo()
                var inDone = false; var outDone = false
                val deadline = android.os.SystemClock.elapsedRealtime() + 5_000
                while (!outDone) {
                    check(android.os.SystemClock.elapsedRealtime() < deadline) { "decode timeout" }
                    if (!inDone) {
                        val i = codec.dequeueInputBuffer(10_000)
                        if (i >= 0) {
                            val n = ex.readSampleData(codec.getInputBuffer(i)!!, 0)
                            if (n < 0) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                            else { codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                        }
                    }
                    val o = codec.dequeueOutputBuffer(info, 10_000)
                    if (o >= 0) {
                        val b = codec.getOutputBuffer(o)!!
                        val chunk = ByteArray(info.size); b.position(info.offset); b.get(chunk); out.write(chunk)
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                    } else if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        codec.outputFormat.let { rate = it.getInteger(MediaFormat.KEY_SAMPLE_RATE); ch = it.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }
                    }
                }
                val sb = ByteBuffer.wrap(out.toByteArray()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                Clip(ShortArray(sb.remaining()).also { sb.get(it) }, rate, ch)
            } finally { codec.release() }
        } finally { ex.release() }
    }.onFailure { Log.w(TAG, "decode $path: ${it.message}") }.getOrNull()

    fun start() {
        loadClips(voice.dir)
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
                        override fun onStart(id: String?) { Log.i(TAG, "platform tts onStart +${since()}ms") }
                        override fun onDone(id: String?) { release(); finished() }
                        @Deprecated("") override fun onError(id: String?) { release() }
                    })
                    if (!pleosReady) engine = "platform"
                    Log.i(TAG, "platform TTS ready: ${platform?.defaultEngine}")
                } else Log.w(TAG, "platform TTS init failed: $st")
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
        main.postDelayed({ release() }, 6_000)                // safety net if no done-callback arrives
        clips[phrase]?.let { clip ->
            if (playClip(clip, withEarcon)) { Log.i(TAG, "cue: $phrase via clip $clipsDir"); return true }
        }
        if (withEarcon) playEarcon()
        // Warm on-device TTS needs 0.15–0.35 s from speak() to sound on the AVD, so speak 150 ms into the 0.3 s
        // earcon: the phrase lands 0–0.2 s after the earcon ends and never overlaps it.
        main.postDelayed({ say(phrase) }, if (withEarcon) 150 else 0)
        Log.i(TAG, "cue: $phrase via $engine")
        return true
    }

    /**
     * Plays [earcon +] clip as ONE gapless AudioTrack (the clip's leading silence is trimmed, so the phrase starts
     * right where the 0.3 s earcon ends and never overlaps it). Focus is released and onDone fires when the
     * playback head reaches the end; a timer at duration + 0.5 s covers a missed marker callback.
     */
    private fun playClip(c: Clip, withEarcon: Boolean): Boolean {
        val buf = if (withEarcon) earconAt(c.rate, c.ch) + c.pcm else c.pcm
        val frames = buf.size / c.ch
        val track = runCatching {
            AudioTrack.Builder().setAudioAttributes(attrs)
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(c.rate)
                    .setChannelMask(if (c.ch == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO).build())
                .setBufferSizeInBytes(buf.size * 2).setTransferMode(AudioTrack.MODE_STATIC).build()
        }.getOrElse { Log.w(TAG, "clip track: ${it.message}"); return false }
        var ended = false
        val end = Runnable {
            if (!ended) {
                ended = true
                if (clipTrack === track) clipTrack = null
                runCatching { track.release() }
                Log.i(TAG, "clip end +${since()}ms")
                release(); finished()
            }
        }
        return runCatching {
            track.write(buf, 0, buf.size)
            track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                override fun onMarkerReached(t: AudioTrack?) { end.run() }
                override fun onPeriodicNotification(t: AudioTrack?) {}
            }, main)
            track.setNotificationMarkerPosition(frames)
            clipTrack?.let { runCatching { it.release() } }
            clipTrack = track
            track.play()
            Log.i(TAG, "clip play +${since()}ms (${frames * 1000L / c.rate} ms)")
            main.postDelayed(end, frames * 1000L / c.rate + 500)
            true
        }.getOrElse { Log.w(TAG, "clip play: ${it.message}"); runCatching { track.release() }; false }
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

    /** Speaking speed; set at trip start from the child's profile. Switching speed reloads that speed's clips. */
    @Volatile var voice = Voice.NORMAL
        set(v) {
            field = v
            synchronized(this) { if (clipsDir != v.dir) clips = emptyMap() }   // never play another speed's clips
            Log.i(TAG, "voice ${v.name} (${v.dir})")
            loadClips(v.dir)
        }

    private fun say(text: String) {
        Log.i(TAG, "tts speak() +${since()}ms")
        if (pleosReady) runCatching { pleos?.speak(text, true); return }.onFailure { Log.w(TAG, "speak: ${it.message}") }
        val v = voice
        platform?.apply { setPitch(v.pitch); setSpeechRate(v.rate) }
            ?.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "cue")
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
        runCatching { clipTrack?.release() }
        runCatching { pleos?.release() }
        platform?.shutdown()
    }
}
