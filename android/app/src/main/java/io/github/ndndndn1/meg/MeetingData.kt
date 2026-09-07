package io.github.ndndndn1.meg

import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

fun id() = UUID.randomUUID().toString()

fun emptyMeeting() =
    JSONObject()
        .put("schemaVersion", 2)
        .put("listeningState", "IDLE")
        .put("speakerHistory", JSONArray())
        .put("appVersion", "0.2.0")
        .put("speakers", JSONArray())
        .put("claims", JSONArray())
        .put("evidence", JSONArray())

fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }

fun policy(m: JSONObject, speaker: String): Policy {
    val s = m.getJSONArray("speakers").objects().find { it.getString("id") == speaker }
    val baseline = s?.optJSONObject("baseline")
    var p =
        if (baseline != null)
            Policy(
                baseline.optString("mode", "NORMAL"),
                baseline.optInt("unsupported"),
                baseline.optInt("calibrated"),
            )
        else Policy()
    val seen = mutableSetOf<String>()
    for (c in m.getJSONArray("claims").objects().sortedBy { it.getLong("endedAt") }) if (
        c.optString("speakerId") == speaker &&
            c.optBoolean("speakerCertain") &&
            seen.add(c.getString("id")) &&
            c.optString("basis") != "MODEL_KNOWLEDGE"
    )
        p = p.next(c.getString("status"))
    return p
}

fun revise(c: JSONObject, status: String, reason: String) {
    c.getJSONArray("revisions")
        .put(
            JSONObject()
                .put("status", c.getString("status"))
                .put("basis", c.opt("basis"))
                .put("evidenceIds", JSONArray(c.getJSONArray("evidenceIds").toString()))
                .put("reason", c.optString("reason"))
                .put("at", c.optLong("checkedAt"))
        )
    if (status in setOf("EXPERIENCE", "HYPOTHESIS")) c.remove("basis")
    c.put("status", status).put("reason", reason).put("checkedAt", System.currentTimeMillis())
}

fun addEvidence(
    m: JSONObject,
    text: String,
    title: String,
    page: Int? = null,
    hash: String? = null,
) {
    text
        .split(Regex("\\n+|(?<=[.!?。])\\s+"))
        .flatMap { it.chunked(300) }
        .filter { it.isNotBlank() }
        .forEach {
            m.getJSONArray("evidence")
                .put(
                    JSONObject()
                        .put("id", id())
                        .put("text", it)
                        .put("title", title)
                        .put("source", title)
                        .put("version", hash?.let { "SHA-256 $it" } ?: "사용자 제공")
                        .put("documentHash", hash)
                        .put("basis", "DOCUMENT")
                        .put("page", page)
                        .put("checkedAt", System.currentTimeMillis())
                )
        }
}

fun exportMeeting(m: JSONObject, profiles: Boolean): String {
    val copy = JSONObject(m.toString())
    if (!profiles)
        copy.getJSONArray("speakers").objects().forEach {
            it.remove("embedding")
            it.remove("baseline")
        }
    if (profiles) {
        copy.put(
            "profileSnapshots",
            JSONArray(
                m.getJSONArray("speakers").objects().map { s ->
                    val p = policy(m, s.getString("id"))
                    JSONObject(s.toString())
                        .put(
                            "baseline",
                            JSONObject()
                                .put("mode", p.mode)
                                .put("unsupported", p.unsupported)
                                .put("calibrated", p.calibrated),
                        )
                }
            ),
        )
    }
    return copy.toString(2)
}

fun validateMeeting(raw: String): JSONObject {
    require(raw.length <= 10_000_000) { "파일이 너무 큽니다." }
    val m = JSONObject(raw)
    require(m.getInt("schemaVersion") in setOf(1, 2))
    val statuses =
        setOf(
            "GENERAL_KNOWLEDGE",
            "VERIFIED",
            "CONTRADICTED",
            "EXPERIENCE",
            "HYPOTHESIS",
            "UNSUPPORTED ASSERTION",
            "UNVERIFIABLE",
            "PENDING",
            "QUESTION",
            "VALUE JUDGMENT",
            "PROPOSAL",
        )
    fun allowed(o: JSONObject, fields: List<String>): JSONObject {
        val clean = JSONObject()
        fields.forEach { if (o.has(it)) clean.put(it, o.get(it)) }
        return clean
    }
    fun string(o: JSONObject, key: String, max: Int = 10000) {
        require(o.get(key) is String && o.getString(key).length <= max) { "문자열 형식 오류: $key" }
    }
    fun number(o: JSONObject, key: String) {
        require(o.get(key) is Number && o.getDouble(key).isFinite() && o.getDouble(key) >= 0) {
            "숫자 형식 오류: $key"
        }
    }
    for (k in listOf("claims", "speakers", "evidence")) require(
        m.getJSONArray(k).length() <=
            if (k == "speakers") 100 else if (k == "evidence") 50000 else 10000
    )
    val speakers =
        m.getJSONArray("speakers").objects().map { s ->
            string(s, "id", 100)
            string(s, "name", 200)
            s.optJSONArray("embedding")?.let { v ->
                require(v.length() <= 4096)
                for (i in 0 until v.length()) require(
                    v.get(i) is Number && v.getDouble(i).isFinite()
                )
            }
            val clean = allowed(s, listOf("id", "name", "embedding"))
            s.optJSONObject("baseline")?.let { p ->
                require(p.getString("mode") in setOf("NORMAL", "EVIDENCE REQUIRED"))
                number(p, "unsupported")
                number(p, "calibrated")
                clean.put("baseline", allowed(p, listOf("mode", "unsupported", "calibrated")))
            }
            clean
        }
    val evidence =
        m.getJSONArray("evidence").objects().map { e ->
            for (k in listOf("id", "text", "title", "source", "version")) string(e, k)
            number(e, "checkedAt")
            allowed(
                e,
                listOf(
                    "id",
                    "text",
                    "title",
                    "source",
                    "version",
                    "checkedAt",
                    "page",
                    "documentHash",
                    "basis",
                ),
            )
        }
    val claims =
        m.getJSONArray("claims").objects().map { c ->
            for (k in listOf("id", "utteranceId", "text", "reason", "status")) string(c, k, 3000)
            require(c.getString("status") in statuses)
            for (k in listOf("endedAt", "checkedAt")) number(c, k)
            require(
                c.isNull("speakerId") ||
                    speakers.any { it.getString("id") == c.getString("speakerId") }
            )
            val ids = c.getJSONArray("evidenceIds")
            require(ids.length() <= 100)
            for (i in 0 until ids.length()) require(ids.get(i) is String)
            val revisions = c.getJSONArray("revisions")
            require(revisions.length() <= 1000)
            val clean =
                allowed(
                    c,
                    listOf(
                        "id",
                        "utteranceId",
                        "text",
                        "speakerId",
                        "status",
                        "reason",
                        "evidenceIds",
                        "endedAt",
                        "checkedAt",
                    ),
                )
            c.optString("basis")
                .takeIf { it.isNotEmpty() }
                ?.let {
                    require(it in setOf("CALCULATION", "MODEL_KNOWLEDGE", "DOCUMENT", "WEB"))
                    clean.put("basis", it)
                }
            for (k in listOf("attempts", "audioSpans")) {
                val a = c.optJSONArray(k) ?: JSONArray()
                require(a.length() <= 100)
                clean.put(
                    k,
                    JSONArray(
                        a.objects().map { v ->
                            if (k == "attempts") {
                                require(
                                    v.getString("route") in
                                        setOf("CALCULATION", "LOCAL", "OPENROUTER")
                                )
                                string(v, "reason", 1000)
                                string(v, "outcome", 1000)
                                number(v, "startedAt")
                                number(v, "durationMs")
                                for (n in
                                    listOf("inputTokenUpperBound", "retrievalMs", "queueMs")) if (
                                    v.has(n)
                                )
                                    number(v, n)
                                allowed(
                                    v,
                                    listOf(
                                        "route",
                                        "reason",
                                        "outcome",
                                        "startedAt",
                                        "durationMs",
                                        "inputTokenUpperBound",
                                        "retrievalMs",
                                        "queueMs",
                                    ),
                                )
                            } else {
                                string(v, "uri", 3000)
                                string(v, "file", 300)
                                number(v, "startMs")
                                number(v, "endMs")
                                require(v.getDouble("endMs") >= v.getDouble("startMs"))
                                allowed(v, listOf("uri", "file", "startMs", "endMs"))
                            }
                        }
                    ),
                )
            }
            clean
                .put("speakerCertain", c.optBoolean("speakerCertain"))
                .put("spoken", true)
                .put(
                    "revisions",
                    JSONArray(
                        revisions.objects().map { r ->
                            require(r.getString("status") in statuses)
                            string(r, "reason", 3000)
                            number(r, "at")
                            r.optString("basis")
                                .takeIf { it.isNotEmpty() }
                                ?.let {
                                    require(
                                        it in
                                            setOf(
                                                "CALCULATION",
                                                "MODEL_KNOWLEDGE",
                                                "DOCUMENT",
                                                "WEB",
                                            )
                                    )
                                }
                            val refs = r.optJSONArray("evidenceIds") ?: JSONArray()
                            require(refs.length() <= 100)
                            for (i in 0 until refs.length()) require(refs.get(i) is String)
                            allowed(r, listOf("status", "reason", "at", "basis", "evidenceIds"))
                        }
                    ),
                )
            clean
        }
    for (items in listOf(speakers, claims, evidence)) require(
        items.map { it.getString("id") }.distinct().size == items.size
    )
    return emptyMeeting()
        .put("speakers", JSONArray(speakers))
        .put("claims", JSONArray(claims))
        .put("evidence", JSONArray(evidence))
        .put("listeningState", "PAUSED")
        .put(
            "speakerHistory",
            JSONArray(
                (m.optJSONArray("speakerHistory") ?: JSONArray())
                    .also { require(it.length() <= 10000) }
                    .objects()
                    .map { h ->
                        number(h, "at")
                        for (k in listOf("kind", "id", "from", "to")) string(h, k, 200)
                        allowed(h, listOf("at", "kind", "id", "from", "to"))
                    }
            ),
        )
}
