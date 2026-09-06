# Aurora Pure

[![Aurora Pure CI](https://github.com/sampple-korea/AuroraPure/actions/workflows/android.yml/badge.svg)](https://github.com/sampple-korea/AuroraPure/actions/workflows/android.yml)
[![Latest release](https://img.shields.io/github/v/release/sampple-korea/AuroraPure?display_name=tag)](https://github.com/sampple-korea/AuroraPure/releases/latest)
[![License: GPL-3.0-or-later](https://img.shields.io/badge/license-GPL--3.0--or--later-blue.svg)](LICENSE)

**English** · [한국어](README.ko.md) · [简体中文](README.zh-CN.md) · [日本語](README.ja.md)

Aurora Pure is a download-only Android app and desktop CLI for saving APK files delivered by Google Play. It keeps the useful search, artwork, developer, and description views from Aurora Store while removing installation, installed-app management, and automatic updates.

> Aurora Pure is an independent GPL fork. It is not an official Google or Aurora OSS distribution and is not affiliated with either project.

## Current interface

<p align="center">
  <img src="docs/screenshots/search-en.png" width="30%" alt="Aurora Pure English search screen">
  <img src="docs/screenshots/variants-en.png" width="30%" alt="Selectable real ABI, Android, and screen-DPI variants">
  <img src="docs/screenshots/cli-en.png" width="30%" alt="Aurora Pure interactive desktop CLI">
</p>

The Android screenshots and CLI capture above are generated from the 1.2.0 release candidate. The interface is fully available in English, Simplified Chinese, Japanese, and Korean.

## Download

Get release files from [GitHub Releases](https://github.com/sampple-korea/AuroraPure/releases/latest).

| Platform | Release file | Requirement |
| --- | --- | --- |
| Android | `AuroraPure-1.2.0.apk` | Android 10 / API 29 or newer |
| Linux and macOS CLI | `aurora-pure-cli-1.2.0.tar` or `.zip` | Java 21 or newer |
| Windows CLI | `aurora-pure-cli-1.2.0.zip` | Java 21 or newer; run `bin\aurora-pure.bat` |

Aurora Pure downloads files only. To install a downloaded app, use Android's file manager or a compatible split-APK installer separately.

## What 1.2.0 can do

- Search by app name, package name, Google Play URL, or a shared Play link.
- Show the app icon, developer, package name, version metadata, and description.
- Use an anonymous session without entering a personal Google account.
- Discover **actual delivery combinations** before download instead of inventing variants from filenames.
- List and select the version, ABI, minimum Android version, tested Android profile, and screen DPI returned by Google Play.
- Offer a separate **Universal ABI mode** that probes ARM64, ARM32, x86_64, and x86, then includes every architecture for which Play actually returns APKs.
- Probe the standard density buckets: 120, 160, 213, 240, 320, 480, and 640 dpi, or one selected DPI.
- Read the delivered base APK's split declaration and request **every advertised language APK by default**.
- Download up to four unique APKs in parallel, safely resume byte ranges, and avoid transferring identical content twice.
- Verify Play-provided hashes, APK signatures, signer consistency, package names, versions, split identities, and the final saved file.
- Save a single file as `.apk` and a split result as an APK-only `.apks` archive.
- Provide the same search, discovery, selection, download, verification, history, and configuration workflow in an interactive PC CLI.

## Actual variant discovery

Aurora Pure probes the selected ABI and DPI scope starting with Android API 36. It reads the `minSdk` from the APK that Google Play actually returned, then probes the next meaningful older Android tier. Each `ABI × DPI` path is followed independently.

Results are grouped only when their version and real APK artifact set are identical. Every selectable row therefore represents an observed combination and shows:

- delivered version name and version code;
- one or more compatible ABIs;
- minimum Android version from the APK manifest;
- observed DPI values;
- Android API profiles that returned that exact set;
- unique APK count and download size.

The **Universal** row exists only in Universal ABI mode and combines the latest discovered sets without mixing version codes. All four ABI families are probed; a family that Play does not deliver for that app is omitted rather than fabricated, and the row shows exactly which families were returned. Other multi-variant scopes use a clearly separate **Combined** row. Aurora Pure does not rewrite or merge several split APKs into a fabricated monolithic APK.

Google Play may target other dimensions, such as device features or graphics texture formats. Version 1.2.0 promises the ABI, density, Android-version, and language dimensions it explicitly probes—not every possible Play targeting dimension.

## Output contract

| Delivered result | Saved file | Contents |
| --- | --- | --- |
| One standalone APK | `.apk` | The APK bytes delivered by Google Play, unchanged |
| Multiple APKs | `.apks` | Root-level `.apk` entries only; no JSON, checksum text, icon, or modified APK |

Output names use the package name, version code, and useful ABI/DPI identity. They do **not** add an `_all-languages` suffix: all advertised language packs are already the normal default.

Aurora Pure's `.apks` is a ZIP-compatible APK collection following the simple APK-only convention supported by tools such as SAI. It is not claimed to be a `bundletool build-apks` archive and does not contain an AAB-derived `toc.pb`. Universal and Combined archives can contain alternative base/configuration sets; an installer must choose one compatible set.

Runtime-downloaded Play Asset Delivery content, account data, and a game's complete post-install resources are outside this APK-only scope. When Google Play reports additional non-APK data, Aurora Pure displays that limitation.

## Desktop CLI

Running the CLI without a subcommand starts a guided workflow with numbered search and actual-variant selection:

```bash
bin/aurora-pure
```

Every feature is also scriptable:

```bash
# Search and inspect public metadata
bin/aurora-pure search "Google Authenticator"
bin/aurora-pure info com.google.android.apps.authenticator2

# Display only combinations actually returned for all ABIs and standard DPI buckets
bin/aurora-pure variants com.google.android.apps.authenticator2 \
  --architecture universal --density all --android-api 36

# Resolve every declared language split and download the Universal latest set
bin/aurora-pure download com.google.android.apps.authenticator2 \
  --architecture universal --density all --variant universal --yes

# Re-open and cryptographically verify an existing result
bin/aurora-pure verify ~/Downloads/AuroraPure/example.apks

# Machine-readable output is available for automation
bin/aurora-pure variants com.example.app --json
bin/aurora-pure history --json
```

Available architecture scopes are `universal`, `both`, `64`, `32`, `arm64`, `arm32`, `x86_64`, and `x86`. Density accepts `current`, `all`, a standard name such as `xxhdpi`, or an exact numeric DPI. `config` stores non-secret defaults; `history` never stores tokens, cookies, or signed delivery URLs; `doctor` checks the runtime and local paths.

## Deliberately absent

- direct install or system installer launch;
- root, Shizuku, or unattended installation;
- installed-app inventory and update lists;
- automatic, scheduled, boot-time, or background downloads;
- personal Google account login or account management;
- recommendation feeds, rankings, review submission, analytics, or advertising;
- arbitrary URL and APK-mirror downloads.

On Android, leaving the entire app pauses active network transfers. Returning to the app and choosing **Resume** continues only after range-safety checks. The CLI keeps safe `.part` files when interrupted and validates the server's `Content-Range` before appending.

## Permissions and privacy

The only Android platform permissions requested by the final app are:

- `android.permission.INTERNET`
- `android.permission.ACCESS_NETWORK_STATE`

It does not request package installation, all-app visibility, all-files access, notifications, or foreground-service permissions. AndroidX also contributes an app-ID-scoped `signature` permission used to guard non-exported compatibility broadcasts; it grants no Android platform capability and only same-signer apps can hold it. Aurora Pure has no first-party ads, behavior analytics, or automatic crash upload. See [PRIVACY.md](PRIVACY.md) for the exact network and local-data boundary.

## Build and verify

JDK 21 and Android SDK 36 are required. The repository includes its Gradle Wrapper.

```bash
./gradlew --no-daemon --no-configuration-cache \
  :pure:testDebugUnitTest :pure:lintDebug :pure:assembleRelease \
  :cli:test :cli:distZip :cli:distTar
```

Release integration tests can use a local, non-committed directory of real Play APKs to exercise APK-only `.apks` export and cryptographic re-verification. Full details are in [BUILDING.md](BUILDING.md).

## Source and license

Aurora Pure is derived from [Aurora Store 4.8.3](https://gitlab.com/AuroraOSS/AuroraStore/-/tree/4.8.3), upstream commit `e9be2c8293e02cc362d603df6b12b019fdb849f2`. Source and modifications are distributed under GNU GPL v3 or later.

- [Release notes](RELEASE_NOTES.md)
- [Privacy notice](PRIVACY.md)
- [Upstream and trademark notice](NOTICE.md)
- [Build and signing guide](BUILDING.md)
- [GNU GPL v3 license](LICENSE)

Google Play's unofficial API and the external anonymous credential service can change or become temporarily unavailable. “Latest” always means the version currently offered to the selected anonymous delivery profiles—not a promise of the globally highest version number.
