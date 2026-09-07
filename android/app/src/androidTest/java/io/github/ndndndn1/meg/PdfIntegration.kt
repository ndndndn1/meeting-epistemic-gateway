package io.github.ndndndn1.meg

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PdfIntegration {
    @Test
    fun syntheticPdfExtractionIndexAndPromptBudget() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        PDFBoxResourceLoader.init(context)
        val system = context.assets.open("system-prompt.txt").bufferedReader().use { it.readText() }
        for (kind in listOf("light", "dense")) {
            val file = File(context.filesDir, "meg-v2-$kind.pdf")
            val began = System.nanoTime()
            val m = emptyMeeting()
            val hash =
                java.security.MessageDigest.getInstance("SHA-256")
                    .digest(file.readBytes())
                    .joinToString("") { "%02x".format(it) }
            PDDocument.load(file).use { pdf ->
                for (i in 1..pdf.numberOfPages) {
                    val stripper = PDFTextStripper()
                    stripper.startPage = i
                    stripper.endPage = i
                    addEvidence(m, stripper.getText(pdf), file.name, i, hash)
                }
            }
            DocumentIndex.add(m.getJSONArray("evidence"))
            val indexMs = (System.nanoTime() - began) / 1000000
            val (evidence, bound, retrievalMs) =
                DocumentIndex.bounded(
                    "Orion launch budget is 120 million won",
                    m.getJSONArray("evidence"),
                    system,
                )
            assertTrue(evidence.length() > 0)
            assertTrue(bound <= 2800)
            assertTrue(
                evidence.objects().all {
                    it.getString("documentHash") == hash && it.getInt("page") > 0
                }
            )
            println(
                "MEG_METRIC " +
                    JSONObject()
                        .put("fixture", kind)
                        .put("bytes", file.length())
                        .put("indexMs", indexMs)
                        .put("retrievalMs", retrievalMs)
                        .put("inputTokenUpperBound", bound)
                        .put("paragraphs", m.getJSONArray("evidence").length())
            )
        }
    }

    @Test
    fun actualFiveSecondCancellationBudget() {
        val r = AnalysisRouting()
        var cancelled = false
        var remoteCalls = 0
        val attempts = org.json.JSONArray()
        val began = System.nanoTime()
        try {
            assertEquals(
                "cloud",
                r.run(
                    ExecutionMode.AUTO,
                    true,
                    1000,
                    attempts,
                    local = {
                        Thread.sleep(8000)
                        "late"
                    },
                    cancelLocal = { cancelled = true },
                    remote = {
                        remoteCalls++
                        "cloud"
                    },
                    warning = {},
                ),
            )
            val elapsed = (System.nanoTime() - began) / 1000000
            assertTrue(cancelled)
            assertEquals(1, remoteCalls)
            assertTrue(elapsed in 4900..6500)
            println(
                "MEG_METRIC " +
                    JSONObject().put("fiveSecondSwitchMs", elapsed).put("attempts", attempts)
            )
        } finally {
            r.close()
        }
    }
}
