package io.github.ndndndn1.meg

import android.content.ContentValues
import android.media.MediaPlayer
import android.net.Uri
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ArchiveIntegration {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver = context.contentResolver

    private fun rows(path: String): Map<String, Uri> {
        val files = mutableMapOf<String, Uri>()
        resolver
            .query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf("_id", MediaStore.MediaColumns.DISPLAY_NAME),
                "${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                arrayOf(path),
                null,
            )!!
            .use { c ->
                while (c.moveToNext()) files[c.getString(1)] =
                    android.content.ContentUris.withAppendedId(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        c.getLong(0),
                    )
            }
        return files
    }

    private fun cleanup(path: String) {
        rows(path).values.forEach { resolver.delete(it, null, null) }
    }

    private fun read(uri: Uri) = resolver.openInputStream(uri)!!.use { it.readBytes() }

    @Test
    fun wholeAudioChunksPauseReplayAndRecovery() {
        val failures = mutableListOf<String>()
        val session = SessionArchive(context, onFailure = { failures.add(it) })
        try {
            val meeting = emptyMeeting()
            meeting.getJSONArray("speakers").put(JSONObject().put("id","A").put("name","합성 화자 A").put("baseline",JSONObject().put("mode","NORMAL").put("unsupported",2).put("calibrated",0)))
            addEvidence(meeting, "보존할 합성 자료", "fixture-kept")
            addEvidence(meeting, "삭제할 합성 자료", "fixture-removed")
            session.snapshot(meeting)
            meeting.getJSONArray("evidence").remove(1)
            session.snapshot(meeting)
            session.resume()
            val start = System.currentTimeMillis()
            val pcm =
                ShortArray(16000) {
                    (kotlin.math.sin(it * 2 * Math.PI * 440 / 16000) * 1000).toInt().toShort()
                }
            for (i in 1..61) session.pcm(pcm, pcm.size, start + i * 1000)
            session.pause()
            session.awaitIdle()
            session.pcm(pcm, pcm.size, start + 62000) // must not save paused sound
            session.resume()
            session.pcm(pcm, pcm.size, start + 71000)
            session.pause()
            session.awaitIdle()
            var spans = JSONArray()
            session.spans(start + 59000, start + 71000) { spans = it }
            session.awaitIdle()
            assertEquals(3, spans.length())
            session.finish()
            session.awaitIdle()
            assertTrue(failures.toString(), failures.isEmpty())
            val files = rows(session.path)
            val waves = files.filterKeys { it.endsWith(".wav") }.toSortedMap()
            assertEquals(3, waves.size)
            val samples = waves.map { (_, uri) ->
                val bytes = read(uri)
                assertEquals("RIFF", String(bytes, 0, 4))
                assertEquals(
                    bytes.size - 44,
                    ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(40),
                )
                (bytes.size - 44) / 2
            }
            assertEquals(listOf(960000, 16000, 16000), samples)
            val log = String(read(files.getValue("events.jsonl")))
            assertEquals(2, log.lineSequence().count { it.contains("LISTENING_RESUMED") })
            assertFalse(log.contains("Authorization"))
            val player = MediaPlayer()
            val done = CountDownLatch(1)
            try {
                player.setDataSource(context, waves.values.last())
                player.setOnPreparedListener {
                    it.setVolume(0f, 0f)
                    it.start()
                }
                player.setOnCompletionListener { done.countDown() }
                player.prepareAsync()
                assertTrue("WAV replay", done.await(6, TimeUnit.SECONDS))
            } finally {
                player.release()
            }
            // Simulate an interrupted header and pending publish on our synthetic WAV only.
            val uri = waves.values.last()
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 1) },
                null,
                null,
            )
            resolver.openFileDescriptor(uri, "rw")!!.use { p ->
                SessionArchive.write(p.fileDescriptor, SessionArchive.header(0))
            }
            val events = files.getValue("events.jsonl")
            resolver.update(
                events,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 1) },
                null,
                null,
            )
            resolver.openFileDescriptor(events, "rw")!!.use { p ->
                android.system.Os.lseek(p.fileDescriptor, 0, android.system.OsConstants.SEEK_END)
                SessionArchive.write(p.fileDescriptor, "{\"partial\":".toByteArray())
            }
            resolver.openOutputStream(files.getValue("meeting.json"), "wt")!!.use {
                it.write("{".toByteArray())
            }
            assertEquals(2, SessionArchive.recover(context, session.path))
            assertEquals(
                32000,
                ByteBuffer.wrap(read(uri)).order(ByteOrder.LITTLE_ENDIAN).getInt(40),
            )
            val restored = JSONObject(String(read(files.getValue("meeting.json"))))
            assertEquals(2, restored.getInt("schemaVersion"))
            assertEquals(2,policy(restored,"A").unsupported)
            assertEquals(1, restored.getJSONArray("evidence").length())
            assertEquals(
                "fixture-kept",
                restored.getJSONArray("evidence").getJSONObject(0).getString("title"),
            )
            assertTrue(
                String(read(events))
                    .lineSequence()
                    .filter { it.isNotBlank() }
                    .all { runCatching { JSONObject(it) }.isSuccess }
            )
        } finally {
            session.finish()
            session.awaitIdle()
            cleanup(session.path)
        }
    }

    @Test
    fun storageFailureStopsAcceptingAudio() {
        val failed = CountDownLatch(1)
        val session =
            SessionArchive(
                context,
                onFailure = { failed.countDown() },
                beforeAudioWrite = {
                    throw android.system.ErrnoException("write", android.system.OsConstants.ENOSPC)
                },
            )
        try {
            session.resume()
            session.pcm(ShortArray(512), 512, System.currentTimeMillis())
            assertTrue(failed.await(5, TimeUnit.SECONDS))
            assertTrue(session.failed)
            session.pause("storage failure")
            session.finish()
            session.awaitIdle()
        } finally {
            session.finish()
            session.awaitIdle()
            cleanup(session.path)
        }
    }
}
