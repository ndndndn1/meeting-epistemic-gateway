import { readFile, writeFile, mkdir, copyFile } from "node:fs/promises";
const src = "node_modules/sherpa-onnx/",
  dst = "web/public/runtime/";
await mkdir(dst, { recursive: true });
for (const f of ["sherpa-onnx-wasm-nodejs.js", "sherpa-onnx-wasm-nodejs.wasm"])
  await copyFile(src + f, dst + f);
// The upstream WASM binary supports browsers. Its npm loader's path dependency
// is the sole unguarded Node import; use the reviewed POSIX browser polyfill.
const loader = await readFile(dst + "sherpa-onnx-wasm-nodejs.js", "utf8");
if (loader.split('var nodePath=require("path");').length !== 2)
  throw Error("Upstream loader changed: review required");
const pathSource = await readFile(
  "node_modules/path-browserify/index.js",
  "utf8",
);
const prelude =
  "(()=>{const module={exports:{}};const exports=module.exports;" +
  pathSource +
  ";globalThis.MegPath=module.exports;})();\n";
const rawGuard =
  'if(!ENVIRONMENT_IS_NODE){throw new Error("NODERAWFS is currently only supported on Node.js environment.")}';
const rawMount =
  "for(var _key in NODERAWFS){FS[_key]=_wrapNodeError(NODERAWFS[_key])}";
if (!loader.includes(rawGuard) || !loader.includes(rawMount))
  throw Error("Upstream filesystem changed: review required");
await writeFile(
  dst + "sherpa-onnx-wasm-nodejs.js",
  prelude +
    loader
      .replace(
        'var nodePath=require("path");',
        "var nodePath=globalThis.MegPath;",
      )
      .replace(rawGuard, "")
      .replace(rawMount, "if(ENVIRONMENT_IS_NODE){" + rawMount + "}"),
);
await copyFile(
  "node_modules/path-browserify/LICENSE",
  dst + "path-browserify-LICENSE.txt",
);
for (const [file, name, exports] of [
  ["sherpa-onnx-asr.js", "SherpaAsr", "OfflineRecognizer"],
  ["sherpa-onnx-vad.js", "SherpaVad", "createVad"],
  [
    "sherpa-onnx-speaker-diarization.js",
    "SherpaSpeaker",
    "createOfflineSpeakerDiarization",
  ],
  ["sherpa-onnx-tts.js", "SherpaTts", "OfflineTts"],
]) {
  await writeFile(
    dst + file,
    "(()=>{\n" +
      (await readFile(src + file, "utf8")) +
      `\nglobalThis.${name}={${exports}};\n})();`,
  );
}

for (const name of ["sherpa-onnx", "llama.cpp", "ONNXRuntime", "SileroVAD"])
  await copyFile("docs/licenses/" + name + ".txt", dst + name + "-LICENSE.txt");
