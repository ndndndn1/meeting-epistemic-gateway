package io.github.ndndndn1.meg

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import com.k2fsa.sherpa.onnx.*
import java.util.concurrent.ArrayBlockingQueue
import kotlin.concurrent.thread
import kotlin.math.sqrt

class AudioEngine(
    private val models: ModelStore,
    private val speaking: () -> Boolean,
    private val interrupt: () -> Unit,
    private val onText: (String, String?, FloatArray?, Long, Long) -> Unit,
    private val error: (String) -> Unit,
) {
    var onPcm: ((ShortArray, Int, Long) -> Unit)? = null
    @Volatile private var generation = 0
    private var captureThread: Thread? = null
    private var speechThread: Thread? = null
    private var recognizer: OfflineRecognizer? = null
    private var extractor: SpeakerEmbeddingExtractor? = null
    private var vad: Vad? = null
    private var diarizer: OfflineSpeakerDiarization? = null
    private var recorder: AudioRecord? = null
    private var echo: AcousticEchoCanceler? = null
    @Volatile private var active = false

    private data class Frame(val pcm: FloatArray, val at: Long, val ownVoice: Boolean)

    private val queue = ArrayBlockingQueue<Frame>(512)
    val profiles = linkedMapOf<String, FloatArray>()

    fun prepare() {
        check(!active && speechThread?.isAlive != true) { "이전 음성 작업 정리 중입니다. 잠시 후 모델을 준비하세요." }
        diarizer?.release()
        diarizer =
            OfflineSpeakerDiarization(
                config =
                    OfflineSpeakerDiarizationConfig(
                        segmentation =
                            OfflineSpeakerSegmentationModelConfig(
                                pyannote =
                                    OfflineSpeakerSegmentationPyannoteModelConfig(
                                        model = models.path("segmentation")
                                    ),
                                numThreads = 2,
                            ),
                        embedding =
                            SpeakerEmbeddingExtractorConfig(
                                model = models.path("embedding"),
                                numThreads = 2,
                            ),
                        clustering = FastClusteringConfig(numClusters = -1, threshold = .5f),
                    )
            )
        recognizer?.release()
        extractor?.release()
        vad?.release()
        recognizer =
            OfflineRecognizer(
                config =
                    OfflineRecognizerConfig(
                        modelConfig =
                            OfflineModelConfig(
                                senseVoice =
                                    OfflineSenseVoiceModelConfig(
                                        model = models.path("asr"),
                                        language = "ko",
                                        useInverseTextNormalization = true,
                                    ),
                                tokens = models.path("tokens"),
                                numThreads = 2,
                                debug = false,
                            )
                    )
            )
        extractor =
            SpeakerEmbeddingExtractor(
                config =
                    SpeakerEmbeddingExtractorConfig(
                        model = models.path("embedding"),
                        numThreads = 2,
                        debug = false,
                    )
            )
        vad =
            Vad(
                config =
                    VadModelConfig(
                        sileroVadModelConfig =
                            SileroVadModelConfig(
                                model = models.path("vad"),
                                minSilenceDuration = .65f,
                                minSpeechDuration = .3f,
                                maxSpeechDuration = 20f,
                            ),
                        numThreads = 1,
                        debug = false,
                    )
            )
    }

    @Suppress("MissingPermission")
    fun start() {
        check(recognizer != null) { "음성 모델을 먼저 준비하세요." }
        if (active) return
        check(speechThread?.isAlive != true && captureThread?.isAlive != true) {
            "이전 음성 작업을 정리 중입니다. 잠시 후 재개하세요."
        }
        val run = ++generation
        vad!!.reset()
        queue.clear()
        val r =
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                16000,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(AudioRecord.getMinBufferSize(16000, 16, 2), 16000 * 8),
            )
        check(r.state == AudioRecord.STATE_INITIALIZED) { "마이크 초기화 실패" }
        recorder = r
        if (AcousticEchoCanceler.isAvailable())
            echo = AcousticEchoCanceler.create(r.audioSessionId)?.apply { enabled = true }
        active = true
        r.startRecording()
        captureThread =
            thread(name = "meg-capture") {
                try {
                    val b = ShortArray(512)
                    while (active && generation == run) {
                        val n = r.read(b, 0, b.size)
                        if (n < 0) throw IllegalStateException("마이크 읽기 실패")
                        if (n == 0) continue
                        if (!active || generation != run) break
                        val now = System.currentTimeMillis()
                        onPcm?.invoke(b, n, now)
                        val pcm = FloatArray(n) { b[it] / 32768f }
                        if (!queue.offer(Frame(pcm, now, speaking())))
                            throw IllegalStateException("음성 처리가 실시간 속도를 따라가지 못했습니다.")
                    }
                } catch (e: Exception) {
                    if (active) error(e.message ?: "음성 오류")
                    active = false
                }
            }
        speechThread =
            thread(name = "meg-speech") {
                var vadOrigin = 0L
                var fed = 0L
                try {
                    while (active && generation == run) {
                        val frame = queue.poll(1, java.util.concurrent.TimeUnit.SECONDS) ?: continue
                        val (pcm, at, ownVoice) = frame
                        if (ownVoice) {
                            if (sqrt(pcm.sumOf { (it * it).toDouble() } / pcm.size) > .12)
                                interrupt()
                            vad!!.reset()
                            fed = 0
                            vadOrigin = 0
                            pcm.fill(0f)
                            continue
                        }
                        if (fed == 0L) vadOrigin = at - pcm.size * 1000L / 16000
                        fed += pcm.size
                        vad!!.acceptWaveform(pcm)
                        pcm.fill(0f)
                        while (!vad!!.empty()) {
                            val segment = vad!!.front()
                            vad!!.pop()
                            val start = vadOrigin + segment.start * 1000L / 16000
                            process(
                                segment.samples,
                                start + segment.samples.size * 1000L / 16000,
                                start,
                                run,
                            )
                        }
                    }
                } catch (e: Exception) {
                    if (active) error(e.message ?: "음성 분석 실패")
                    active = false
                } finally {
                    queue.forEach { it.pcm.fill(0f) }
                    queue.clear()
                }
            }
    }

    private fun embedding(samples: FloatArray): FloatArray? {
        val stream = extractor!!.createStream()
        try {
            stream.acceptWaveform(samples, 16000)
            stream.inputFinished()
            return if (extractor!!.isReady(stream)) extractor!!.compute(stream) else null
        } finally {
            stream.release()
        }
    }

    private fun cosine(a: FloatArray, b: FloatArray): Double {
        if (a.size != b.size) return -1.0
        var dot = 0.0
        var aa = 0.0
        var bb = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            aa += a[i] * a[i]
            bb += b[i] * b[i]
        }
        return dot / (sqrt(aa * bb) + 1e-9)
    }

    fun process(
        samples: FloatArray,
        endedAt: Long,
        startedAt: Long = endedAt - samples.size * 1000L / 16000,
        expectedRun: Int? = null,
    ) {
        if (samples.size >= 16000 * 19.5) {
            samples.fill(0f)
            return
        }
        val regions = diarizer!!.process(samples)
        val single = regions.map { it.speaker }.distinct().size == 1
        var id: String? = null
        var vector: FloatArray? = null
        if (samples.size >= 16000) {
            vector = embedding(samples)
            val half = samples.size / 2
            val left = embedding(samples.copyOfRange(0, half))
            val right = embedding(samples.copyOfRange(half, samples.size))
            val stable = single && left != null && right != null && cosine(left, right) > .6
            if (vector != null && stable) {
                val matches =
                    synchronized(profiles) { profiles.toMap() }
                        .map { it.key to cosine(vector, it.value) }
                        .sortedByDescending { it.second }
                val first = matches.firstOrNull()
                if (
                    first != null &&
                        first.second > .65 &&
                        (matches.size < 2 || first.second - matches[1].second > .1)
                )
                    id = first.first
                else if ((first == null || first.second < .4) && profiles.size < 6) {
                    id = java.util.UUID.randomUUID().toString()
                    if (expectedRun == null || expectedRun == generation)
                        synchronized(profiles) { profiles[id] = vector }
                }
            }
        }
        val stream = recognizer!!.createStream()
        try {
            stream.acceptWaveform(samples, 16000)
            recognizer!!.decode(stream)
            val text = recognizer!!.getResult(stream).text.trim()
            if (text.isNotEmpty() && (expectedRun == null || expectedRun == generation))
                onText(text, id, vector, endedAt, startedAt)
        } finally {
            stream.release()
            samples.fill(0f)
        }
    }

    fun stop() {
        generation++
        active = false
        try {
            recorder?.stop()
        } catch (_: Exception) {}
        captureThread?.join(1000)
        recorder?.release()
        recorder = null
        echo?.release()
        echo = null
        queue.forEach { it.pcm.fill(0f) }
        queue.clear()
    }

    fun release() {
        stop()
        recognizer?.release()
        recognizer = null
        extractor?.release()
        extractor = null
        vad?.release()
        vad = null
        diarizer?.release()
        diarizer = null
    }

    fun clearProfiles() {
        synchronized(profiles) {
            profiles.values.forEach { it.fill(0f) }
            profiles.clear()
        }
    }
}
