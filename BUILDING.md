# Aurora Pure 빌드 안내

## 요구 환경

- JDK 21
- Android SDK Platform 36
- Android SDK Build Tools 36.0.0
- 인터넷 연결이 가능한 최초 의존성 동기화

Gradle Wrapper가 포함되어 있으므로 별도 Gradle 설치는 필요하지 않습니다.

## 개발 빌드

```bash
./gradlew :pure:assembleDebug
```

결과는 `pure/build/outputs/apk/debug/pure-debug.apk`에 생성됩니다. 디버그 application ID는 릴리스와 함께 설치할 수 있는 `com.aurora.pure.debug`입니다.

## 전체 검증

```bash
./gradlew --no-daemon --no-configuration-cache \
  :pure:testDebugUnitTest :pure:lintDebug :pure:assembleRelease
```

실제 분할 APK의 Manifest와 암호학적 서명을 검사하는 통합 테스트는 외부 APK를 저장소에 포함하지 않기 때문에 기본 실행에서 건너뜁니다. 릴리스 후보를 실제 APK 디렉터리로 추가 검증하려면 다음 환경 변수를 모두 지정합니다.

```bash
AURORA_PURE_APK_FIXTURE_DIR=/absolute/path/to/apk-set \
AURORA_PURE_APK_FIXTURE_PACKAGE=com.example.app \
AURORA_PURE_APK_FIXTURE_VERSION=12345 \
  ./gradlew :pure:testDebugUnitTest
```

## 릴리스 서명

`pure/signing.properties` 파일을 로컬에 만들면 릴리스 빌드가 해당 키로 서명됩니다. 이 파일과 키 저장소는 Git에서 제외됩니다.

```properties
STORE_FILE=/absolute/path/to/release.jks
STORE_PASSWORD=replace-me
KEY_ALIAS=aurora-pure
KEY_PASSWORD=replace-me
```

비밀번호를 속성 파일에 직접 쓰지 않으려면 권한을 제한한 별도 파일을 지정할 수 있습니다.

```properties
STORE_FILE=/absolute/path/to/release.jks
STORE_PASSWORD_FILE=/absolute/path/to/store-password
KEY_ALIAS=aurora-pure
KEY_PASSWORD_FILE=/absolute/path/to/key-password
```

```bash
./gradlew :pure:assembleRelease
```

키가 설정되면 결과는 `pure/build/outputs/apk/release/pure-release.apk`입니다. 키가 없으면 CI 검증용 `pure-release-unsigned.apk`가 생성됩니다.

서명 확인 예시:

```bash
apksigner verify --verbose --print-certs \
  pure/build/outputs/apk/release/pure-release.apk
```

공개 릴리스 업데이트에는 반드시 같은 영구 서명 키를 사용해야 합니다. 비밀번호, 키 저장소, `signing.properties`는 저장소나 CI 로그에 올리지 마세요.

## 프로젝트 경계

`:pure` 모듈은 설치 API, 설치 앱 전체 조회, WorkManager, 포그라운드 서비스, 루트와 Shizuku 의존성을 포함하지 않습니다. CI의 소스 경계 검사와 최종 병합 Manifest 검사를 함께 확인해야 합니다.
