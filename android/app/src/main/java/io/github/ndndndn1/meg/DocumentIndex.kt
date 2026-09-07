package io.github.ndndndn1.meg

import org.json.JSONArray
import org.json.JSONObject

/** Imported evidence is indexed once; claim work scans cached normalized paragraphs, not PDFs. */
object DocumentIndex {
    private val index = linkedMapOf<String, Pair<String, String>>()

    @Synchronized
    fun clear() {
        index.clear()
    }

    @Synchronized
    fun add(documents: JSONArray) {
        for (d in documents.objects()) {
            val key = d.getString("id")
            val text = d.getString("text")
            if (index[key]?.first != text) index[key] = text to text.lowercase()
        }
        while (index.size > 20000) index.remove(index.keys.first())
    }

    @Synchronized
    fun retrieve(text: String, documents: JSONArray): JSONArray {
        add(documents)
        val tokens =
            Regex("[가-힣a-zA-Z0-9]{2,}")
                .findAll(text)
                .map {
                    it.value.lowercase().let { t ->
                        if (t.length > 2) t.replace(Regex("(은|는|이|가|을|를|에서|으로)$"), "") else t
                    }
                }
                .toList()
        return JSONArray(
            documents
                .objects()
                .map { d -> d to tokens.count { index[d.getString("id")]!!.second.contains(it) } }
                .filter { it.second > 0 }
                .sortedByDescending { it.second }
                .take(5)
                .map { it.first }
        )
    }

    fun bounded(text: String, documents: JSONArray, system: String): Triple<JSONArray, Int, Long> {
        val started = System.nanoTime()
        val evidence = retrieve(text, documents)
        fun count() =
            (system +
                    JSONObject()
                        .put("utterance", text)
                        .put("evidence", evidence)
                        .put("hasEvidenceScope", evidence.length() > 0)
                        .toString())
                .toByteArray()
                .size + 128
        while (evidence.length() > 0 && count() > 2800) evidence.remove(evidence.length() - 1)
        return Triple(evidence, count(), (System.nanoTime() - started) / 1000000)
    }
}
