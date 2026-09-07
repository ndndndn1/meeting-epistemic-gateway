package io.github.ndndndn1.meg

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AudioIntegration {
    @Test
    fun koreanSpeechWithModelResident() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = ModelStore(context)
        val llm = LocalLlm()
        llm.load(store.path("llm-1.7b"))
        val bytes = File(context.filesDir, "ko.wav").readBytes()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        while (String(bytes, pos, 4) != "data") pos += 8 + buffer.getInt(pos + 4)
        val length = buffer.getInt(pos + 4) / 2
        val samples = FloatArray(length) { buffer.getShort(pos + 8 + it * 2) / 32768f }
        var transcript = ""
        var attributed = false
        val audio =
            AudioEngine(
                store,
                { false },
                {},
                { text, speaker, _, _, _ ->
                    transcript = text
                    attributed = speaker != null
                },
                { throw AssertionError(it) },
            )
        val start = System.nanoTime()
        try {
            audio.prepare()
            val concurrent =
                InstrumentationRegistry.getArguments().getString("concurrent") == "true"
            val pool = java.util.concurrent.Executors.newSingleThreadExecutor()
            val started = java.util.concurrent.CountDownLatch(1)
            val inference =
                if (concurrent)
                    pool.submit<String> {
                        started.countDown()
                        llm.generate(
                            "<|im_start|>user\n회의 중 가설과 사실의 차이를 한 문장으로 설명하세요. /no_think<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n"
                        )
                    }
                else null
            if (concurrent) started.await()
            val prepared = System.nanoTime()
            audio.process(samples, System.currentTimeMillis())
            val result =
                JSONObject()
                    .put("kind", "single-speaker-upstream-audio-integration")
                    .put("representativeMeeting", false)
                    .put("llmResident", true)
                    .put("concurrentGeneration", concurrent)
                    .put("transcript", transcript)
                    .put("attributed", attributed)
                    .put("prepareMs", (prepared - start) / 1000000)
                    .put("processingMs", (System.nanoTime() - prepared) / 1000000)
            if (inference != null) {
                try {
                    result.put(
                        "llmCompleted",
                        inference.get(30, java.util.concurrent.TimeUnit.SECONDS).isNotBlank(),
                    )
                } catch (_: Exception) {
                    result.put("llmCompleted", false)
                }
            }
            pool.shutdownNow()
            File(
                    context.filesDir,
                    if (concurrent) "audio-concurrent.json" else "audio-integration.json",
                )
                .writeText(result.toString(2))
            assertTrue("Korean audio must produce text", transcript.any { it in '가'..'힣' })
        } finally {
            audio.release()
            llm.close()
        }
    }
}
