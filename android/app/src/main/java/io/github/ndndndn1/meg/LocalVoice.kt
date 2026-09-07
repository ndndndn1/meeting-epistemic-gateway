package io.github.ndndndn1.meg

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.k2fsa.sherpa.onnx.*
import java.util.concurrent.Executors

/** Optional, explicitly downloaded Korean voice. Never uses a network TTS service. */
class LocalVoice(private val models: ModelStore) {
    private var tts: OfflineTts? = null
    private var track: AudioTrack? = null
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var generation = 0
    val ready
        get() = tts != null

    fun prepare(progress: (String) -> Unit) {
        models.prepare("tts", progress)
        tts?.release()
        tts =
            OfflineTts(
                config =
                    OfflineTtsConfig(
                        model =
                            OfflineTtsModelConfig(
                                supertonic =
                                    OfflineTtsSupertonicModelConfig(
                                        durationPredictor =
                                            models.path("tts-duration_predictor.int8.onnx"),
                                        textEncoder = models.path("tts-text_encoder.int8.onnx"),
                                        vectorEstimator =
                                            models.path("tts-vector_estimator.int8.onnx"),
                                        vocoder = models.path("tts-vocoder.int8.onnx"),
                                        ttsJson = models.path("tts-tts.json"),
                                        unicodeIndexer = models.path("tts-unicode_indexer.bin"),
                                        voiceStyle = models.path("tts-voice.bin"),
                                    ),
                                numThreads = 2,
                                debug = false,
                            )
                    )
            )
    }

    fun speak(text: String, done: () -> Unit) {
        stop()
        val token = generation
        worker.execute {
            try {
                if (token != generation) return@execute
                val audio =
                    tts!!.generateWithConfig(
                        text,
                        GenerationConfig(speed = 1.2f, numSteps = 5, extra = mapOf("lang" to "ko")),
                    )
                if (token != generation) {
                    audio.samples.fill(0f)
                    return@execute
                }
                val player =
                    AudioTrack.Builder()
                        .setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build()
                        )
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setSampleRate(audio.sampleRate)
                                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .build()
                        )
                        .setBufferSizeInBytes(
                            maxOf(
                                16384,
                                AudioTrack.getMinBufferSize(
                                    audio.sampleRate,
                                    AudioFormat.CHANNEL_OUT_MONO,
                                    AudioFormat.ENCODING_PCM_FLOAT,
                                ),
                            )
                        )
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build()
                track = player
                player.play()
                var offset = 0
                while (offset < audio.samples.size && token == generation) {
                    val count =
                        player.write(
                            audio.samples,
                            offset,
                            minOf(4096, audio.samples.size - offset),
                            AudioTrack.WRITE_BLOCKING,
                        )
                    if (count <= 0) break
                    offset += count
                }
                audio.samples.fill(0f)
                // Drain only the bounded output buffer, with cancellation still responsive.
                while (token == generation && player.playbackHeadPosition < offset) Thread.sleep(20)
                if (track === player) {
                    player.stop()
                    player.release()
                    track = null
                }
            } catch (_: Exception) {
                /* Caller stops the indicator; never switch to remote TTS. */
            } finally {
                if (token == generation) done()
            }
        }
    }

    fun stop() {
        generation++
        track?.let {
            try {
                it.pause()
                it.flush()
                it.release()
            } catch (_: Exception) {}
        }
        track = null
    }

    fun close() {
        stop()
        worker.shutdown()
    }
}
