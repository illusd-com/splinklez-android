package cc.huhm.splinklez.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceLogicTest {
    private fun run(ep: Endpointer, amps: List<Int>): Pair<Endpointer.Verdict, Int> {
        amps.forEachIndexed { i, a ->
            val v = ep.feed(a)
            if (v != Endpointer.Verdict.GOING) return v to i
        }
        return Endpointer.Verdict.GOING to amps.size
    }

    @Test fun endsAfterQuietFollowingSpeech() {
        val quiet = List(6) { 300 }
        val speech = List(25) { 9000 }
        val after = List(40) { 350 }
        val (v, i) = run(Endpointer(), quiet + speech + after)
        assertEquals(Endpointer.Verdict.END, v)
        // 1.2 s of quiet at 60 ms ticks = 20 ticks after speech ends.
        assertEquals(6 + 25 + 19, i)
    }

    @Test fun silenceTimesOut() {
        val (v, i) = run(Endpointer(), List(200) { 200 })
        assertEquals(Endpointer.Verdict.NOTHING, v)
        assertEquals(7000 / 60, i + 0) // ~7 s
    }

    @Test fun noisyRoomRaisesThreshold() {
        val ep = Endpointer()
        val (v, _) = run(ep, List(150) { 2500 }) // steady fan noise only
        assertEquals(Endpointer.Verdict.NOTHING, v)
        assertFalse(ep.spoke)
    }

    @Test fun shortPausesDoNotEnd() {
        val pattern = List(6) { 300 } + List(10) { 8000 } + List(12) { 300 } + List(10) { 8000 } + List(5) { 300 }
        val (v, _) = run(Endpointer(), pattern)
        assertEquals(Endpointer.Verdict.GOING, v)
    }

    @Test fun cleanStripsMarkdownAndEmoji() {
        val s = Speech.clean("**今天** 台北 ☀️ 晴，[氣象署](https://cwa.gov.tw) 說 28 度 🌡️\n- 記得防曬 😎")
        assertEquals("今天 台北 晴，氣象署 說 28 度 記得防曬", s)
        assertTrue(Speech.clean("👨‍👩‍👧 家人平安").startsWith("家人平安"))
    }

    @Test fun clipCutsAtSentence() {
        val long = "第一句話講完了。".repeat(80)
        val c = Speech.clip(long, 100)
        assertTrue(c.length <= 100 && c.endsWith("。"))
    }
}
