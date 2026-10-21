package ai.softride.app.engine

import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.ErrorType
import ai.pleos.playground.stt.constant.Mode
import ai.pleos.playground.stt.listener.OnInitializeListener
import ai.pleos.playground.stt.listener.ResultListener
import ai.softride.app.signals.LiveVehicle.Companion.TAG
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.Locale

enum class RestState { NONE, ASKED, LISTENING, YES, NO_ANSWER, UNAVAILABLE }

/**
 * Hears the answer to the rest prompt with Pleos SpeechToText (ON_DEVICE, no voice leaves the car).
 * The mic is opened with request() only right after the prompt, and closed after [timeoutMs].
 * "응/네/그래/좋아" → [onYes]; anything else or silence → nothing happens.
 */
class Listener(context: Context, private val onYes: () -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val stt = SpeechToText(context.applicationContext, Mode.ON_DEVICE)
    @Volatile var ready = false; private set
    @Volatile var state = RestState.NONE; private set
    @Volatile var heard = ""; private set
    private val timeout = Runnable { if (state == RestState.LISTENING) { runCatching { stt.stop() }; state = RestState.NO_ANSWER; Log.i(TAG, "stt: no answer") } }

    fun start() = runCatching {
        stt.initialize(object : OnInitializeListener {
            override fun onSuccess() { runCatching { stt.setLocale(Locale.KOREA) }; ready = true; Log.i(TAG, "stt ready") }
            override fun onFailure(type: ErrorType) { Log.w(TAG, "stt init failed: $type") }
        })
        stt.addListener(object : ResultListener {
            override fun onUpdated(text: String, completed: Boolean) {
                heard = text
                if (completed && state == RestState.LISTENING) {
                    main.removeCallbacks(timeout)
                    Log.i(TAG, "stt heard: $text")
                    if (isYes(text)) { state = RestState.YES; onYes() } else state = RestState.NO_ANSWER
                }
            }
            override fun onUpdatedEpdData(start: Long, end: Long) {}
            override fun onStartedRecognition() { Log.i(TAG, "stt listening") }
            override fun onEndedRecognition() {}
            override fun onError(type: ErrorType) {
                Log.w(TAG, "stt error: $type")
                if (state == RestState.LISTENING) { main.removeCallbacks(timeout); state = RestState.NO_ANSWER }
            }
            override fun onReady() {}
            override fun onUpdatedRms(rms: Double) {}
        })
    }.onFailure { Log.w(TAG, "stt: ${it.message}") }

    fun asked() { state = RestState.ASKED }

    /** Call when the prompt has finished playing. */
    fun listen(timeoutMs: Long = 6_000) {
        if (!ready) { state = RestState.UNAVAILABLE; Log.i(TAG, "stt unavailable: no answer taken"); return }
        state = RestState.LISTENING
        runCatching { stt.request() }.onFailure { Log.w(TAG, "stt request: ${it.message}"); state = RestState.UNAVAILABLE; return }
        main.postDelayed(timeout, timeoutMs)
    }

    fun reset() { main.removeCallbacks(timeout); if (state == RestState.LISTENING) runCatching { stt.stop() }; state = RestState.NONE; heard = "" }

    fun stop() = runCatching { stt.release() }

    companion object {
        private val YES = listOf("응", "네", "예", "그래", "좋아")
        fun isYes(text: String): Boolean = text.trim().let { t -> YES.any { t.startsWith(it) } }
    }
}
