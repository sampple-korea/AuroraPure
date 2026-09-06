# Aurora Pure

[![Aurora Pure CI](https://github.com/sampple-korea/AuroraPure/actions/workflows/android.yml/badge.svg)](https://github.com/sampple-korea/AuroraPure/actions/workflows/android.yml)
[![최신 릴리스](https://img.shields.io/github/v/release/sampple-korea/AuroraPure?display_name=tag)](https://github.com/sampple-korea/AuroraPure/releases/latest)
[![라이선스: GPL-3.0-or-later](https://img.shields.io/badge/license-GPL--3.0--or--later-blue.svg)](LICENSE)

[English](README.md) · **한국어** · [简体中文](README.zh-CN.md) · [日本語](README.ja.md)

Aurora Pure는 Google Play가 제공하는 APK 파일을 저장하는 다운로드 전용 Android 앱이자 PC CLI입니다. Aurora Store의 검색, 아이콘, 개발자, 설명 화면은 유지하면서 설치, 설치 앱 관리, 자동 업데이트는 제거했습니다.

> Aurora Pure는 독립적인 GPL 포크이며 Google 또는 Aurora OSS의 공식 배포판이 아닙니다.

## 최신 화면

<p align="center">
  <img src="docs/screenshots/search-en.png" width="30%" alt="Aurora Pure 영어 검색 화면">
  <img src="docs/screenshots/variants-en.png" width="30%" alt="ABI, Android, 화면 DPI 실제 조합 선택 화면">
  <img src="docs/screenshots/cli-en.png" width="30%" alt="Aurora Pure 대화형 PC CLI">
</p>

위 Android 화면과 CLI 이미지는 1.2.0 릴리스 후보에서 직접 만들었습니다. Android 앱과 CLI는 영어·중국어 간체·일본어·한국어를 모두 지원합니다.

## 다운로드

[GitHub Releases](https://github.com/sampple-korea/AuroraPure/releases/latest)에서 받으세요.

| 플랫폼 | 릴리스 파일 | 요구 사항 |
| --- | --- | --- |
| Android | `AuroraPure-1.2.0.apk` | Android 10 / API 29 이상 |
| Linux·macOS CLI | `aurora-pure-cli-1.2.0.tar` 또는 `.zip` | Java 21 이상 |
| Windows CLI | `aurora-pure-cli-1.2.0.zip` | Java 21 이상, `bin\aurora-pure.bat` 실행 |

Aurora Pure는 파일만 다운로드합니다. 받은 앱의 설치는 Android 파일 관리자나 호환되는 분할 APK 설치 도구에서 별도로 수행합니다.

## 1.2.0 핵심 기능

- 앱 이름, 패키지명, Google Play URL, 공유받은 Play 링크 검색
- 앱 아이콘, 개발자, 패키지명, 버전 정보, 설명 표시
- 개인 Google 계정을 입력하지 않는 익명 세션
- 파일명으로 추정하지 않고 다운로드 전에 **실제 제공 조합** 조회
- Google Play가 반환한 버전, ABI, 최소 Android, 확인한 Android 프로파일, 화면 DPI를 목록에서 선택
- ARM64, ARM32, x86_64, x86을 모두 조회하고 Play가 실제 APK를 반환한 아키텍처를 전부 포함하는 별도 **Universal ABI 모드**
- 표준 화면 밀도 120, 160, 213, 240, 320, 480, 640dpi 전체 또는 한 DPI 선택
- base APK의 split 선언을 읽어 **게시된 모든 언어 APK를 기본으로 요청**
- 고유 APK 최대 4개 병렬 다운로드, 안전한 Range 이어받기, 동일 콘텐츠 중복 전송 제거
- Play 제공 해시, APK 서명, 서명자 일관성, 패키지·버전·split 식별자와 최종 파일 검증
- 단일 파일은 `.apk`, 분할 결과는 APK만 든 `.apks`로 저장
- PC CLI에서도 검색부터 실제 조합 선택, 다운로드, 검증, 기록, 설정까지 지원

## 실제 조합 조회 방식

선택한 ABI와 DPI 범위에서 Android API 36부터 조회합니다. 실제로 받은 APK Manifest의 `minSdk`를 읽고, 그다음 의미 있는 하위 Android 계층을 다시 조회합니다. 각 `ABI × DPI` 경로는 서로 독립적으로 따라갑니다.

버전과 실제 APK 파일 세트가 완전히 같은 응답만 하나로 묶습니다. 따라서 선택 가능한 각 행에는 다음 실측 정보가 들어갑니다.

- 제공 버전 이름과 버전 코드
- 호환 ABI
- APK Manifest 기준 최소 Android
- 실제 확인된 DPI 값
- 해당 파일 세트를 반환한 Android API 프로파일
- 고유 APK 수와 다운로드 크기

**Universal** 행은 Universal ABI 모드에서만 나타나며, 발견된 최신 세트를 버전 코드가 섞이지 않게 묶습니다. 네 ABI 계열을 모두 조회하되 해당 앱에 Play가 APK를 제공하지 않는 계열은 꾸며내지 않고 제외하며, 실제 포함된 계열을 행에 그대로 표시합니다. 다른 다중 범위는 **선택 범위 전체 묶음**으로 구분합니다. 여러 split APK를 변조해 가짜 단일 APK로 합치지는 않습니다.

Google Play에는 기기 기능이나 그래픽 텍스처 형식 같은 추가 타기팅도 존재할 수 있습니다. 1.2.0이 명시적으로 보장하는 범위는 ABI, 화면 밀도, Android 버전, 언어 차원입니다.

## 저장 형식

| 실제 제공 결과 | 최종 파일 | 내용 |
| --- | --- | --- |
| 독립 APK 하나 | `.apk` | Google Play가 전달한 APK 바이트 그대로 |
| APK 여러 개 | `.apks` | 루트의 `.apk` 항목만 포함. JSON·체크섬 TXT·아이콘·변조 APK 없음 |

파일명은 패키지명, 버전 코드, 필요한 ABI/DPI 식별값을 사용합니다. 모든 언어팩이 정상 기본값이므로 `_all-languages` 같은 접미사는 붙이지 않습니다.

이 `.apks`는 SAI 같은 도구가 다루는 단순 APK-only ZIP 관례를 따릅니다. AAB에서 `bundletool build-apks`로 생성한 `toc.pb` 포함 형식이라고 주장하지 않습니다. Universal 또는 전체 묶음에는 서로 다른 base/config 대안이 있을 수 있으므로 설치 도구가 호환되는 한 세트를 선택해야 합니다.

실행 후 받는 Play Asset Delivery, 계정 데이터, 게임의 완전한 추가 리소스는 APK-only 범위 밖입니다. Google Play 응답에서 APK 외 데이터가 확인되면 제한 사항으로 표시합니다.

## PC CLI

명령 없이 실행하면 검색과 조합 선택을 번호로 안내하는 대화형 흐름이 시작됩니다.

```bash
bin/aurora-pure
```

모든 기능은 명령으로도 사용할 수 있습니다.

```bash
bin/aurora-pure search "Google Authenticator"
bin/aurora-pure info com.google.android.apps.authenticator2

bin/aurora-pure variants com.google.android.apps.authenticator2 \
  --architecture universal --density all --android-api 36

bin/aurora-pure download com.google.android.apps.authenticator2 \
  --architecture universal --density all --variant universal --yes

bin/aurora-pure verify ~/Downloads/AuroraPure/example.apks
bin/aurora-pure history --json
```

아키텍처 범위는 `universal`, `both`, `64`, `32`, `arm64`, `arm32`, `x86_64`, `x86`입니다. DPI는 `current`, `all`, `xxhdpi` 같은 표준 이름, 정확한 숫자를 받습니다. `config`는 비밀정보가 아닌 기본값만 저장하고, `history`에는 토큰·쿠키·서명된 URL을 남기지 않습니다.

## 의도적으로 없는 기능

- 바로 설치와 시스템 설치기 호출
- 루트·Shizuku·무인 설치
- 설치 앱 전체 조회와 업데이트 목록
- 자동·예약·부팅 후·백그라운드 다운로드
- 개인 Google 계정 로그인과 계정 관리
- 추천 피드, 순위, 리뷰 작성, 분석, 광고
- 임의 URL과 APK 미러 다운로드

Android 앱이 완전히 보이지 않으면 네트워크 전송을 일시정지합니다. 돌아와 **이어받기**를 누르면 Range 안전성 확인 후 계속합니다. CLI가 중단되면 안전한 `.part`를 남기며, 서버의 `Content-Range`가 정확할 때만 뒤에 이어 씁니다.

## 권한과 개인정보

최종 앱이 요청하는 Android 플랫폼 권한은 다음 두 개뿐입니다.

- `android.permission.INTERNET`
- `android.permission.ACCESS_NETWORK_STATE`

설치, 전체 앱 조회, 모든 파일 접근, 알림, 포그라운드 서비스 권한은 요청하지 않습니다. AndroidX가 비공개 호환 브로드캐스트 보호용으로 앱 ID 범위의 `signature` 권한 하나를 병합하지만, 이는 Android 플랫폼 기능을 부여하지 않으며 같은 서명 앱만 가질 수 있습니다. 자체 광고·행동 분석·자동 오류 업로드도 없습니다. 자세한 경계는 [개인정보 안내](PRIVACY.ko.md)를 확인하세요.

## 빌드와 검증

JDK 21과 Android SDK 36이 필요합니다.

```bash
./gradlew --no-daemon --no-configuration-cache \
  :pure:testDebugUnitTest :pure:lintDebug :pure:assembleRelease \
  :cli:test :cli:distZip :cli:distTar
```

실제 Play APK가 있는 로컬 디렉터리를 지정하면 APK-only `.apks` 내보내기와 암호학적 재검증 통합 시험도 실행할 수 있습니다. 자세한 방법은 [BUILDING.md](BUILDING.md)에 있습니다.

## 출처와 라이선스

Aurora Pure는 [Aurora Store 4.8.3](https://gitlab.com/AuroraOSS/AuroraStore/-/tree/4.8.3)의 커밋 `e9be2c8293e02cc362d603df6b12b019fdb849f2`에서 파생됐으며 GNU GPL v3 이상으로 배포됩니다.

- [릴리스 노트](RELEASE_NOTES.md)
- [개인정보 안내](PRIVACY.ko.md)
- [원본·상표 고지](NOTICE.md)
- [빌드·서명 안내](BUILDING.md)
- [GNU GPL v3](LICENSE)

비공식 Google Play API와 외부 익명 인증 서비스는 변경되거나 일시 중단될 수 있습니다. 여기서 “최신”은 전 세계 최고 버전이 아니라 선택한 익명 제공 프로파일에 현재 제시된 버전입니다.
