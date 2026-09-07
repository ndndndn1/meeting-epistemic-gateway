# Meeting Epistemic Gateway

한국어 대면 회의의 주장과 근거를 비교하는 기기 실행형 실험 앱입니다. React·TypeScript 웹과 Kotlin·Compose Android 앱을 제공합니다. 운영 서버는 없습니다.

**v0.1.0은 사전 릴리스입니다. 실기기 초기 비교에서 정확도·지연 목표에 미달했습니다.** 실제 회의의 자동 판정이나 화자별 누적을 신뢰할 수 있는 수준으로 검증하지 않았습니다. [평가 결과와 미검증 항목](docs/EVALUATION.md)을 먼저 확인하세요.

- [웹앱](https://ndndndn1.github.io/meeting-epistemic-gateway/)
- [Galaxy S24 Ultra APK 및 SHA-256](https://github.com/ndndndn1/meeting-epistemic-gateway/releases)

## 사용

1. 참가자에게 음성 분석을 알리고 앱을 전면에 둡니다. 원음은 저장하지 않습니다.
2. 모델 및 연결에서 로컬 모델을 준비합니다. 음성 모델 약 270 MB, Android Qwen3 1.7B 약 1.11 GB 또는 4B 약 2.50 GB와 별도 실행 메모리가 필요합니다. 최초 다운로드는 Wi-Fi를 권장합니다. 웹 가중치 크기는 별도 MLC manifest에 기록합니다.
3. PDF·TXT·Markdown 또는 붙여넣기로 근거 자료를 추가합니다. 텍스트 없는 스캔 PDF는 지원하지 않습니다.
4. 마이크로 회의를 시작하거나 주장을 직접 입력합니다. 마이크 권한과 로컬 한국어 음성을 사용합니다. 음성이 없으면 설치하거나 선택적으로 약 145 MB의 로컬 한국어 TTS 모델을 준비합니다.
5. 판정과 화자 배정을 검토합니다. 이의제기·화자 수정·경험/추정 표시로 상태를 재계산합니다. 저장을 선택한 기록만 파일로 내보냅니다. 종료 시 미저장 기록은 폐기합니다.

웹 브라우저가 모델 호스팅의 다운로드 확인 화면에 막히면, 설정의 공식 링크에서 파일을 직접 받아 가져올 수 있습니다. SHA-256이 맞는 음성/TTS 파일만 캐시합니다. 브라우저 저장소 삭제·용량 부족은 재다운로드가 필요합니다. WebGPU가 없는 브라우저는 로컬 언어 모델을 실행할 수 없습니다.

Android의 기록 저장/가져오기는 시스템 파일 선택창을 사용합니다. 내부 저장소 또는 USB-C 카드리더의 SD카드를 선택할 수 있습니다. S24 Ultra에는 내장 microSD 슬롯이 없습니다. 앱은 arm64-v8a와 S24 Ultra CPU 명령어에 맞춰 빌드합니다.

## 판정과 개입

`VERIFIED`, `EXPERIENCE`, `HYPOTHESIS`, `UNSUPPORTED ASSERTION`, `CONTRADICTED`를 표시합니다. 질문·가치판단·제안과 `PENDING`, `UNVERIFIABLE`은 별도로 처리합니다. 근거 없는 단정은 현재 확인한 자료 범위에서 뒷받침되지 않는 사실 주장이라는 뜻이며 거짓 판정과 다릅니다. 모델 기억은 근거로 인정하지 않습니다. 확정 검증·반박에는 제공된 근거 ID가 필요합니다.

완료된 발화의 불확실한 주장에만 짧게 개입합니다. AI 발언 중지는 항상 가능합니다. 30초가 지난 결과는 자동 발언에서 제외합니다. 긴 연속 발화나 불확실한 화자는 누적에서 제외하며, 겹침 검출과 자기 음성 제거의 실기기 정확도는 미검증입니다.

같은 화자의 판정 가능한 주장에서 근거 없는 단정 3회가 연속되면 `EVIDENCE REQUIRED`가 됩니다. 경험·추정·근거 확인 3회가 연속되면 해제됩니다. 질문·검증 실패·불확실한 화자는 상태에 영향을 주지 않습니다. 반박 판정은 두 연속 횟수를 끊지만 잠금 자체를 해제하지 않습니다. 발언을 물리적으로 차단하지 않습니다. 정책은 주장 기록을 시간순으로 재생해 계산합니다.

## 선택적 OpenRouter

S256 PKCE를 사용합니다. 웹은 Pages 콜백 후 키를 메모리에만 보관하며 새로고침하면 다시 로그인합니다. Android는 시스템 브라우저가 제공하는 일회용 코드를 앱에서 교환하고 Keystore AES-GCM으로 키를 보호합니다.

원음 대신 검증 대상 문장과 필요한 자료 발췌만 전송합니다. 모델을 직접 선택하며 유료 모델로 자동 전환하지 않습니다. 웹 검색은 기본 ON입니다. **무료 모델도 검색 요금이 발생할 수 있습니다.** 인증·한도·검색 오류 때 로컬 모델이 준비돼 있으면 로컬로 전환하고 사유를 표시합니다. 준비되지 않았다면 검증 불가로 남깁니다. 사용자 계정의 실제 인증·결제 흐름은 이번 릴리스에서 테스트하지 않았습니다.

## 개발

Node 22와 Python 3.12 이상:

```sh
npm ci
python3 scripts/bootstrap.py web
npm test
npm run build
npm run dev -- --host 127.0.0.1
```

Android는 JDK 17, API 36, NDK/CMake 버전을 `android/app/build.gradle.kts`에 고정합니다. 기존 SDK 라이선스가 준비된 환경에서:

```sh
python3 scripts/bootstrap.py android
cd android
./gradlew testDebugUnitTest assembleDebug
```

배포 서명은 저장소 밖의 키를 사용합니다. `MEG_KEYSTORE`, `MEG_STORE_PASSWORD`를 비밀 환경으로 공급하고 `./gradlew assembleRelease`를 실행합니다. `MEG_SIGN_DEBUG=1`은 같은 키로 실기기 시험 후 release 업데이트를 검증할 때만 사용합니다. 키·비밀번호·모델·원음·사용자 기록은 Git에 넣지 않습니다.

`contracts/`는 정책 사례·프롬프트·스키마·모델 manifest, `scripts/`는 해시 검증 다운로드와 빌드 준비, `docs/`는 평가와 운영 설명을 담습니다. 웹은 Pages에 `dist/`만 배포하며 APK는 Releases에서 배포합니다. 모델은 Pages에 포함하지 않습니다. 작은 VAD 실행 자산만 정적 앱에 포함됩니다.

## 라이선스와 출처

앱 소스는 MIT입니다. [외부 구성요소 고지](docs/THIRD_PARTY.md) 및 각 고정 모델 manifest의 라이선스를 따릅니다. 재배포 모델 가중치는 APK·Pages·Git에 포함하지 않습니다.
