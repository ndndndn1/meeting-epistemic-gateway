package io.github.ndndndn1.meg

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.system.Os
import android.system.OsConstants
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.*
import org.json.JSONArray
import org.json.JSONObject

/** Entire app-received mic stream; audio processing by the device may already be applied. */
class SessionArchive(
    private val context: Context,
    private val onFailure: (String) -> Unit,
    private val beforeAudioWrite: () -> Unit = {},
) {
    companion object {
        const val ROOT = "Download/meeting-epistemic-gateway/"
        const val CHUNK_SAMPLES = 16000 * 60

        fun header(samples: Long): ByteArray =
            ByteBuffer.allocate(44)
                .order(ByteOrder.LITTLE_ENDIAN)
                .put("RIFF".toByteArray())
                .putInt((36 + samples * 2).toInt())
                .put("WAVEfmt ".toByteArray())
                .putInt(16)
                .putShort(1)
                .putShort(1)
                .putInt(16000)
                .putInt(32000)
                .putShort(2)
                .putShort(16)
                .put("data".toByteArray())
                .putInt((samples * 2).toInt())
                .array()

        fun write(fd: java.io.FileDescriptor, bytes: ByteArray) {
            var p = 0
            while (p < bytes.size) {
                val n = Os.write(fd, bytes, p, bytes.size - p)
                check(n > 0)
                p += n
            }
        }

        fun finishWav(p: ParcelFileDescriptor) {
            val samples = maxOf(0, (Os.fstat(p.fileDescriptor).st_size - 44) / 2)
            Os.ftruncate(p.fileDescriptor, 44 + samples * 2)
            Os.lseek(p.fileDescriptor, 0, OsConstants.SEEK_SET)
            write(p.fileDescriptor, header(samples))
            Os.fsync(p.fileDescriptor)
        }

        fun recover(context: Context, prefix: String = ROOT): Int {
            val resolver = context.contentResolver
            var count = 0
            val paths = mutableSetOf<String>()
            resolver
                .query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    arrayOf(
                        "_id",
                        MediaStore.MediaColumns.DISPLAY_NAME,
                        MediaStore.MediaColumns.RELATIVE_PATH,
                    ),
                    android.os.Bundle().apply {
                        putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
                        putString(
                            android.content.ContentResolver.QUERY_ARG_SQL_SELECTION,
                            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.MediaColumns.IS_PENDING}=1",
                        )
                        putStringArray(
                            android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                            arrayOf("$prefix%"),
                        )
                    },
                    null,
                )
                ?.use { c ->
                    while (c.moveToNext()) {
                        paths.add(c.getString(2))
                        val uri =
                            android.content.ContentUris.withAppendedId(
                                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                                c.getLong(0),
                            )
                        if (c.getString(1).endsWith(".wav"))
                            resolver.openFileDescriptor(uri, "rw")!!.use(::finishWav)
                        if (c.getString(1).endsWith(".jsonl"))
                            resolver.openFileDescriptor(uri, "rw")!!.use { p ->
                                val size = Os.fstat(p.fileDescriptor).st_size
                                // Only an interrupted final event can be incomplete. Scan backwards
                                // to newline.
                                var end = size
                                val b = ByteArray(4096)
                                var found = false
                                while (end > 0 && !found) {
                                    val start = maxOf(0, end - b.size)
                                    Os.lseek(p.fileDescriptor, start, OsConstants.SEEK_SET)
                                    val n = Os.read(p.fileDescriptor, b, 0, (end - start).toInt())
                                    for (i in n - 1 downTo 0) if (b[i] == 10.toByte()) {
                                        end = start + i + 1
                                        found = true
                                        break
                                    }
                                    if (!found) end = start
                                }
                                Os.ftruncate(p.fileDescriptor, end)
                                Os.fsync(p.fileDescriptor)
                            }
                        resolver.update(
                            uri,
                            ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                            null,
                            null,
                        )
                        count++
                    }
                }
            for (path in paths) {
                val files = mutableMapOf<String, Uri>()
                resolver
                    .query(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        arrayOf("_id", MediaStore.MediaColumns.DISPLAY_NAME),
                        "${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                        arrayOf(path),
                        null,
                    )
                    ?.use { c ->
                        while (c.moveToNext()) files[c.getString(1)] =
                            android.content.ContentUris.withAppendedId(
                                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                                c.getLong(0),
                            )
                    }
                val events = files["events.jsonl"] ?: continue
                val m = emptyMeeting().put("listeningState", "PAUSED")
                val audioParts = linkedMapOf<String, JSONObject>()
                val claims = linkedMapOf<String, JSONObject>()
                val evidence = linkedMapOf<String, JSONObject>()
                var claimIds: List<String>? = null
                var evidenceIds: List<String>? = null
                resolver.openInputStream(events)!!.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        val e = runCatching { JSONObject(line) }.getOrNull() ?: return@forEach
                        val data = e.optJSONObject("data") ?: return@forEach
                        when (e.optString("type")) {
                            "AUDIO_FILE_STARTED",
                            "AUDIO_FILE_CLOSED" -> audioParts[data.getString("file")] = data
                            "CLAIM_UPDATED" -> {
                                val c = data.getJSONObject("claim")
                                claims[c.getString("id")] = c
                            }
                            "DOCUMENTS_ADDED" ->
                                data.getJSONArray("evidence").objects().forEach {
                                    evidence[it.getString("id")] = it
                                }
                            "DOCUMENTS_REMOVED" -> {
                                val ids = data.getJSONArray("ids")
                                for (i in 0 until ids.length()) evidence.remove(ids.getString(i))
                            }
                            "SPEAKERS_UPDATED" -> {
                                m.put("speakers", data.getJSONArray("speakers"))
                                m.put("speakerHistory", data.optJSONArray("history") ?: JSONArray())
                            }
                            "MEETING_STATE" -> {
                                val a = data.getJSONArray("claimIds")
                                claimIds = (0 until a.length()).map { a.getString(it) }
                                data.optJSONArray("evidenceIds")?.let { b ->
                                    evidenceIds = (0 until b.length()).map { b.getString(it) }
                                }
                            }
                        }
                    }
                }
                m.put(
                        "claims",
                        JSONArray(
                            claims
                                .filterKeys { claimIds == null || it in claimIds!! }
                                .values
                                .toList()
                        ),
                    )
                    .put(
                        "evidence",
                        JSONArray(
                            evidence
                                .filterKeys { evidenceIds == null || it in evidenceIds!! }
                                .values
                                .toList()
                        ),
                    )
                for (c in m.getJSONArray("claims").objects()) if (
                    c.optString("status") == "PENDING"
                )
                    revise(c, "UNVERIFIABLE", "앱 중단으로 검증을 완료하지 못했습니다.")
                for ((name, uri) in files.filterKeys { it.endsWith(".wav") }) {
                    val part = audioParts[name] ?: JSONObject().put("file", name)
                    val samples =
                        resolver.openFileDescriptor(uri, "r")!!.use { p ->
                            maxOf(0, (Os.fstat(p.fileDescriptor).st_size - 44) / 2)
                        }
                    part.put("uri", uri.toString()).put("samples", samples)
                    audioParts[name] = part
                }
                files["session.json"]?.let { uri ->
                    val meta = runCatching {
                        resolver.openInputStream(uri)!!.bufferedReader().use {
                            JSONObject(it.readText())
                        }
                    }
                        .getOrDefault(JSONObject())
                    meta
                        .put("schemaVersion", 2)
                        .put("appVersion", "0.2.0")
                        .put("path", path)
                        .put("audioFiles", JSONArray(audioParts.values.toList()))
                        .put("closed", true)
                        .put("recovered", true)
                    resolver.openFileDescriptor(uri, "rwt")!!.use { p ->
                        write(p.fileDescriptor, meta.toString().toByteArray())
                        Os.fsync(p.fileDescriptor)
                    }
                }
                files["meeting.json"]?.let { uri ->
                    resolver.openFileDescriptor(uri, "rwt")!!.use { p ->
                        write(p.fileDescriptor, m.toString().toByteArray())
                        Os.fsync(p.fileDescriptor)
                    }
                }
                resolver.openFileDescriptor(events, "rw")!!.use { p ->
                    Os.lseek(p.fileDescriptor, 0, OsConstants.SEEK_END)
                    write(
                        p.fileDescriptor,
                        (JSONObject()
                                .put("at", System.currentTimeMillis())
                                .put("type", "RECOVERED_AFTER_INTERRUPTION")
                                .put("data", JSONObject())
                                .toString() + "\n")
                            .toByteArray(),
                    )
                    Os.fsync(p.fileDescriptor)
                }
            }
            return count
        }

        fun savedMeetings(context: Context): List<Pair<String, Uri>> {
            val result = mutableListOf<Pair<String, Uri>>()
            context.contentResolver
                .query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    arrayOf("_id", MediaStore.MediaColumns.RELATIVE_PATH),
                    "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?",
                    arrayOf("$ROOT%", "meeting.json"),
                    "${MediaStore.MediaColumns.DATE_ADDED} DESC",
                )
                ?.use { c ->
                    while (c.moveToNext()) result.add(
                        c.getString(1) to
                            android.content.ContentUris.withAppendedId(
                                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                                c.getLong(0),
                            )
                    )
                }
            return result
        }
    }

    val sessionId = id()
    val path =
        ROOT +
            SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.ROOT).format(Date()) +
            "_" +
            sessionId +
            "/"
    private val resolver = context.contentResolver
    private val queue = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(256))
    @Volatile
    var failed = false
        private set

    @Volatile
    var closed = false
        private set

    @Volatile private var accepting = false
    private var closing = false
    private var wav: ParcelFileDescriptor? = null
    private var wavUri: Uri? = null
    private var samples = 0
    private var sequence = 0
    private val parts = JSONArray()
    private var part: JSONObject? = null
    private val eventsUri = create("events.jsonl", "application/x-ndjson")
    private val metadataUri = create("session.json", "application/json")
    private val meetingUri = create("meeting.json", "application/json")
    private val events = resolver.openFileDescriptor(eventsUri, "rw")!!
    private var aiStarted: Long? = null
    private var lastMeeting = emptyMeeting()

    private fun create(name: String, mime: String): Uri =
        resolver.insert(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, path)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            },
        ) ?: error("다운로드 저장 위치 생성 실패")

    private fun publish(uri: Uri) {
        resolver.update(
            uri,
            ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
            null,
            null,
        )
    }

    private fun fail(e: Exception) {
        if (!failed) {
            failed = true
            accepting = false
            onFailure("녹음·로그 저장 실패: ${e.message} · 듣기를 일시정지합니다.")
        }
    }

    private fun enqueue(block: () -> Unit) {
        if (closed) return
        try {
            queue.execute {
                try {
                    block()
                } catch (e: Exception) {
                    fail(e)
                }
            }
        } catch (e: RejectedExecutionException) {
            fail(e)
        }
    }

    private fun eventNow(type: String, data: JSONObject = JSONObject()) {
        write(
            events.fileDescriptor,
            (JSONObject()
                    .put("at", System.currentTimeMillis())
                    .put("type", type)
                    .put("data", data)
                    .toString() + "\n")
                .toByteArray(),
        )
        Os.fsync(events.fileDescriptor)
    }

    fun event(type: String, data: JSONObject = JSONObject()) {
        val copy = JSONObject(data.toString())
        enqueue { eventNow(type, copy) }
    }

    fun resume() {
        check(!failed && !closed) { "저장 상태를 확인하고 새 회의를 시작하세요." }
        accepting = true
        event("LISTENING_RESUMED")
    }

    fun pcm(input: ShortArray, n: Int, endedAt: Long) {
        if (!accepting || failed) return
        val bytes =
            ByteBuffer.allocate(n * 2)
                .order(ByteOrder.LITTLE_ENDIAN)
                .apply { for (i in 0 until n) putShort(input[i]) }
                .array()
        enqueue {
            var offset = 0
            while (offset < n) {
                if (wav == null) {
                    val name = "audio-${(++sequence).toString().padStart(4,'0')}.wav"
                    wavUri = create(name, "audio/wav")
                    wav = resolver.openFileDescriptor(wavUri!!, "rw")!!
                    write(wav!!.fileDescriptor, header(0))
                    samples = 0
                    part =
                        JSONObject()
                            .put("uri", wavUri.toString())
                            .put("file", name)
                            .put("startedAt", endedAt - (n - offset) * 1000L / 16000)
                            .put("samples", 0)
                    parts.put(part)
                    eventNow("AUDIO_FILE_STARTED", JSONObject(part.toString()))
                }
                val take = minOf(n - offset, CHUNK_SAMPLES - samples)
                beforeAudioWrite()
                write(wav!!.fileDescriptor, bytes.copyOfRange(offset * 2, (offset + take) * 2))
                samples += take
                offset += take
                part!!.put("samples", samples)
                if (samples == CHUNK_SAMPLES) finishPart()
            }
            bytes.fill(0)
        }
    }

    private fun finishPart() {
        val p = wav ?: return
        try {
            finishWav(p)
            publish(wavUri!!)
            eventNow("AUDIO_FILE_CLOSED", JSONObject(part.toString()))
        } finally {
            p.close()
            wav = null
            wavUri = null
            part = null
        }
    }

    fun pause(reason: String = "사용자 일시정지") {
        accepting = false
        enqueue {
            finishPart()
            eventNow("LISTENING_PAUSED", JSONObject().put("reason", reason))
            metadata()
        }
    }

    fun spans(start: Long, end: Long, callback: (JSONArray) -> Unit) {
        enqueue {
            val refs =
                JSONArray(
                    parts.objects().mapNotNull { p ->
                        val at = p.getLong("startedAt")
                        val duration = p.getLong("samples") * 1000L / 16000
                        val a = maxOf(0, start - at)
                        val b = minOf(duration, end - at)
                        if (b > a)
                            JSONObject()
                                .put("uri", p.getString("uri"))
                                .put("file", p.getString("file"))
                                .put("startMs", a)
                                .put("endMs", b)
                        else null
                    }
                )
            callback(refs)
        }
    }

    fun ai(active: Boolean) {
        enqueue {
            if (active && aiStarted == null) {
                aiStarted = System.currentTimeMillis()
                eventNow("AI_SPEECH_STARTED")
            } else if (!active && aiStarted != null) {
                eventNow("AI_SPEECH_ENDED", JSONObject().put("startedAt", aiStarted))
                aiStarted = null
            }
        }
    }

    fun snapshot(meeting: JSONObject) {
        val snapshot = JSONObject(exportMeeting(meeting, false))
        // Retain an explicitly connected carry-over policy, without persisting voice embeddings.
        val baselines =
            meeting.getJSONArray("speakers").objects().associate {
                it.getString("id") to it.optJSONObject("baseline")
            }
        snapshot.getJSONArray("speakers").objects().forEach { s ->
            baselines[s.getString("id")]?.let { s.put("baseline", JSONObject(it.toString())) }
        }
        enqueue {
            val oldClaims =
                lastMeeting.getJSONArray("claims").objects().associate {
                    it.getString("id") to it.toString()
                }
            val changed =
                snapshot.getJSONArray("claims").objects().filter {
                    oldClaims[it.getString("id")] != it.toString()
                }
            for (c in changed) {
                val ids = c.getJSONArray("evidenceIds").toString()
                eventNow(
                    "CLAIM_UPDATED",
                    JSONObject()
                        .put("claim", c)
                        .put(
                            "evidence",
                            JSONArray(
                                snapshot.getJSONArray("evidence").objects().filter {
                                    ids.contains(it.getString("id"))
                                }
                            ),
                        ),
                )
            }
            if (
                snapshot.getJSONArray("speakers").toString() !=
                    lastMeeting.getJSONArray("speakers").toString() ||
                    snapshot.optJSONArray("speakerHistory").toString() !=
                        lastMeeting.optJSONArray("speakerHistory").toString()
            )
                eventNow(
                    "SPEAKERS_UPDATED",
                    JSONObject()
                        .put("speakers", snapshot.getJSONArray("speakers"))
                        .put("history", snapshot.optJSONArray("speakerHistory")),
                )
            val oldEvidenceIds =
                lastMeeting
                    .getJSONArray("evidence")
                    .objects()
                    .map { it.getString("id") }
                    .toHashSet()
            val added =
                snapshot.getJSONArray("evidence").objects().filter {
                    it.getString("id") !in oldEvidenceIds
                }
            if (added.isNotEmpty())
                eventNow("DOCUMENTS_ADDED", JSONObject().put("evidence", JSONArray(added)))
            val currentIds =
                snapshot.getJSONArray("evidence").objects().map { it.getString("id") }.toSet()
            val removed = oldEvidenceIds.filter { it !in currentIds }
            if (removed.isNotEmpty())
                eventNow("DOCUMENTS_REMOVED", JSONObject().put("ids", JSONArray(removed)))
            eventNow(
                "MEETING_STATE",
                JSONObject()
                    .put(
                        "claimIds",
                        JSONArray(
                            snapshot.getJSONArray("claims").objects().map { it.getString("id") }
                        ),
                    )
                    .put("listeningState", snapshot.optString("listeningState")),
            )
            lastMeeting = snapshot
            saveJson(meetingUri, snapshot)
            metadata()
        }
    }

    private fun saveJson(uri: Uri, data: JSONObject) {
        resolver.openFileDescriptor(uri, "rwt")!!.use { p ->
            write(p.fileDescriptor, data.toString().toByteArray())
            Os.fsync(p.fileDescriptor)
        }
    }

    private fun metadata() {
        saveJson(
            metadataUri,
            JSONObject()
                .put("schemaVersion", 2)
                .put("appVersion", "0.2.0")
                .put("sessionId", sessionId)
                .put("path", path)
                .put(
                    "audioFormat",
                    "16000Hz PCM signed16 mono WAV; device audio processing may apply",
                )
                .put("audioFiles", parts)
                .put("failed", failed)
                .put("closed", closed),
        )
    }

    @Synchronized
    fun finish() {
        if (closing || closed) return
        closing = true
        accepting = false
        enqueue {
            try {
                finishPart()
                if (aiStarted != null) {
                    eventNow("AI_SPEECH_ENDED", JSONObject().put("startedAt", aiStarted))
                    aiStarted = null
                }
                eventNow("MEETING_ENDED")
                closed = true
                metadata()
                publish(eventsUri)
                publish(metadataUri)
                publish(meetingUri)
            } finally {
                events.close()
                queue.shutdown()
            }
        }
    }

    fun awaitIdle() {
        if (queue.isShutdown) {
            queue.awaitTermination(15, TimeUnit.SECONDS)
        } else
            try {
                queue.submit {}.get(15, TimeUnit.SECONDS)
            } catch (_: RejectedExecutionException) {
                queue.awaitTermination(15, TimeUnit.SECONDS)
            }
    }
}
