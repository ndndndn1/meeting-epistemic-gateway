package io.github.ndndndn1.meg

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MeetingTest {
    private fun claim(i: Int) =
        JSONObject()
            .put("id", "$i")
            .put("utteranceId", "$i")
            .put("text", "주장")
            .put("speakerId", "A")
            .put("speakerCertain", true)
            .put("status", "UNSUPPORTED ASSERTION")
            .put("reason", "근거 부족")
            .put("evidenceIds", JSONArray())
            .put("endedAt", i)
            .put("checkedAt", i)
            .put("revisions", JSONArray())
            .put("spoken", false)

    @Test
    fun replayAndSanitizedRestore() {
        val m = emptyMeeting()
        m.getJSONArray("speakers")
            .put(JSONObject().put("id", "A").put("name", "화자 A").put("key", "must-disappear"))
        (1..3).forEach { m.getJSONArray("claims").put(claim(it)) }
        assertEquals("EVIDENCE REQUIRED", policy(m, "A").mode)
        revise(m.getJSONArray("claims").getJSONObject(1), "VERIFIED", "새 근거")
        assertEquals("NORMAL", policy(m, "A").mode)
        val restored = validateMeeting(m.toString())
        assertFalse(restored.toString().contains("must-disappear"))
        assertEquals(policy(m, "A"), policy(restored, "A"))
    }

    @Test
    fun duplicatesAndUncertainSpeakerDoNotCount() {
        val m = emptyMeeting()
        m.getJSONArray("claims")
            .put(claim(1))
            .put(claim(1))
            .put(claim(2).put("speakerCertain", false))
        assertEquals(1, policy(m, "A").unsupported)
    }

    @Test(expected = IllegalArgumentException::class)
    fun unknownStatusIsRejected() {
        val m = emptyMeeting()
        m.getJSONArray("claims").put(claim(1).put("status", "FAKE"))
        validateMeeting(m.toString())
    }
}
