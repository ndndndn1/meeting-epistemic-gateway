package io.github.ndndndn1.meg

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import org.json.JSONObject

class ModelStore(private val context: Context) {
    val folder = File(context.filesDir, "models").apply { mkdirs() }
    val manifest =
        JSONObject(context.assets.open("models.json").bufferedReader().use { it.readText() })

    fun path(id: String) = File(folder, id).absolutePath

    fun prepare(llm: String, progress: (String) -> Unit) {
        val assets = manifest.getJSONArray("assets")
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            val id = a.getString("id")
            if (llm == "tts" && !id.startsWith("tts-")) continue
            if (llm != "tts" && id.startsWith("tts-")) continue
            if (id.startsWith("llm-") && id != llm) continue
            val file = File(folder, id)
            val expected = a.getString("sha256")
            if (file.exists() && file.length() == a.getLong("bytes") && digest(file) == expected)
                continue
            val temp = File(folder, "$id.part")
            var connection: HttpURLConnection? = null
            try {
                connection = URL(a.getString("url")).openConnection() as HttpURLConnection
                connection.connectTimeout = 20000
                connection.readTimeout = 60000
                check(connection.responseCode == 200) { "모델 다운로드 실패: ${connection.responseCode}" }
                var count = 0L
                connection.inputStream.use { input ->
                    temp.outputStream().use { out ->
                        val b = ByteArray(1024 * 1024)
                        while (true) {
                            val n = input.read(b)
                            if (n < 0) break
                            out.write(b, 0, n)
                            count += n
                            progress(
                                "$id 다운로드 ${count/1048576}/${a.getLong("bytes")/1048576} MB · Wi-Fi 권장"
                            )
                        }
                    }
                }
                check(digest(temp) == expected) { "모델 무결성 확인 실패" }
                check(temp.renameTo(file)) { "모델 저장 실패" }
            } finally {
                connection?.disconnect()
                temp.delete()
            }
        }
    }

    private fun digest(file: File): String {
        val d = MessageDigest.getInstance("SHA-256")
        file.inputStream().use {
            val b = ByteArray(1024 * 1024)
            while (true) {
                val n = it.read(b)
                if (n < 0) break
                d.update(b, 0, n)
            }
        }
        return d.digest().joinToString("") { "%02x".format(it) }
    }
}
