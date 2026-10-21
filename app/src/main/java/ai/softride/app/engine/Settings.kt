package ai.softride.app.engine

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

enum class Seat(val label: String) { LEFT("뒷좌석 왼쪽"), RIGHT("뒷좌석 오른쪽") }
enum class Sensitivity(val label: String, val airAt: Float, val restAt: Int) {
    LOW("낮음", 55f, 45), MID("보통", 45f, 35), HIGH("높음", 35f, 25)
}
/**
 * Alert voice. Pleos TextToSpeech 3.3.1 has no pitch or rate setting; it picks the voice with
 * setSpeakerType(name) (speaker names as in the Pleos speech service, e.g. its default "seoyoon").
 * [pitch]/[rate] apply only when the Android TextToSpeech fallback speaks.
 */
enum class Voice(val label: String, val speaker: String, val pitch: Float, val rate: Float) {
    WARM("다정한 목소리", "seoyoon", 1.0f, 1.0f),
    CLEAR("또렷한 목소리", "haeun", 1.15f, 1.15f),
    CALM("차분한 목소리", "eunwoo", 0.85f, 0.85f),
}

data class Profile(
    val name: String, val age: Int, val seat: Seat,
    val sensitivity: Sensitivity = Sensitivity.MID, val voice: Voice = Voice.WARM,
)

data class Settings(
    val profiles: List<Profile> = listOf(Profile("서윤", 7, Seat.RIGHT), Profile("준호", 10, Seat.LEFT)),
    val selected: Int = 0,
    val cuesOn: Boolean = true,
    val restOn: Boolean = true,
    val air: AirAllowance = AirAllowance(),
) {
    val child: Profile? get() = profiles.getOrNull(selected)
}

/** Settings stay in the car (SharedPreferences). Changed only while parked (the UI enforces it). */
class SettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<Settings> = _state
    val value: Settings get() = _state.value

    fun update(f: (Settings) -> Settings) {
        val s = f(_state.value)
        _state.value = s
        prefs.edit().putString("json", toJson(s).toString()).apply()
    }

    fun updateChild(f: (Profile) -> Profile) = update { s ->
        s.copy(profiles = s.profiles.mapIndexed { i, p -> if (i == s.selected) f(p) else p })
    }

    private fun load(): Settings = prefs.getString("json", null)?.let { runCatching { fromJson(JSONObject(it)) }.getOrNull() } ?: Settings()

    private fun toJson(s: Settings) = JSONObject().apply {
        put("profiles", JSONArray(s.profiles.map { p ->
            JSONObject().put("name", p.name).put("age", p.age).put("seat", p.seat.name)
                .put("sensitivity", p.sensitivity.name).put("voice", p.voice.name)
        }))
        put("selected", s.selected); put("cuesOn", s.cuesOn); put("restOn", s.restOn)
        put("freshAir", s.air.freshAir); put("tempDownC", s.air.tempDownC.toDouble()); put("fanUp", s.air.fanUp)
    }

    private fun fromJson(o: JSONObject): Settings {
        val a = o.getJSONArray("profiles")
        return Settings(
            profiles = List(a.length()) { i -> a.getJSONObject(i).let {
                Profile(it.getString("name"), it.getInt("age"), Seat.valueOf(it.getString("seat")),
                    Sensitivity.valueOf(it.getString("sensitivity")), Voice.valueOf(it.getString("voice")))
            } },
            selected = o.getInt("selected"), cuesOn = o.getBoolean("cuesOn"), restOn = o.getBoolean("restOn"),
            air = AirAllowance(o.getBoolean("freshAir"), o.getDouble("tempDownC").toFloat(), o.getInt("fanUp")),
        )
    }
}
