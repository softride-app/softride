package ai.softride.app.engine

import ai.softride.app.signals.EventKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Every text the app can speak must have a pre-generated clip in all three speeds (assets/voice). */
class VoiceClipsTest {
    private val voiceDir = File("src/main/assets/voice")   // unit tests run in the app/ module directory

    private fun index(): Map<String, String> =
        Regex("\"([^\"]+)\":\\s*\"([^\"]+)\"").findAll(File(voiceDir, "index.json").readText(Charsets.UTF_8))
            .associate { it.groupValues[1] to it.groupValues[2] }

    @Test fun everySpokenPhraseHasAClipInEverySpeed() {
        val phrases = EventKind.entries.mapNotNull { it.phrase } +
            listOf(WindowGaze.PHRASE, AirCare.SAY_FRESH, AirCare.SAY_TEMP, AirCare.SAY_FAN, AirCare.ADVICE, REST_YES) +
            (50..3_000 step 10).map { restPrompt(it.toDouble()) }
        val idx = index()
        assertTrue("index.json empty", idx.isNotEmpty())
        for (p in phrases.distinct()) {
            val file = idx[p]
            assertTrue("no clip entry for '$p'", file != null)
            Voice.entries.forEach { v -> assertTrue("missing ${v.dir}/$file for '$p'", File(voiceDir, "${v.dir}/$file").isFile) }
        }
        assertEquals("phrases without a trigger in index.json", phrases.distinct().size, idx.size)
    }

    @Test fun restPromptRoundsTo100mAndSwitchesToKilometres() {
        assertEquals("100미터 앞 휴게소에서 쉬었다 갈까요?", restPrompt(30.0))
        assertEquals("900미터 앞 휴게소에서 쉬었다 갈까요?", restPrompt(940.0))
        assertEquals("1킬로미터 앞 휴게소에서 쉬었다 갈까요?", restPrompt(950.0))
        assertEquals("1.5킬로미터 앞 휴게소에서 쉬었다 갈까요?", restPrompt(1_540.0))
        assertEquals("3킬로미터 앞 휴게소에서 쉬었다 갈까요?", restPrompt(2_999.0))
    }

    @Test fun oldSavedVoicesMapToSpeeds() {
        assertEquals(Voice.NORMAL, Voice.of("WARM")); assertEquals(Voice.FAST, Voice.of("CLEAR"))
        assertEquals(Voice.SLOW, Voice.of("CALM")); assertEquals(Voice.SLOW, Voice.of("SLOW"))
        assertEquals(Voice.NORMAL, Voice.of("anything else"))
    }
}
