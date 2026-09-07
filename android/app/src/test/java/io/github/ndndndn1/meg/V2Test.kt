package io.github.ndndndn1.meg

import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class V2Test {
    @Test
    fun sharedVerdicts() {
        val cases =
            JSONArray(javaClass.classLoader!!.getResource("verdict-cases.json")!!.readText())
        for (c in cases.objects()) assertEquals(
            c.getString("name"),
            c.getString("expected"),
            Verdicts.parse(c.getJSONObject("raw").toString(), c.getJSONArray("evidence"))
                .getJSONObject(0)
                .getString("status"),
        )
    }

    @Test
    fun sharedArithmetic() {
        val cases =
            JSONArray(javaClass.classLoader!!.getResource("arithmetic-cases.json")!!.readText())
        for (c in cases.objects()) assertEquals(
            c.getString("text"),
            if (c.isNull("status")) null else c.getString("status"),
            Calculation.analyze(c.getString("text"))?.first?.getJSONObject(0)?.getString("status"),
        )
    }

    @Test
    fun timeoutCancelsAndReturnsOnlyCloud() {
        val routing = AnalysisRouting(30)
        val cancelled = AtomicBoolean()
        var cloud = 0
        val trace = JSONArray()
        try {
            assertEquals(
                "cloud",
                routing.run(
                    ExecutionMode.AUTO,
                    true,
                    1,
                    trace,
                    local = {
                        Thread.sleep(200)
                        "late"
                    },
                    cancelLocal = { cancelled.set(true) },
                    remote = {
                        cloud++
                        "cloud"
                    },
                    warning = {},
                ),
            )
            assertTrue(cancelled.get())
            assertEquals(1, cloud)
            assertEquals(2, trace.length())
        } finally {
            routing.close()
        }
    }

    @Test
    fun cloudErrorsDoNotRetryOrChangeModel() {
        for (error in listOf("401", "402", "429", "network")) {
            val r = AnalysisRouting()
            var calls = 0
            try {
                assertEquals(
                    "local",
                    r.run(
                        ExecutionMode.AUTO,
                        true,
                        7500,
                        JSONArray(),
                        local = { "local" },
                        cancelLocal = {},
                        remote = {
                            calls++
                            throw IllegalStateException(error)
                        },
                        warning = {},
                    ),
                )
                assertEquals(1, calls)
            } finally {
                r.close()
            }
        }
    }

    @Test
    fun v1LoadsAndModelKnowledgeCannotChangePolicy() {
        val m = emptyMeeting().put("schemaVersion", 1)
        assertEquals(2, validateMeeting(m.toString()).getInt("schemaVersion"))
        m.getJSONArray("claims")
            .put(
                JSONObject()
                    .put("id", "1")
                    .put("speakerId", "a")
                    .put("speakerCertain", true)
                    .put("endedAt", 1)
                    .put("status", "VERIFIED")
                    .put("basis", "MODEL_KNOWLEDGE")
            )
        assertEquals(0, policy(m, "a").calibrated)
    }
}
