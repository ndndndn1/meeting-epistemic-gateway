let tts;
self.onmessage = async (e) => {
  try {
    if (e.data.type === "init") {
      const base = e.data.base;
      importScripts(
        base + "runtime/sherpa-onnx-wasm-nodejs.js",
        base + "runtime/sherpa-onnx-tts.js",
      );
      const m = await Module({
        locateFile: (f) => base + "runtime/" + f,
        print: () => {},
        printErr: () => {},
      });
      const cache = await caches.open("meg-tts-v1");
      const manifest = await (await fetch(base + "models.json")).json();
      for (const a of manifest.assets.filter((a) => a.id.startsWith("tts-"))) {
        postMessage({
          type: "progress",
          text: "한국어 음성 모델 준비: " + a.id,
        });
        let r = await cache.match(a.url);
        if (!r) {
          r = await fetch(a.url);
          if (!r.ok) throw Error("음성 모델 다운로드 실패");
          const b = await r.arrayBuffer();
          const hash = Array.from(
            new Uint8Array(await crypto.subtle.digest("SHA-256", b)),
            (x) => x.toString(16).padStart(2, "0"),
          ).join("");
          if (hash !== a.sha256) throw Error("음성 모델 무결성 오류");
          await cache.put(a.url, new Response(b));
          r = new Response(b);
        }
        m.FS.writeFile(
          "/" + a.id.slice(4),
          new Uint8Array(await r.arrayBuffer()),
        );
      }
      tts = new SherpaTts.OfflineTts(
        {
          model: {
            supertonic: {
              durationPredictor: "/duration_predictor.int8.onnx",
              textEncoder: "/text_encoder.int8.onnx",
              vectorEstimator: "/vector_estimator.int8.onnx",
              vocoder: "/vocoder.int8.onnx",
              ttsJson: "/tts.json",
              unicodeIndexer: "/unicode_indexer.bin",
              voiceStyle: "/voice.bin",
            },
            numThreads: 1,
            debug: 0,
            provider: "cpu",
          },
        },
        m,
      );
      postMessage({ type: "ready" });
    } else if (e.data.type === "say") {
      const audio = tts.generateWithConfig(e.data.text, {
        sid: 0,
        speed: 1.2,
        numSteps: 5,
        extra: { lang: "ko" },
      });
      postMessage(
        {
          type: "audio",
          samples: audio.samples,
          rate: audio.sampleRate,
          generation: e.data.generation,
        },
        [audio.samples.buffer],
      );
    }
  } catch (err) {
    postMessage({ type: "error", message: String(err) });
  }
};
