# Third-party components

Application source is MIT. Dependencies retain their own licenses. Model weights are downloaded directly from their publishers and are not bundled in the APK or Pages, except the small MIT Silero VAD runtime asset.

| Component                               | Version / lock                                 | License    | Upstream                                                      |
| --------------------------------------- | ---------------------------------------------- | ---------- | ------------------------------------------------------------- |
| React / React DOM                       | package-lock.json                              | MIT        | https://github.com/facebook/react                             |
| PDF.js                                  | 5.4.624                                        | Apache-2.0 | https://github.com/mozilla/pdf.js                             |
| WebLLM                                  | 0.2.84                                         | Apache-2.0 | https://github.com/mlc-ai/web-llm                             |
| sherpa-onnx WASM / Android AAR          | 1.13.7                                         | Apache-2.0 | https://github.com/k2-fsa/sherpa-onnx                         |
| ONNX Runtime, included by sherpa        | upstream binary                                | MIT        | https://github.com/microsoft/onnxruntime                      |
| llama.cpp                               | 5266f24da75dc449bd56cbed7addb9c8e4a6a73e       | MIT        | https://github.com/ggml-org/llama.cpp                         |
| path-browserify                         | 1.0.1                                          | MIT        | https://github.com/browserify/path-browserify                 |
| AndroidX Compose / Activity / Lifecycle | android/toolchain.properties, build.gradle.kts | Apache-2.0 | https://android.googlesource.com/platform/frameworks/support/ |
| PDFBox Android                          | 2.0.27.0                                       | Apache-2.0 | https://github.com/TomRoush/PdfBox-Android                    |
| SenseVoice                              | contracts/models.json                          | Apache-2.0 | https://github.com/FunAudioLLM/SenseVoice                     |
| 3D-Speaker CAMPPlus                     | contracts/models.json                          | Apache-2.0 | https://github.com/modelscope/3D-Speaker                      |
| Pyannote segmentation 3.0               | contracts/models.json                          | MIT        | https://huggingface.co/pyannote/segmentation-3.0              |
| Silero VAD                              | contracts/models.json                          | MIT        | https://github.com/snakers4/silero-vad                        |
| Qwen3 / quantizations                   | contracts/models.json, mlc-models.json         | Apache-2.0 | https://github.com/QwenLM/Qwen3                               |
| Supertonic 3 Korean TTS                 | contracts/models.json                          | MIT        | https://github.com/supertone-inc/supertonic                   |

The npm sherpa loader is adapted for a browser Worker by retaining Emscripten MEMFS and supplying path-browserify. The script checks the exact upstream patch sites and fails if they change. The WASM binary is unchanged. Helper modules are wrapped in separate scopes. See `scripts/prepare-web-runtime.mjs`.

Pinned model URLs, byte sizes, revisions and SHA-256 values are public in the manifests. `scripts/bootstrap.py` verifies build-input hashes. Deliberate updates use `resolve-models.py` and `lock-web-models.py`, followed by review and evaluation. Updating a manifest does not make a model validated.

Apache-2.0: https://www.apache.org/licenses/LICENSE-2.0

Each upstream distribution's copyright notices and license continue to apply; see included runtime license files and linked upstream repositories for complete notices.
