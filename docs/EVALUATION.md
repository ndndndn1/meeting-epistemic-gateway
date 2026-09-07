# v0.1.0 evaluation — prerelease

Date: 2026-09-07. Device: Galaxy S24 Ultra SM-S928N, Android 16 / API 36, arm64. Device serials, keys and private meeting material are omitted.

## Release decision

**No candidate passed the release quality and latency gates.** The 1.7B selection is an experimental lower-memory choice, not a validated default. This release must remain a prerelease. No production real-time accuracy or speaker-attribution claim is made.

## Native model comparison

Five fixed synthetic Korean text cases were run against the same small supplied manual excerpt: matching voltage, contradictory voltage, hypothesis, personal experience, question. LLM and speech models were resident together. This is an integration probe, not a representative meeting corpus, audio-to-verdict benchmark or held-out quality estimate.

| Build                                    | Candidate   | Exact expected classes | Unverifiable / timeout | Observed analysis latency |
| ---------------------------------------- | ----------- | ---------------------- | ---------------------- | ------------------------- |
| Optimized native, generic ARM            | 1.7B Q4_K_M | 1 / 5                  | 2 / 5 unverifiable     | 20.36–36.38 s             |
| Optimized native, generic ARM            | 4B Q4_K_M   | 3 / 5                  | 0 / 5                  | 51.53–87.46 s             |
| S24 ARM dotprod/i8mm/fp16, 25 s deadline | 1.7B Q4_K_M | 1 / 5                  | 3 / 5 unverifiable     | 7.53–15.34 s              |
| S24 ARM dotprod/i8mm/fp16, 25 s deadline | 4B Q4_K_M   | 1 / 5                  | 2 / 5 timeout          | 16.88–25.02 s             |

Raw sanitized observations: [baseline](benchmark-baseline.json), [S24 optimization](benchmark-optimized.json). The baseline's hypothesis was wrongly marked EXPERIENCE by 4B. 1.7B wrongly treated the contradictory voltage as unsupported. Such classifications can produce incorrect speaker streaks. Correction UI and replayed policy do not cure model quality.

The 25-second deadline bounds native work and returns `UNVERIFIABLE` on timeout. Late results never enter the automatic speech queue after 30 seconds. Pending cards are shown before analysis; their display is not reported as completed verification latency.

## Verified integration checks

- TypeScript and Kotlin consume the same five policy fixtures, including entering/recovering from EVIDENCE REQUIRED, neutral questions/failures and a contradiction breaking a streak.
- TypeScript tests exercise replay after correction, uncertain attribution, duplicate IDs, fabricated evidence IDs, missing evidence scope, stale speech and sanitized import/export.
- Web production build and responsive desktop/mobile rendering were checked. No horizontal overflow was observed at the tested mobile viewport. Actual Pages returned HTTPS 200; its service worker installed and a network-disabled hard reload worked without page errors. Microphone API acquisition used a synthetic browser input, not a physical room microphone.
- Browser sherpa WASM initialized with all external requests blocked and previously hash-verified local model fixtures seeded into its cache. An upstream single-speaker Korean WAV produced Korean text and an initial speaker label. This proves an audio path works; it does not establish diarization accuracy.
- Local Korean TTS WASM generated 109,192 finite audio samples at 44,100 Hz (about 2.48 seconds) without external network access.
- Native debug APK built, signature verified, installed and launched on the connected S24 Ultra. With the 1.7B LLM resident, a Korean single-speaker audio sample was transcribed and attributed in 616 ms after 3,566 ms speech preparation. See [audio integration observation](audio-integration.json). This excludes live capture and meeting ground truth. A second [concurrent generation check](audio-concurrent.json) completed the Korean speech path in 507 ms while the 1.7B LLM generated another response; both completed. This short integration check does not establish sustained throughput. Additional final-package checks are recorded in the release notes.

## Unmet or unverified acceptance gates

| Gate                                                               | Status                                                            |
| ------------------------------------------------------------------ | ----------------------------------------------------------------- |
| Classification macro-F1 ≥ 0.85 on representative Korean set        | Not established; tiny integration set contains failures           |
| VERIFIED / CONTRADICTED precision ≥ 95%                            | Not established; sample too small                                 |
| Non-overlap speaker assignment ≥ 90%                               | Not measured on ground truth multi-speaker audio                  |
| End of speech → completed display p95 ≤ 2 s                        | Not passed / no representative end-to-end percentile              |
| Local spoken intervention p95 ≤ 5 s                                | Not passed; text analysis alone exceeds target                    |
| Korean 2 / 4 / 6 people, technical English, negation, units, noise | Representative recording set unavailable in this run              |
| 60-minute heat, memory, battery, omissions, queue stability        | Not run                                                           |
| Barge-in phrase once, acoustic echo and overlapping people         | Implemented conservative gates; physical-room accuracy unverified |
| PC WebGPU and S24 Chrome concurrent ASR/diarizer/LLM benchmark     | Not completed; headless UI/WASM checks are separate               |
| OAuth cancellation/expiry, paid search and account limits          | Error handling implemented; live account/cost flows not exercised |
| USB-C SD export/import                                             | Android SAF implemented; physical card reader not tested          |

Web diarization uses short ephemeral audio anchors internally; raw audio is never saved or exported. Web profile carryover currently preserves names and policy state for manual attribution; persistent automatic voice matching across web meetings is not implemented. Native embeddings can be exported only on explicit opt-in and linked after confirmation. Unknown/overlapping regions are excluded from streaks, but imperfect detection remains possible.

Model downloads in automated Chromium received a hosting-side Human Verification response. The app reports the error and offers hash-checked manual speech/TTS import; no verification challenge was bypassed. Offline seeded-cache testing must not be described as successful online browser downloading.

## Dependency review

`npm audit` found zero project package vulnerabilities during this run. Trivy's project dependency scan found zero reported runtime npm vulnerabilities using a database last updated 2026-09-05. That database was stale at scan time. The Node build image scan reported 1 critical, 12 high, 13 medium and 13 low findings, largely build-tool dependencies and OpenSSL. This image is a local, on-demand build environment with no published port; it is not deployed with the static app. The scan is not a clean bill for native AAR/NDK dependencies or the public app. Build containers are stopped after work.
