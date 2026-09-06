# Aurora Pure

[![Aurora Pure CI](https://github.com/sampple-korea/AuroraPure/actions/workflows/android.yml/badge.svg)](https://github.com/sampple-korea/AuroraPure/actions/workflows/android.yml)
[![最新リリース](https://img.shields.io/github/v/release/sampple-korea/AuroraPure?display_name=tag)](https://github.com/sampple-korea/AuroraPure/releases/latest)
[![ライセンス: GPL-3.0-or-later](https://img.shields.io/badge/license-GPL--3.0--or--later-blue.svg)](LICENSE)

[English](README.md) · [한국어](README.ko.md) · [简体中文](README.zh-CN.md) · **日本語**

Aurora Pure は、Google Play が実際に配信する APK ファイルを保存するダウンロード専用 Android アプリ兼デスクトップ CLI です。Aurora Store の便利な検索、アイコン、開発者、説明画面を残し、インストール、インストール済みアプリ管理、自動更新を削除しています。

> Aurora Pure は独立した GPL フォークです。Google または Aurora OSS の公式配布物ではなく、両者との提携もありません。

## 現在の画面

<p align="center">
  <img src="docs/screenshots/search-en.png" width="30%" alt="Aurora Pure の英語検索画面">
  <img src="docs/screenshots/variants-en.png" width="30%" alt="実在する ABI、Android、画面 DPI バリアントの選択">
  <img src="docs/screenshots/cli-en.png" width="30%" alt="Aurora Pure の対話型デスクトップ CLI">
</p>

上の Android 画面と CLI 画像は 1.2.0 リリース候補から作成しています。Android アプリと CLI は英語、簡体字中国語、日本語、韓国語に完全対応します。

## ダウンロード

[GitHub Releases](https://github.com/sampple-korea/AuroraPure/releases/latest) から取得してください。

| プラットフォーム | ファイル | 要件 |
| --- | --- | --- |
| Android | `AuroraPure-1.2.0.apk` | Android 10 / API 29 以降 |
| Linux・macOS CLI | `aurora-pure-cli-1.2.0.tar` または `.zip` | Java 21 以降 |
| Windows CLI | `aurora-pure-cli-1.2.0.zip` | Java 21 以降、`bin\aurora-pure.bat` を実行 |

Aurora Pure はファイルのダウンロードだけを行います。インストールには Android のファイルマネージャーまたは互換性のある分割 APK インストーラーを別途使用してください。

## 1.2.0 の機能

- アプリ名、パッケージ名、Google Play URL、共有された Play リンクで検索。
- アイコン、開発者、パッケージ名、バージョン情報、説明を表示。
- 個人 Google アカウントを入力しない匿名セッション。
- ファイル名から推測せず、ダウンロード前に**実際の配信組み合わせ**を取得。
- Google Play が返したバージョン、ABI、最小 Android、確認済み Android プロファイル、画面 DPI を一覧から選択。
- ARM64、ARM32、x86_64、x86 をすべて調査し、Play が実際に APK を返した全アーキテクチャを含める独立した **Universal ABI モード**。
- 標準密度 120、160、213、240、320、480、640dpi の全体、または一つの DPI を調査。
- base APK の split 宣言を読み、**公開されている全言語 APK を既定で要求**。
- 最大 4 個の固有 APK を並列取得し、安全な Range 再開と同一内容の重複転送回避。
- Play 参照ハッシュ、APK 署名、署名者の一貫性、パッケージ、バージョン、split ID、最終ファイルを検証。
- 単一ファイルは `.apk`、分割結果は APK のみを含む `.apks` で保存。
- PC CLI でも検索、発見、選択、ダウンロード、検証、履歴、設定の全フローを提供。

## 実際のバリアント発見

選択した ABI と DPI の範囲を Android API 36 から調査します。Google Play が実際に返した APK Manifest の `minSdk` を読み、次に意味のある古い Android 階層を調査します。各 `ABI × DPI` 経路は独立しています。

バージョンと実 APK ファイルセットが完全に一致する応答だけをまとめます。選択可能な各行には次が表示されます。

- 配信バージョン名とバージョンコード
- 互換 ABI
- APK Manifest に記録された最小 Android
- 実際に確認した DPI
- そのセットを返した Android API プロファイル
- 固有 APK 数とダウンロードサイズ

**Universal** 行は Universal ABI モードだけに現れ、最新検出セットをバージョンコードを混在させずにまとめます。4 ABI 系列をすべて調査しますが、そのアプリを Play が配信しない系列は捏造せず除外し、実際に返された系列を行に表示します。それ以外の複数バリアント範囲は明確に**選択範囲の統合セット**と表示します。複数の split APK を書き換えて偽の単一 APK にすることはありません。

Google Play は端末機能やグラフィックテクスチャ形式など、さらに別の次元で配信を最適化する場合があります。1.2.0 が明示的に扱うのは ABI、密度、Android バージョン、言語です。

## 出力仕様

| 実際の配信結果 | 保存ファイル | 内容 |
| --- | --- | --- |
| 独立 APK 一つ | `.apk` | Google Play が配信した APK バイトを変更せず保存 |
| 複数 APK | `.apks` | ルートの `.apk` エントリのみ。JSON、チェックサム TXT、アイコン、改変 APK は含まない |

ファイル名にはパッケージ名、バージョンコード、必要な ABI/DPI 情報を使用します。全公開言語パックが通常の既定値なので、`_all-languages` のような接尾辞は付けません。

Aurora Pure の `.apks` は SAI などが扱える単純な APK-only ZIP 規約です。AAB から `bundletool build-apks` で生成した `toc.pb` 付き形式とは主張しません。Universal または統合アーカイブには異なる base/config 候補が入る場合があり、インストーラーが互換性のある一セットを選択する必要があります。

実行後に取得する Play Asset Delivery、アカウントデータ、ゲームの完全な追加リソースは APK-only の対象外です。Google Play が APK 外データを報告した場合は制限として表示します。

## デスクトップ CLI

サブコマンドなしで実行すると、番号付き検索と実際のバリアント選択を行うガイドモードが始まります。

```bash
bin/aurora-pure
```

すべての機能をスクリプトからも利用できます。

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

アーキテクチャ範囲は `universal`、`both`、`64`、`32`、`arm64`、`arm32`、`x86_64`、`x86` です。DPI は `current`、`all`、`xxhdpi` などの標準名、または正確な数値を受け付けます。`config` は機密でない既定値だけを保存し、`history` はトークン、Cookie、署名付き URL を保存しません。

## 意図的に含まない機能

- 直接インストールとシステムインストーラー起動
- root、Shizuku、無人インストール
- インストール済みアプリ一覧と更新一覧
- 自動、予約、起動後、バックグラウンドのダウンロード
- 個人 Google アカウントのログインと管理
- おすすめ、ランキング、レビュー投稿、分析、広告
- 任意 URL または APK ミラーからのダウンロード

Android アプリ全体が見えなくなるとネットワーク転送を一時停止します。戻って「再開」を選ぶと Range 安全性確認後に続行します。CLI の中断時は安全な `.part` を残し、サーバーの `Content-Range` が正確な場合だけ追記します。

## 権限、ビルド、ライセンス

最終 Android Manifest が要求するのは `android.permission.INTERNET` と `android.permission.ACCESS_NETWORK_STATE` だけです。インストール、全アプリ可視性、全ファイルアクセス、通知、フォアグラウンドサービス権限は要求しません。独自広告、行動分析、自動クラッシュ送信もありません。詳細は [PRIVACY.md](PRIVACY.md) を参照してください。

JDK 21 と Android SDK 36 でビルドします。

```bash
./gradlew --no-daemon --no-configuration-cache \
  :pure:testDebugUnitTest :pure:lintDebug :pure:assembleRelease \
  :cli:test :cli:distZip :cli:distTar
```

詳しい検証と署名方法は [BUILDING.md](BUILDING.md) にあります。Aurora Pure は [Aurora Store 4.8.3](https://gitlab.com/AuroraOSS/AuroraStore/-/tree/4.8.3) のコミット `e9be2c8293e02cc362d603df6b12b019fdb849f2` から派生し、GNU GPL v3 以降で配布されます。

「最新」とは、選択した匿名配信プロファイルに Google Play が現在提示するバージョンを意味し、世界最高のバージョン番号を保証するものではありません。非公式 Google Play API と外部匿名認証サービスは変更または一時停止する場合があります。
