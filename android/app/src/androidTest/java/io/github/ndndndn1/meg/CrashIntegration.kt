package io.github.ndndndn1.meg

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CrashIntegration {
    @Test
    fun createPendingForExternalProcessKill() {
        val c = InstrumentationRegistry.getInstrumentation().targetContext
        val archive = SessionArchive(c, onFailure = { throw AssertionError(it) })
        archive.snapshot(emptyMeeting())
        archive.resume()
        archive.pcm(ShortArray(16000) { 123 }, 16000, System.currentTimeMillis())
        archive.awaitIdle()
        c.getSharedPreferences("meg-v2-crash-test", 0)
            .edit()
            .putString("path", archive.path)
            .commit()
        println("MEG_CRASH_READY")
        Thread.sleep(
            60000
        ) // Driver kills only this test process after the marker; no raw user audio.
        archive.finish()
    }

    @Test
    fun verifyRecoveryAfterRelaunch() {
        val c = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = c.getSharedPreferences("meg-v2-crash-test", 0)
        val path = prefs.getString("path", null)!!
        val rows = mutableMapOf<String, Uri>()
        c.contentResolver
            .query(
                android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf("_id", "_display_name", "is_pending"),
                "relative_path=?",
                arrayOf(path),
                null,
            )!!
            .use { cur ->
                while (cur.moveToNext()) {
                    assertEquals(0, cur.getInt(2))
                    rows[cur.getString(1)] =
                        android.content.ContentUris.withAppendedId(
                            android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                            cur.getLong(0),
                        )
                }
            }
        try {
            assertEquals(4, rows.size)
            val bytes =
                c.contentResolver.openInputStream(rows.getValue("audio-0001.wav"))!!.use {
                    it.readBytes()
                }
            assertEquals(32044, bytes.size)
            assertEquals(
                32000,
                java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt(40),
            )
            val meta =
                JSONObject(
                    c.contentResolver
                        .openInputStream(rows.getValue("session.json"))!!
                        .bufferedReader()
                        .use { it.readText() }
                )
            assertTrue(meta.getBoolean("recovered"))
            assertEquals(16000, meta.getJSONArray("audioFiles").getJSONObject(0).getInt("samples"))
            println(
                "MEG_METRIC " +
                    JSONObject().put("forceStopRecovery", true).put("syntheticAudioOnly", true)
            )
        } finally {
            rows.values.forEach { c.contentResolver.delete(it, null, null) }
            prefs.edit().clear().commit()
        }
    }
}
