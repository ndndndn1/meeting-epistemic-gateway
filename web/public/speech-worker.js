/* Dedicated worker: raw PCM never leaves the device or enters persistent storage. */
let module, recognizer, vad, diarizer;
let base = "",
  history = new Float32Array(0),
  historyStart = 0,
  total = 0;
let anchors = [],
  nextSpeaker = 0;
const WINDOW = 16000 * 30;
async function cached(asset) {
  const cache = await caches.open("meg-speech-v1");
  let r = await cache.match(asset.url);
  if (!r) {
    r = await fetch(asset.url);
    if (!r.ok)
      throw Error(
        `음성 모델 ${asset.id} 다운로드 실패 (${r.status}). 설정의 모델 직접 가져오기를 사용하세요.`,
      );
    const bytes = await r.arrayBuffer();
    const hash = Array.from(
      new Uint8Array(await crypto.subtle.digest("SHA-256", bytes)),
      (x) => x.toString(16).padStart(2, "0"),
    ).join("");
    if (hash !== asset.sha256) throw Error("모델 무결성 확인 실패");
    await cache.put(asset.url, new Response(bytes));
    return new Uint8Array(bytes);
  }
  return new Uint8Array(await r.arrayBuffer());
}
self.onmessage = async (e) => {
  try {
    if (e.data.type === "init") {
      base = e.data.base;
      importScripts(
        base + "runtime/sherpa-onnx-wasm-nodejs.js",
        base + "runtime/sherpa-onnx-asr.js",
        base + "runtime/sherpa-onnx-vad.js",
        base + "runtime/sherpa-onnx-speaker-diarization.js",
      );
      module = await Module({
        locateFile: (f) => base + "runtime/" + f,
        print: () => {},
        printErr: () => {},
      });
      const manifest = await (await fetch(base + "models.json")).json();
      postMessage({
        type: "progress",
        message: "음성 모델을 준비합니다. 최초 약 270MB · Wi-Fi 권장",
      });
      for (const a of manifest.assets.filter((a) =>
        ["asr", "tokens", "vad", "segmentation", "embedding"].includes(a.id),
      ))
        module.FS.writeFile(
          "/" + a.id,
          await cached(
            a.id === "vad" ? { ...a, url: base + "runtime/vad.onnx" } : a,
          ),
        );
      recognizer = new SherpaAsr.OfflineRecognizer(
        {
          featConfig: { sampleRate: 16000, featureDim: 80 },
          modelConfig: {
            senseVoice: {
              model: "/asr",
              language: "ko",
              useInverseTextNormalization: 1,
            },
            tokens: "/tokens",
            numThreads: 1,
            provider: "cpu",
            debug: 0,
          },
          decodingMethod: "greedy_search",
        },
        module,
      );
      vad = SherpaVad.createVad(module, {
        sileroVad: {
          model: "/vad",
          threshold: 0.5,
          minSilenceDuration: 0.65,
          minSpeechDuration: 0.3,
          maxSpeechDuration: 20,
          windowSize: 512,
        },
        sampleRate: 16000,
        numThreads: 1,
        provider: "cpu",
        debug: 0,
        bufferSizeInSeconds: 40,
      });
      diarizer = SherpaSpeaker.createOfflineSpeakerDiarization(module, {
        segmentation: {
          pyannote: { model: "/segmentation", windowShiftRatio: 0.1 },
          numThreads: 1,
          debug: 0,
          provider: "cpu",
        },
        embedding: {
          model: "/embedding",
          numThreads: 1,
          debug: 0,
          provider: "cpu",
        },
        clustering: { numClusters: -1, threshold: 0.5 },
        minDurationOn: 0.3,
        minDurationOff: 0.5,
      });
      postMessage({ type: "ready" });
      return;
    }
    if (e.data.type === "reset") {
      vad?.reset();
      history.fill(0);
      history = new Float32Array(0);
      historyStart = 0;
      total = 0;
      return;
    }
    if (e.data.type === "pcm") {
      const { pcm, speaking, at } = e.data;
      if (speaking) {
        vad.reset();
        history = new Float32Array(0);
        historyStart = total;
        postMessage({ type: "done" });
        return;
      }
      if (e.data.rate !== 16000)
        throw Error("16kHz 음성 입력을 지원하지 않는 환경입니다.");
      const joined = new Float32Array(history.length + pcm.length);
      joined.set(history);
      joined.set(pcm, history.length);
      history = joined.length > WINDOW ? joined.slice(-WINDOW) : joined;
      total += pcm.length;
      historyStart = total - history.length;
      vad.acceptWaveform(pcm);
      while (!vad.isEmpty()) {
        const seg = vad.front();
        vad.pop();
        if (seg.samples.length >= 16000 * 19.5) {
          seg.samples.fill(0);
          postMessage({
            type: "progress",
            message:
              "긴 연속 발화는 문장 끝을 확정할 수 없어 집계하지 않았습니다.",
          });
          continue;
        }
        const end = total / 16000;
        const start = end - seg.samples.length / 16000;
        // Short, session-only anchor buffers stabilize cluster IDs across windows.
        // They are never persisted and disappear when the worker is terminated.
        const ranges = [];
        let offset = 0;
        for (const a of anchors) {
          ranges.push({
            id: a.id,
            start: offset / 16000,
            end: (offset + a.samples.length) / 16000,
          });
          offset += a.samples.length + 8000;
        }
        const combined = new Float32Array(offset + seg.samples.length);
        let p = 0;
        for (const a of anchors) {
          combined.set(a.samples, p);
          p += a.samples.length + 8000;
        }
        combined.set(seg.samples, offset);
        const regions = diarizer.process(combined);
        combined.fill(0);
        const claimStart = offset / 16000,
          claimEnd = (offset + seg.samples.length) / 16000;
        const relevant = regions.filter(
          (r) => r.end > claimStart && r.start < claimEnd,
        );
        const clusters = [...new Set(relevant.map((r) => r.speaker))];
        let certain = clusters.length === 1 && seg.samples.length >= 24000;
        let speaker = "";
        if (certain) {
          const cluster = clusters[0];
          const match = ranges.find((a) =>
            regions.some(
              (r) =>
                r.speaker === cluster &&
                Math.min(r.end, a.end) - Math.max(r.start, a.start) > 0.8,
            ),
          );
          if (match) speaker = match.id;
          else if (anchors.length < 6) {
            speaker = `voice-${crypto.randomUUID()}`;
            anchors.push({ id: speaker, samples: seg.samples.slice(0, 48000) });
          } else certain = false;
        }
        const stream = recognizer.createStream();
        try {
          stream.acceptWaveform(16000, seg.samples);
          recognizer.decode(stream);
          const result = recognizer.getResult(stream);
          if (result.text?.trim())
            postMessage({
              type: "utterance",
              generation: e.data.generation,
              text: result.text,
              speaker,
              certain,
              endedAt: at,
            });
        } finally {
          stream.free();
          seg.samples.fill(0);
        }
      }
      postMessage({ type: "done" });
    }
  } catch (err) {
    postMessage({ type: "error", message: String(err) });
  }
};
