package io.github.ndndndn1.meg

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.util.Locale
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    private var meeting by mutableStateOf(emptyMeeting())
    private var notice by mutableStateOf("모델 준비 후 회의를 시작하세요.")
    private var busy by mutableStateOf(false)
    private var running by mutableStateOf(false)
    private var ready by mutableStateOf(false)
    private var tab by mutableStateOf(0)
    private lateinit var localVoice: LocalVoice
    private lateinit var models: ModelStore
    private lateinit var router: OpenRouter
    private lateinit var analyst: Analyst
    private lateinit var audio: AudioEngine
    private lateinit var tts: TextToSpeech
    private val work = Executors.newSingleThreadExecutor()
    @Volatile private var epoch = 0
    @Volatile private var speaking = false
    private var interrupted = false
    private var currentSpeech = ""
    private var speechToken = ""
    private val speechHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var stoppedSpeechAt = 0L
    private val speechPoll =
        object : Runnable {
            override fun run() {
                if (running && !speaking) {
                    val c =
                        meeting.getJSONArray("claims").objects().firstOrNull {
                            !it.optBoolean("spoken") &&
                                it.optBoolean("speakerCertain") &&
                                it.optString("status") in
                                    setOf("UNSUPPORTED ASSERTION", "CONTRADICTED") &&
                                it.optLong("endedAt") > stoppedSpeechAt &&
                                System.currentTimeMillis() - it.optLong("endedAt") < 30000
                        }
                    if (c != null) {
                        say(
                            "잠시 사실관계를 확인하겠습니다. " +
                                if (c.getString("status") == "CONTRADICTED")
                                    "현재 자료와 충돌합니다. 화면의 출처를 확인해 주세요."
                                else "현재 자료에서 근거를 찾지 못했습니다. 근거나 경험, 추정 여부를 알려주세요."
                        )
                        change { m ->
                            m.getJSONArray("claims")
                                .objects()
                                .find { it.getString("id") == c.getString("id") }
                                ?.put("spoken", true)
                        }
                    }
                }
                speechHandler.postDelayed(this, 250)
            }
        }
    private val speechStop = Runnable {
        tts.stop()
        localVoice.stop()
        speaking = false
    }

    private var includeProfiles = false
    private var remote by mutableStateOf(false)
    private var web by mutableStateOf(true)
    private var model by mutableStateOf("")
    private var catalog by mutableStateOf(listOf<Pair<String, String>>())
    private var authCode by mutableStateOf("")
    private var savedText = ""
    private val permission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            if (it) startMeeting() else notice = "마이크 권한이 필요합니다."
        }
    private val save =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri
            ->
            if (uri != null)
                try {
                    contentResolver.openOutputStream(uri, "wt")!!.bufferedWriter().use {
                        it.write(savedText)
                    }
                    notice = "선택한 위치에 저장했습니다."
                } catch (e: Exception) {
                    notice = e.message ?: "저장 실패"
                }
        }
    private val load =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null)
                background {
                    val data =
                        contentResolver.openInputStream(uri)!!.bufferedReader().use {
                            it.readText()
                        }
                    val m = validateMeeting(data)
                    runOnUiThread {
                        endMeeting()
                        meeting = m
                        notice = "기록을 불러왔습니다. 저장된 프로필 연결은 참가자 화면에서 선택하세요."
                    }
                }
        }
    private val addDocument =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null)
                background {
                    val name = uri.lastPathSegment ?: "회의 자료"
                    val data = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                    require(data.size < 25_000_000) { "파일당 25MB까지 지원합니다." }
                    val chunks = mutableListOf<Pair<Int?, String>>()
                    if (contentResolver.getType(uri) == "application/pdf") {
                        PDDocument.load(data).use { pdf ->
                            require(pdf.numberOfPages <= 200)
                            for (i in 1..pdf.numberOfPages) {
                                val stripper = PDFTextStripper()
                                stripper.startPage = i
                                stripper.endPage = i
                                val t = stripper.getText(pdf)
                                if (t.isNotBlank()) chunks.add(i to t)
                            }
                        }
                        require(chunks.isNotEmpty()) { "텍스트가 없는 PDF입니다. OCR은 지원하지 않습니다." }
                    } else chunks.add(null to data.toString(Charsets.UTF_8))
                    runOnUiThread {
                        change { m -> chunks.forEach { addEvidence(m, it.second, name, it.first) } }
                        notice = "자료를 추가했습니다."
                    }
                }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!android.os.Build.MODEL.startsWith("SM-S928")) {
            android.widget.Toast.makeText(
                    this,
                    "Galaxy S24 Ultra 전용 APK입니다.",
                    android.widget.Toast.LENGTH_LONG,
                )
                .show()
            finish()
            return
        }
        speechHandler.post(speechPoll)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        PDFBoxResourceLoader.init(this)
        models = ModelStore(this)
        localVoice = LocalVoice(models)
        router = OpenRouter(this)
        analyst = Analyst(this, LocalLlm(), router)
        tts =
            TextToSpeech(this) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    tts.language = Locale.KOREAN
                    val voice =
                        tts.voices?.firstOrNull {
                            it.locale.language == "ko" && !it.isNetworkConnectionRequired
                        }
                    if (voice != null) tts.voice = voice else notice = "오프라인 한국어 음성을 설치해 주세요."
                }
            }
        tts.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}

                override fun onDone(id: String?) {
                    if (id == speechToken) speaking = false
                }

                @Deprecated("legacy")
                override fun onError(id: String?) {
                    if (id == speechToken) speaking = false
                }
            }
        )
        audio =
            AudioEngine(
                models,
                { speaking },
                {
                    runOnUiThread {
                        if (speaking && !interrupted) {
                            interrupted = true
                            say("제 발언을 들어주시고 끊지 말아주세요. $currentSpeech", false)
                        }
                    }
                },
                { text, who, embedding, at ->
                    runOnUiThread {
                        if (running) {
                            if (
                                who != null &&
                                    !meeting.getJSONArray("speakers").objects().any {
                                        it.getString("id") == who
                                    }
                            )
                                change {
                                    it.getJSONArray("speakers")
                                        .put(
                                            JSONObject()
                                                .put("id", who)
                                                .put(
                                                    "name",
                                                    "화자 ${'A'+it.getJSONArray("speakers").length()}",
                                                )
                                                .put(
                                                    "embedding",
                                                    JSONArray(
                                                        embedding?.map { v -> v.toDouble() }
                                                            ?: emptyList<Double>()
                                                    ),
                                                )
                                        )
                                }
                            submit(text, who, at)
                        }
                    }
                },
                { error ->
                    runOnUiThread {
                        notice = error
                        running = false
                        audio.stop()
                    }
                },
            )
        setContent {
            MaterialTheme(
                colorScheme =
                    lightColorScheme(
                        primary = Color(0xff285343),
                        secondary = Color(0xff81985f),
                        background = Color(0xfff5f6f2),
                    )
            ) {
                Surface(Modifier.fillMaxSize()) { Screen() }
            }
        }
    }

    private fun change(f: (JSONObject) -> Unit) {
        val m = JSONObject(meeting.toString())
        f(m)
        meeting = m
    }

    private fun background(block: () -> Unit) {
        busy = true
        work.execute {
            try {
                block()
            } catch (e: Exception) {
                runOnUiThread { notice = e.message ?: "작업 실패" }
            } finally {
                runOnUiThread { busy = false }
            }
        }
    }

    private fun prepare(which: String) {
        background {
            models.prepare(which) { runOnUiThread { notice = it } }
            audio.prepare()
            analyst.local.load(models.path(which))
            analyst.localReady = true
            runOnUiThread {
                ready = true
                notice = "로컬 음성·화자·언어 모델 준비 완료. 성능은 평가 전입니다."
            }
        }
    }

    private fun startMeeting() {
        if (!ready) {
            notice = "먼저 모델을 준비하세요."
            tab = 3
            return
        }
        if (
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED
        ) {
            permission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        try {
            audio.start()
            running = true
            notice = "듣고 있습니다. 원음은 저장하지 않습니다."
        } catch (e: Exception) {
            notice = e.message ?: "마이크 시작 실패"
        }
    }

    private fun endMeeting() {
        speechHandler.removeCallbacks(speechStop)
        stoppedSpeechAt = System.currentTimeMillis()
        epoch++
        analyst.local.cancel()
        running = false
        audio.stop()
        audio.clearProfiles()
        tts.stop()
        localVoice.stop()
        speaking = false
        meeting = emptyMeeting()
        notice = "회의 종료. 미저장 기록과 화자 정보를 폐기했습니다."
    }

    private fun say(text: String, reset: Boolean = true) {
        if (reset) {
            speechHandler.removeCallbacks(speechStop)
            speechHandler.postDelayed(speechStop, 11000)
        }
        val v = tts.voice
        if (v == null || v.locale.language != "ko" || v.isNetworkConnectionRequired) {
            if (localVoice.ready) {
                if (reset) interrupted = false
                currentSpeech = text
                speaking = true
                localVoice.speak(text) { runOnUiThread { speaking = false } }
                return
            }
            notice = "오프라인 한국어 음성이 없어 화면에만 표시합니다."
            return
        }
        if (reset) interrupted = false
        currentSpeech = text
        speechToken = id()
        speaking = true
        tts.setSpeechRate(1.15f)
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, speechToken)
    }

    private fun submit(
        text: String,
        speaker: String?,
        endedAt: Long = System.currentTimeMillis(),
        replaceId: String? = null,
    ) {
        if (text.isBlank()) return
        if (
            meeting.getJSONArray("claims").objects().count {
                it.optString("status") == "PENDING"
            } >= 4
        ) {
            notice = "검증 대기열이 가득 찼습니다. 잠시 회의를 멈춰 주세요."
            return
        }
        val generation = epoch
        val claimId = replaceId ?: id()
        val docs = JSONArray(meeting.getJSONArray("evidence").toString())
        analyst.useRemote = remote
        analyst.web = web
        analyst.model = model
        change { m ->
            if (replaceId == null)
                m.getJSONArray("claims")
                    .put(
                        JSONObject()
                            .put("id", claimId)
                            .put("utteranceId", claimId)
                            .put("text", text)
                            .put("speakerId", speaker ?: JSONObject.NULL)
                            .put("speakerCertain", speaker != null)
                            .put("status", "PENDING")
                            .put("reason", "검증 중")
                            .put("evidenceIds", JSONArray())
                            .put("endedAt", endedAt)
                            .put("checkedAt", endedAt)
                            .put("revisions", JSONArray())
                            .put("spoken", false)
                    )
            else
                m.getJSONArray("claims")
                    .objects()
                    .find { it.getString("id") == claimId }
                    ?.let { revise(it, "PENDING", "이의제기: 재검증 중") }
        }
        background {
            if (epoch != generation) return@background
            analyst.useRemote = remote
            analyst.web = web
            analyst.model = model
            val start = System.nanoTime()
            try {
                val (results, evidence) = analyst.analyze(text, docs)
                runOnUiThread {
                    if (epoch != generation) return@runOnUiThread
                    change { m ->
                        val old =
                            m.getJSONArray("claims").objects().find {
                                it.getString("id") == claimId
                            } ?: return@change
                        val revised =
                            results.objects().mapIndexed { i, c ->
                                JSONObject(old.toString())
                                    .put(
                                        "id",
                                        if (replaceId != null && i == 0) claimId else "$claimId:$i",
                                    )
                                    .put("text", c.getString("text"))
                                    .put("status", c.getString("status"))
                                    .put("reason", c.optString("reason"))
                                    .put("evidenceIds", c.getJSONArray("evidenceIds"))
                                    .put("checkedAt", System.currentTimeMillis())
                            }
                        m.put(
                            "claims",
                            JSONArray(
                                m.getJSONArray("claims").objects().flatMap {
                                    if (it.getString("id") == claimId) revised else listOf(it)
                                }
                            ),
                        )
                        val known = m.getJSONArray("evidence").objects().map { it.getString("id") }
                        evidence
                            .objects()
                            .filter { it.getString("id") !in known }
                            .forEach { m.getJSONArray("evidence").put(it) }
                        if (replaceId != null && old.optBoolean("spoken") && running)
                            say("이전 판정을 정정합니다. 재검증 결과를 화면에서 확인해 주세요.")
                    }
                    notice = "검증 처리 ${(System.nanoTime()-start)/1000000} ms · 모델 준비 이후 실측"
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (epoch == generation)
                        change { m ->
                            m.getJSONArray("claims")
                                .objects()
                                .find { it.getString("id") == claimId }
                                ?.let { revise(it, "UNVERIFIABLE", e.message ?: "검증 실패") }
                        }
                }
            }
        }
    }

    @Composable
    private fun Screen() {
        var input by remember { mutableStateOf("") }
        var who by remember { mutableStateOf<String?>(null) }
        var docText by remember { mutableStateOf("") }
        var title by remember { mutableStateOf("회의 자료") }
        var profileExport by remember { mutableStateOf(false) }
        var connectProfiles by remember { mutableStateOf(false) }
        Column(
            Modifier.fillMaxSize().padding(18.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("MEETING EPISTEMIC GATEWAY", style = MaterialTheme.typography.labelSmall)
            Text("확신보다, 확인 가능한 근거.", style = MaterialTheme.typography.headlineSmall)
            Text("v0.1.0 · S24 Ultra · 사전 릴리스", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("회의", "자료", "화자", "설정").forEachIndexed { i, t ->
                    FilterChip(selected = tab == i, onClick = { tab = i }, label = { Text(t) })
                }
            }
            Card(Modifier.fillMaxWidth()) { Text(notice, Modifier.padding(14.dp)) }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            when (tab) {
                0 -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { if (running) endMeeting() else startMeeting() },
                            enabled = !busy || running,
                        ) {
                            Text(if (running) "회의 종료" else "회의 시작")
                        }
                        OutlinedButton(
                            onClick = {
                                stoppedSpeechAt = System.currentTimeMillis()
                                tts.stop()
                                localVoice.stop()
                                speaking = false
                            }
                        ) {
                            Text("AI 발언 중지")
                        }
                    }
                    Text(
                        "종료하면 미저장 기록은 삭제됩니다. 먼저 필요한 기록을 저장하세요.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row {
                        Checkbox(profileExport, { profileExport = it })
                        Text("저장에 화자 프로필·누적 상태 포함", Modifier.padding(top = 12.dp))
                    }
                    Row {
                        OutlinedButton(
                            onClick = {
                                savedText = exportMeeting(meeting, profileExport)
                                save.launch("meeting-${System.currentTimeMillis()}.json")
                            }
                        ) {
                            Text("기록 저장 / SD")
                        }
                        TextButton(onClick = { load.launch(arrayOf("application/json")) }) {
                            Text("가져오기")
                        }
                    }
                    OutlinedTextField(
                        input,
                        { input = it },
                        label = { Text("직접 입력하여 검증") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row {
                        FilterChip(who == null, { who = null }, label = { Text("미확정") })
                        meeting.getJSONArray("speakers").objects().take(6).forEach { s ->
                            FilterChip(
                                who == s.getString("id"),
                                { who = s.getString("id") },
                                label = { Text(s.getString("name")) },
                            )
                        }
                    }
                    Button(
                        onClick = {
                            submit(input, who)
                            input = ""
                        },
                        enabled = !busy && input.isNotBlank(),
                    ) {
                        Text("주장 검증")
                    }
                    Text(
                        "주장과 근거 ${meeting.getJSONArray("claims").length()}건",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    for (c in meeting.getJSONArray("claims").objects().reversed()) {
                        Card(Modifier.fillMaxWidth()) {
                            Column(
                                Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    c.getString("status"),
                                    color = MaterialTheme.colorScheme.primary,
                                    style = MaterialTheme.typography.labelMedium,
                                )
                                Text(
                                    c.getString("text"),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(c.optString("reason"))
                                Text(
                                    "확인 시각: " +
                                        java.text.DateFormat.getTimeInstance()
                                            .format(java.util.Date(c.optLong("checkedAt"))),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                                val sid =
                                    c.optString("speakerId").takeIf {
                                        it != "null" && it.isNotEmpty()
                                    }
                                Text(
                                    meeting
                                        .getJSONArray("speakers")
                                        .objects()
                                        .find { it.getString("id") == sid }
                                        ?.optString("name") ?: "화자 미확정 · 누적 제외",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                                for (e in
                                    meeting.getJSONArray("evidence").objects().filter { ev ->
                                        c.getJSONArray("evidenceIds").let { ids ->
                                            (0 until ids.length()).any {
                                                ids.getString(it) == ev.getString("id")
                                            }
                                        }
                                    }) {
                                    Text(
                                        "${e.optString("title")} · ${e.optString("version")}\n${e.getString("text")}",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    if (e.optString("source").startsWith("https://"))
                                        TextButton(
                                            onClick = {
                                                startActivity(
                                                    Intent(
                                                        Intent.ACTION_VIEW,
                                                        Uri.parse(e.getString("source")),
                                                    )
                                                )
                                            }
                                        ) {
                                            Text("출처 열기")
                                        }
                                }
                                Row {
                                    TextButton(
                                        onClick = {
                                            submit(
                                                c.getString("text"),
                                                sid,
                                                c.getLong("endedAt"),
                                                c.getString("id"),
                                            )
                                        },
                                        enabled = !busy,
                                    ) {
                                        Text("이의제기")
                                    }
                                    TextButton(onClick = { tab = 1 }) { Text("근거 제시") }
                                    TextButton(
                                        onClick = {
                                            change { m ->
                                                m.getJSONArray("claims")
                                                    .objects()
                                                    .find {
                                                        it.getString("id") == c.getString("id")
                                                    }
                                                    ?.let {
                                                        if (it.optBoolean("spoken") && running)
                                                            say("이전 판정을 정정합니다. 직접 경험으로 표시한 발언입니다.")
                                                        revise(it, "EXPERIENCE", "직접 경험으로 표시")
                                                    }
                                            }
                                        }
                                    ) {
                                        Text("경험")
                                    }
                                    TextButton(
                                        onClick = {
                                            change { m ->
                                                m.getJSONArray("claims")
                                                    .objects()
                                                    .find {
                                                        it.getString("id") == c.getString("id")
                                                    }
                                                    ?.let {
                                                        if (it.optBoolean("spoken") && running)
                                                            say("이전 판정을 정정합니다. 추정으로 표시한 발언입니다.")
                                                        revise(it, "HYPOTHESIS", "추정으로 표시")
                                                    }
                                            }
                                        }
                                    ) {
                                        Text("추정")
                                    }
                                }
                                var editSpeaker by
                                    remember(c.getString("id")) { mutableStateOf(false) }
                                TextButton(onClick = { editSpeaker = !editSpeaker }) {
                                    Text("화자 수정")
                                }
                                if (editSpeaker)
                                    meeting.getJSONArray("speakers").objects().forEach { s ->
                                        TextButton(
                                            onClick = {
                                                change { m ->
                                                    m.getJSONArray("claims")
                                                        .objects()
                                                        .find {
                                                            it.getString("id") == c.getString("id")
                                                        }
                                                        ?.put("speakerId", s.getString("id"))
                                                        ?.put("speakerCertain", true)
                                                }
                                                editSpeaker = false
                                            }
                                        ) {
                                            Text(s.getString("name"))
                                        }
                                    }
                            }
                        }
                    }
                }
                1 -> {
                    Text("회의 자료", style = MaterialTheme.typography.titleLarge)
                    Button(
                        onClick = {
                            addDocument.launch(
                                arrayOf("application/pdf", "text/plain", "text/markdown")
                            )
                        }
                    ) {
                        Text("PDF · TXT · Markdown 추가")
                    }
                    OutlinedTextField(
                        title,
                        { title = it },
                        label = { Text("자료 이름·버전") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        docText,
                        { docText = it },
                        label = { Text("자료 붙여넣기") },
                        minLines = 5,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = {
                            change { addEvidence(it, docText, title) }
                            docText = ""
                        },
                        enabled = docText.isNotBlank(),
                    ) {
                        Text("자료 추가")
                    }
                    meeting.getJSONArray("evidence").objects().forEach { e ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Text(e.getString("title"))
                                Text(e.getString("text").take(500))
                                TextButton(
                                    onClick = {
                                        change { m ->
                                            m.put(
                                                "evidence",
                                                JSONArray(
                                                    m.getJSONArray("evidence").objects().filter {
                                                        it.getString("id") != e.getString("id")
                                                    }
                                                ),
                                            )
                                            m.getJSONArray("claims")
                                                .objects()
                                                .filter {
                                                    it.getJSONArray("evidenceIds")
                                                        .toString()
                                                        .contains(e.getString("id"))
                                                }
                                                .forEach {
                                                    revise(it, "UNVERIFIABLE", "근거가 삭제됐습니다.")
                                                }
                                        }
                                    }
                                ) {
                                    Text("삭제")
                                }
                            }
                        }
                    }
                }
                2 -> {
                    Text("화자와 근거 제시 상태", style = MaterialTheme.typography.titleLarge)
                    Button(
                        onClick = {
                            change {
                                it.getJSONArray("speakers")
                                    .put(
                                        JSONObject()
                                            .put("id", id())
                                            .put(
                                                "name",
                                                "화자 ${'A'+it.getJSONArray("speakers").length()}",
                                            )
                                    )
                            }
                        }
                    ) {
                        Text("화자 추가")
                    }
                    meeting.getJSONArray("speakers").objects().forEach { s ->
                        val p = policy(meeting, s.getString("id"))
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp)) {
                                OutlinedTextField(
                                    s.getString("name"),
                                    { name ->
                                        change { m ->
                                            m.getJSONArray("speakers")
                                                .objects()
                                                .find { it.getString("id") == s.getString("id") }
                                                ?.put("name", name)
                                        }
                                    },
                                )
                                Text(
                                    "${p.mode} · 근거 없는 단정 ${p.unsupported}회 · 적절한 표시 ${p.calibrated}회"
                                )
                                var merge by remember { mutableStateOf(false) }
                                TextButton(onClick = { merge = !merge }) { Text("다른 화자와 병합") }
                                if (merge)
                                    meeting
                                        .getJSONArray("speakers")
                                        .objects()
                                        .filter { it.getString("id") != s.getString("id") }
                                        .forEach { target ->
                                            TextButton(
                                                onClick = {
                                                    change { m ->
                                                        m.put(
                                                            "speakers",
                                                            JSONArray(
                                                                m.getJSONArray("speakers")
                                                                    .objects()
                                                                    .filter {
                                                                        it.getString("id") !=
                                                                            s.getString("id")
                                                                    }
                                                            ),
                                                        )
                                                        m.getJSONArray("claims")
                                                            .objects()
                                                            .filter {
                                                                it.optString("speakerId") ==
                                                                    s.getString("id")
                                                            }
                                                            .forEach {
                                                                it.put(
                                                                    "speakerId",
                                                                    target.getString("id"),
                                                                )
                                                            }
                                                    }
                                                    audio.profiles.remove(s.getString("id"))
                                                }
                                            ) {
                                                Text(target.getString("name"))
                                            }
                                        }
                            }
                        }
                    }
                    Row {
                        Checkbox(connectProfiles, { connectProfiles = it })
                        Text("이 프로필을 다음 회의에 연결하는 데 동의", Modifier.padding(top = 10.dp))
                    }
                    Button(
                        onClick = {
                            epoch++
                            analyst.local.cancel()
                            tts.stop()
                            localVoice.stop()
                            speaking = false
                            audio.stop()
                            running = false
                            change { m ->
                                val speakers =
                                    m.getJSONArray("speakers").objects().map { s ->
                                        val p = policy(m, s.getString("id"))
                                        s.put(
                                            "baseline",
                                            JSONObject()
                                                .put("mode", p.mode)
                                                .put("unsupported", p.unsupported)
                                                .put("calibrated", p.calibrated),
                                        )
                                    }
                                m.put("claims", JSONArray())
                                    .put("evidence", JSONArray())
                                    .put("speakers", JSONArray(speakers))
                                audio.clearProfiles()
                                speakers.forEach { s ->
                                    s.optJSONArray("embedding")?.let { v ->
                                        audio.profiles[s.getString("id")] =
                                            FloatArray(v.length()) { v.getDouble(it).toFloat() }
                                    }
                                }
                            }
                        },
                        enabled = connectProfiles,
                    ) {
                        Text("확인 후 새 회의 시작")
                    }
                }
                3 -> {
                    Text("기기 단독 실행", style = MaterialTheme.typography.titleLarge)
                    Text("처음 약 1.4–2.8GB 다운로드 · Wi-Fi 연결을 권장합니다. 다운로드 후 오프라인으로 사용할 수 있습니다.")
                    Button(onClick = { prepare("llm-1.7b") }, enabled = !busy && !running) {
                        Text("Qwen3 1.7B + 음성 모델 준비")
                    }
                    OutlinedButton(onClick = { prepare("llm-4b") }, enabled = !busy && !running) {
                        Text("Qwen3 4B 비교 모델 준비")
                    }
                    Text("원음은 저장하지 않습니다. 모델 비교·실시간 정확도 평가는 진행 전입니다.")
                    HorizontalDivider()
                    Text("OpenRouter · 선택 사항", style = MaterialTheme.typography.titleLarge)
                    Text("원음 없이 주장과 필요한 자료 발췌를 전송합니다. 무료 모델도 웹 검색 요금이 발생할 수 있습니다.")
                    Button(
                        onClick = {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(router.authUrl())))
                        }
                    ) {
                        Text("OpenRouter 로그인")
                    }
                    OutlinedTextField(
                        authCode,
                        { authCode = it },
                        label = { Text("로그인 페이지의 일회용 코드") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = {
                            val code = authCode
                            authCode = ""
                            background {
                                router.exchange(code)
                                val list =
                                    router
                                        .request("models", auth = false)
                                        .getJSONArray("data")
                                        .objects()
                                        .map {
                                            it.getString("id") to
                                                "${it.getString("name")} · 입력 $${it.getJSONObject("pricing").optDouble("prompt")*1e6}/M"
                                        }
                                val keyInfo = router.request("key").getJSONObject("data")
                                runOnUiThread {
                                    catalog = list
                                    remote = true
                                    notice = "연결됨 · 키 잔여 한도 ${keyInfo.opt("limit_remaining")}"
                                }
                            }
                        },
                        enabled = authCode.isNotBlank() && !busy,
                    ) {
                        Text("코드로 연결")
                    }
                    Row {
                        Checkbox(remote, { remote = it })
                        Text("외부 AI 사용", Modifier.padding(top = 10.dp))
                        Checkbox(web, { web = it })
                        Text("웹 검색", Modifier.padding(top = 10.dp))
                    }
                    var expanded by remember { mutableStateOf(false) }
                    Box {
                        OutlinedButton(onClick = { expanded = true }) {
                            Text(model.ifBlank { "모델 직접 선택" })
                        }
                        DropdownMenu(expanded, { expanded = false }) {
                            catalog.forEach { (id, name) ->
                                DropdownMenuItem(
                                    text = { Text(name) },
                                    onClick = {
                                        model = id
                                        expanded = false
                                    },
                                )
                            }
                        }
                    }
                    OutlinedButton(
                        onClick = {
                            router.disconnect()
                            remote = false
                        }
                    ) {
                        Text("연결 해제")
                    }
                    Button(
                        onClick = {
                            background {
                                localVoice.prepare { runOnUiThread { notice = it } }
                                runOnUiThread { notice = "한국어 로컬 음성 준비 완료" }
                            }
                        },
                        enabled = !busy,
                    ) {
                        Text("한국어 음성 다운로드 · 145MB · Wi-Fi 권장")
                    }
                    TextButton(
                        onClick = {
                            startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA))
                        }
                    ) {
                        Text("오프라인 한국어 음성 설치")
                    }
                }
            }
            Spacer(Modifier.height(25.dp))
        }
    }

    override fun onStop() {
        super.onStop()
        if (!::audio.isInitialized) return
        if (running) {
            audio.stop()
            running = false
            notice = "앱이 전면에서 벗어나 음성 수집을 중지했습니다."
        }
        tts.stop()
        localVoice.stop()
        speaking = false
    }

    override fun onDestroy() {
        if (!::audio.isInitialized) {
            super.onDestroy()
            return
        }
        stoppedSpeechAt = System.currentTimeMillis()
        epoch++
        analyst.local.cancel()
        audio.stop()
        audio.clearProfiles()
        localVoice.close()
        tts.shutdown()
        work.shutdown()
        speechHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
