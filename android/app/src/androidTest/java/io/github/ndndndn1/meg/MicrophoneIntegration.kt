package io.github.ndndndn1.meg

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real ambient audio remains ONLY in the phone's Downloads archive; never exported by this test.
 */
@RunWith(AndroidJUnit4::class)
class MicrophoneIntegration {
    private fun call(a: MainActivity, name: String, vararg args: Any?) {
        val method =
            MainActivity::class.java.declaredMethods.first {
                it.name == name && it.parameterCount == args.size
            }
        method.isAccessible = true
        method.invoke(a, *args)
    }

    private fun field(a: MainActivity, name: String): Any? =
        MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(a)

    @Test
    fun realMicPauseResumeBackgroundAndSavedState() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        var audio: AudioEngine? = null
        var archive: SessionArchive? = null
        try {
            scenario.onActivity { a -> audio = field(a, "audio") as AudioEngine }
            audio!!.prepare()
            scenario.onActivity { a ->
                call(a, "setReady", true)
                call(a, "startMeeting")
                archive = field(a, "archive") as SessionArchive?
            }
            assertNotNull(archive)
            Thread.sleep(1800)
            scenario.onActivity { a -> call(a, "pauseMeeting", "instrumentation pause") }
            archive!!.awaitIdle()
            val pauseAt = System.currentTimeMillis()
            Thread.sleep(1200)
            scenario.onActivity { a ->
                assertSame(archive, field(a, "archive"))
                call(a, "startMeeting")
            }
            Thread.sleep(1800)
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            archive!!.awaitIdle()
            scenario.onActivity { a ->
                assertFalse(
                    MainActivity::class
                        .java
                        .getDeclaredMethod("getRunning")
                        .apply { isAccessible = true }
                        .invoke(a) as Boolean
                )
                call(a, "endMeeting")
            }
            archive!!.awaitIdle()
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val meta =
                SessionArchive.savedMeetings(context).find { it.first == archive!!.path }
                    ?: error("meeting archive missing")
            val m =
                JSONObject(
                    context.contentResolver.openInputStream(meta.second)!!.bufferedReader().use {
                        it.readText()
                    }
                )
            assertEquals("ENDED", m.getString("listeningState"))
            val metadataUri =
                SessionArchive::class
                    .java
                    .getDeclaredField("metadataUri")
                    .apply { isAccessible = true }
                    .get(archive) as android.net.Uri
            val metadata =
                JSONObject(
                    context.contentResolver.openInputStream(metadataUri)!!.bufferedReader().use {
                        it.readText()
                    }
                )
            val parts = metadata.getJSONArray("audioFiles").objects()
            assertEquals(2, parts.size)
            assertTrue(parts[1].getLong("startedAt") >= pauseAt + 1000)
            val totalSamples = parts.sumOf { it.getLong("samples") }
            assertTrue(totalSamples in 32000..96000)
            assertFalse(archive!!.failed)
            println(
                "MEG_METRIC " +
                    JSONObject()
                        .put("realMicPauseResume", true)
                        .put("backgroundPause", true)
                        .put("privateAudioExported", false)
            )
        } finally {
            scenario.close()
        }
    }
}
