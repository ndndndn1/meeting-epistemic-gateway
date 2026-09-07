package io.github.ndndndn1.meg

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

class OpenRouter(context: Context) {
    private val prefs = context.getSharedPreferences("meg-auth", Context.MODE_PRIVATE)
    private var verifier: String? = null
    private var started = 0L
    private val secret: SecretKey by lazy {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!ks.containsAlias("meg-openrouter")) {
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(
                    KeyGenParameterSpec.Builder(
                            "meg-openrouter",
                            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                        )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build()
                )
                generateKey()
            }
        }
        ks.getKey("meg-openrouter", null) as SecretKey
    }

    private fun encode(b: ByteArray) =
        Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    fun authUrl(): String {
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        verifier = encode(bytes)
        started = System.currentTimeMillis()
        val challenge =
            encode(MessageDigest.getInstance("SHA-256").digest(verifier!!.toByteArray()))
        return "https://openrouter.ai/auth?code_challenge=$challenge&code_challenge_method=S256&key_label=Meeting%20Epistemic%20Gateway"
    }

    fun exchange(code: String) {
        check(verifier != null && System.currentTimeMillis() - started < 600000) { "로그인이 만료됐습니다." }
        val v = verifier
        verifier = null
        val data =
            request(
                "auth/keys",
                JSONObject()
                    .put("code", code.trim())
                    .put("code_verifier", v)
                    .put("code_challenge_method", "S256"),
                false,
            )
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secret)
        val encrypted = cipher.doFinal(data.getString("key").toByteArray())
        prefs
            .edit()
            .putString("cipher", encode(encrypted))
            .putString("iv", encode(cipher.iv))
            .apply()
    }

    private fun key(): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            secret,
            GCMParameterSpec(128, Base64.decode(prefs.getString("iv", null), Base64.URL_SAFE)),
        )
        return String(
            cipher.doFinal(Base64.decode(prefs.getString("cipher", null), Base64.URL_SAFE))
        )
    }

    fun connected() = prefs.contains("cipher")

    fun disconnect() {
        prefs.edit().clear().apply()
        verifier = null
    }

    fun request(path: String, body: JSONObject? = null, auth: Boolean = true): JSONObject {
        val c = URL("https://openrouter.ai/api/v1/$path").openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15000
            c.readTimeout = 25000
            if (auth) c.setRequestProperty("Authorization", "Bearer ${key()}")
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("X-Title", "Meeting Epistemic Gateway")
            if (body != null) {
                c.requestMethod = "POST"
                c.doOutput = true
                c.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            check(c.responseCode in 200..299) {
                when (c.responseCode) {
                    401 -> "OpenRouter 로그인 만료"
                    402 -> "OpenRouter 잔액 부족"
                    429 -> "OpenRouter 요청 한도 초과"
                    else -> "OpenRouter 요청 실패 (${c.responseCode})"
                }
            }
            return JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        } finally {
            c.disconnect()
        }
    }
}
