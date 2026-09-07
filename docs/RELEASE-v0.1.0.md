# v0.1.0 · 실험용 사전 릴리스

React·TypeScript 웹앱과 Galaxy S24 Ultra 전용 Kotlin·Compose 앱의 첫 공개 버전입니다. 운영 백엔드 없이 기기에서 음성·근거 자료를 처리합니다.

- SenseVoice INT8, VAD, 화자 구분, Qwen3 로컬 주장 검증 경로.
- 근거 카드, 화자 수정·병합, 판정 이의제기와 재계산, 3회 누적·해제 정책.
- PDF·TXT·Markdown, 기록 저장·복원과 선택적 프로필 연결.
- 오프라인 한국어 음성 우선, 선택적 Supertonic 로컬 음성 다운로드.
- 선택적 OpenRouter S256 PKCE, 모델 선택, 웹 검색 ON/OFF, 오류 시 로컬 처리.

**실시간 정확도·지연 기준에 미달한 사전 릴리스입니다.** S24의 최적화된 1.7B 시험은 합성 텍스트 5개 중 기대 분류 1개, 판정 7.53–15.34초였습니다. 4B는 같은 제한에서 2개 시간 초과가 발생했습니다. 60분 회의, 2·4·6인 정확도, 물리적 끼어들기·반향 억제, 실제 유료 검색과 USB-C 카드리더는 미검증입니다. 웹의 다음 회의 화자 연결은 이름·누적 상태를 수동 배정하는 수준입니다.

S24 Chrome에서 WebGPU와 모델 다운로드·준비, 음성 Worker 초기화는 확인했으나 첫 언어 모델 판정은 120초 이내에 완료되지 않았습니다. 최종 코드는 25초 제한으로 검증 불가를 표시합니다. APK에서도 모델 분류 품질은 실용 기준에 도달하지 않았습니다.

검사: 웹 정책 테스트 9개, Android 단위 테스트 4개(공통 정책 사례 5개 포함), GitHub Actions 웹/Android 빌드, S24 설치·실행, 한국어 공개 음성 샘플 처리, LLM 생성 중 동시 음성 처리, WASM 한국어 TTS 생성, 실제 Pages HTTPS/서비스워커/오프라인 새로고침. 성능·기능상의 한계는 [전체 평가](https://github.com/ndndndn1/meeting-epistemic-gateway/blob/main/docs/EVALUATION.md)에 공개합니다.

APK는 `arm64-v8a`, Android 14 이상, **SM-S928 계열 S24 Ultra 전용**입니다. 설치 후 모델 준비가 필요합니다. 원음과 모델 가중치는 APK에 포함하지 않습니다. 첨부 `SHA256SUMS`로 파일을 검증할 수 있습니다.

서명 인증서 SHA-256: `5efbd870a04b86f5c2b8ee6196cc9bf53ca3621896d0c03f9f2f49368ce8a5a1`. 이후 업데이트도 같은 비공개 서명키를 사용합니다.

[웹앱](https://ndndndn1.github.io/meeting-epistemic-gateway/) · [소스](https://github.com/ndndndn1/meeting-epistemic-gateway)
