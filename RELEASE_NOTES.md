# Aurora Pure 1.2.0

This release makes Aurora Pure global, adds a full desktop CLI, and replaces synthetic variant labels with selectable Google Play delivery results.

## Actual ABI, DPI, and Android combinations

- Added Universal ABI mode that probes ARM64, ARM32, x86_64, and x86 and includes every architecture actually delivered for the selected app.
- Added current, all-standard, and individual 120/160/213/240/320/480/640dpi scopes.
- Probes Android API 36 first and follows each ABI × DPI path through real APK `minSdk` boundaries.
- Groups only responses with the same version and actual Play artifact identities.
- Preserves each observed `ABI × DPI × Android API` tuple in the selectable Android cards and human-readable CLI output.
- Treats an app-unavailable architecture as an absent result instead of failing the whole Universal scan; authentication and rate-limit failures still remain visible.
- Lists version, ABI, minimum Android, observed DPI, tested Android API profiles, APK count, and size as selectable rows.
- Keeps the Universal-scope row distinct from a Combined row for other multi-variant scopes.
- Never mixes different version codes or rewrites split APKs into a fabricated monolithic APK.

## APK output and performance

- Split downloads now produce `.apks` archives containing root-level APK files only.
- Removed `download-info.json`, `SHA256SUMS.txt`, icons, and all other non-APK archive entries.
- Removed `_all-languages` from output names because every advertised language split is the normal default.
- Downloads up to four unique APKs concurrently with 256KiB buffers.
- Reuses hash-identical artifacts across profiles and preserves safe HTTP Range resume behavior.
- Reopens the final `.apk` or `.apks` and verifies every expected SHA-256 before completion.

## Desktop CLI

- Added native-feeling guided operation when run without a subcommand.
- Added `search`, `info`, `variants`/`plan`, `download`, `verify`, `history`, `config`, and `doctor` commands.
- Supports all architecture, density, Android-tier, all-language, verification, resume, and output features available in the Android product.
- Supports structured JSON output for automation while keeping progress on stderr.
- Every subcommand exposes operand-free `--help`, including `download` and `verify`.
- Stores no auth token, anonymous account, cookie, or signed download URL in configuration or history.
- Ships portable Gradle application distributions for Linux, macOS, and Windows; Java 21 or newer is required.

## Global interface

- English is now the primary README and project language.
- Added complete Simplified Chinese and Japanese Android/CLI translations.
- Retained complete Korean support with dedicated Korean documentation.
- Added separate English, Korean, Simplified Chinese, and Japanese READMEs.

## Verification performed for this release

- Android unit tests and Lint.
- CLI unit tests for profile matrices, URL/package parsing, safe resume, configuration, translations, and archive policy.
- Real Google Play metadata and anonymous delivery discovery showing distinct Android delivery tiers.
- Real 168-APK fixture integration through content de-duplication, APK-only `.apks` export, APK signature/package/version validation, and final archive verification.
- Final release manifest permission and source-boundary inspection.

---

# Aurora Pure 1.1.0

모든 언어팩과 선택 가능한 64/32비트 제공 프로파일을 추가한 릴리스입니다.

## 새 기능

- 기본값을 **64 + 32비트**로 설정하고, 64비트 전용·32비트 전용 선택 제공
- 각 CPU 아키텍처를 별도 Google Play 기기 프로파일로 조회
- base APK의 bundletool `splits*.xml` 선언을 읽어 현재 전달 모듈의 모든 언어 split 탐색
- 한 번의 다중 언어 요청 후 누락된 언어만 제한적으로 개별 보충
- 64/32비트의 완전한 APK 세트를 `variants/64bit`와 `variants/32bit`에 독립 보존
- 서로 동일한 제공 파일은 검증된 메타데이터와 해시가 일치할 때만 로컬 복사로 재사용
- 다운로드 계획과 `download-info.json`에 요청 로케일, 파일별 로케일 및 아키텍처 프로파일 기록

## 정확성과 안전성

- 기능 모듈별 언어 split 및 base/master에 이미 포함된 언어의 빈 split 선언 처리
- 다운로드 응답을 전체 protobuf로 해석해 파일 목록 누락 방지
- 원격 base APK는 ZIP 중앙 디렉터리와 split XML 범위만 우선 읽고, Range 미지원 시 검증된 전체 파일로 안전하게 대체
- 언어 split 하나라도 누락되면 불완전한 묶음 내보내기 차단
- 서로 다른 앱 버전이 64/32비트 프로파일에 제공되면 결합 차단
- Google Play HTTP 오류를 인증·요청 제한·기타 상태로 구분해 표시

## 실제 릴리스 검증

- Android 16 x86_64 환경에서 Google OTP 7.2(7002011)로 64/32비트 프로파일 동시 시험
- 선언 로케일 82개, 언어 APK 164개, 총 APK 168개 다운로드 성공
- 최종 ZIP 170개 엔트리 검사 및 `SHA256SUMS.txt` 전체 일치
- 모든 APK 무결성·서명·패키지명·버전 검증 통과

“모든 언어팩”은 현재 선택한 제공 프로파일과 현재 전달되는 모듈이 앱 메타데이터에 선언한 언어를 뜻합니다. 온디맨드 모듈, Play Asset Delivery, 실행 후 추가 데이터는 포함하지 않습니다.

---

# Aurora Pure 1.0.0

첫 공개 릴리스입니다.

## 포함된 기능

- 한국어·영어 전체 UI와 한국어 기본 문서
- 이름, 패키지명, Play 링크 검색 및 앱 상세 정보
- 익명 Google Play 연결과 다운로드 직전 최신 제공 결과 재확인
- 단일 APK 원본 저장, 분할/의존 APK ZIP 보존
- 작업 하나씩 실행하는 대기열, 일시정지, 안전한 이어받기와 취소
- APK 해시·암호학적 서명·패키지·버전·분할 구성 검증
- 완성 파일을 다시 읽는 최종 APK/ZIP 검증
- MediaStore의 `Download/AuroraPure` 및 SAF 사용자 지정 폴더
- 로컬 기록, 공유, 기록/파일 삭제 분리
- 시스템·밝게·어둡게 테마와 선택적 화면 켜짐 유지

## 제거된 기능

앱 설치, 루트·Shizuku 설치, 설치된 앱 목록, 업데이트 관리, 자동 업데이트, 예약 또는 백그라운드 다운로드, 개인 계정, 추천·리뷰 작성·분석·광고를 포함하지 않습니다.

## 알려진 제한

- “최신”은 현재 익명 세션과 기기 조건에 Google Play가 제공하는 버전입니다.
- 분할 APK는 원본 보존용 ZIP이며 ZIP 자체를 설치 파일로 표현하지 않습니다.
- 실행 후 별도로 받는 자산·게임 데이터·계정 데이터는 저장하지 않습니다.
- 앱 화면이 완전히 보이지 않으면 다운로드가 일시정지되고 자동 재개되지 않습니다.
- 비공식 Google Play API와 외부 익명 인증 서비스의 변경 또는 제한으로 기능이 중단될 수 있습니다.

## 검증 범위

- 실제 Google Play v2 전용 단일 APK와 분할 APK 2개·4개 전달 경로
- 전경 이탈 일시정지와 Range 이어받기
- APK별 해시·서명·패키지·버전 확인
- ZIP 엔트리와 최종 저장본 SHA-256 재검증
- 화면 회전과 중복 실행 방지
- 제거 대상 권한·설치/업데이트 코드·의존성 부재
- Android 단위/통합 테스트, Lint, R8 릴리스 빌드
