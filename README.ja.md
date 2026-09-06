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

上の Android 画面と CLI 画像は 1.2.1 リリース候補から作成しています。Android アプリと CLI は英語、簡体字中国語、日本語、韓国語に完全対応します。

## ダウンロード

[GitHub Releases](https://github.com/sampple-korea/AuroraPure/releases/latest) から取得してください。

| プラットフォーム | ファイル | 要件 |
| --- | --- | --- |
| Android | `AuroraPure-1.2.1.apk` | Android 10 / API 29 以降 |
| Linux・macOS CLI | `aurora-pure-cli-1.2.1.tar` または `.zip` | Java 21 以降 |
| Windows CLI | `aurora-pure-cli-1.2.1.zip` | Java 21 以降、`bin\aurora-pure.bat` を実行 |

Aurora Pure はファイルのダウンロードだけを行います。インストールには Android のファイルマネージャーまたは互換性のある分割 APK インストーラーを別途使用してください。

## 1.2.1 の機能

- アプリ名、パッケージ名、Google Play URL、共有された Play リンクで検索。
- アイコン、開発者、パッケージ名、バージョン情報、説明を表示。
- 個人 Google アカウントを入力しない匿名セッション。
- アプリを開くと ABI や DPI を事前選択せず、対応する**実際の配信組み合わせ全体**を自動調査。
- 数値のバージョンコードが大きい順に結果をバージョン別にまとめ、Google Play が返した ABI、最小 Android、確認済み Android プロファイル、画面 DPI を一覧から選択。
- ARM64、ARM32、x86_64、x86 をすべて調査し、Play が実際に APK を返した最新セットを含める **Universal 結果**。
- 標準密度 120、160、213、240、320、480、640dpi と、各経路で実在する Android 配信階層を調査。
- 独立した配信経路を最大 4 本並列で調査し、Android の読み込み画面に処理中の ABI、DPI、Android プロファイルを表示。
- base APK の split 宣言を読み、**公開されている全言語 APK を既定で要求**。
- 最大 4 個の固有 APK を並列取得し、安全な Range 再開と同一内容の重複転送回避。
- Play 参照ハッシュ、APK 署名、署名者の一貫性、パッケージ、バージョン、split ID、最終ファイルを検証。
- 単一ファイルは `.apk`、分割結果は APK のみを含む `.apks` で保存。
- PC CLI でも検索、発見、選択、ダウンロード、検証、履歴、設定の全フローを提供。

## 実際のバリアント発見

Android でアプリを開くと、ARM64、ARM32、x86_64、x86 と 7 種類の標準 DPI の全体調査が自動的に始まります。各 `ABI × DPI` 経路は Android API 36 から開始し、Google Play が実際に返した APK Manifest の `minSdk` を読み、次に意味のある古い Android 階層を調査します。独立した経路は上限付きで並列処理し、各経路内の順序は維持します。

結果は数値の `versionCode` が大きい最新バージョンから、バージョン別の区画に分けます。表示用の `versionName` を文字列として並べ替えることはありません。同じバージョン内では、バージョンと実 APK ファイルセットが完全に一致する応答だけをまとめます。選択可能な各行には次が表示されます。

- 配信バージョン名とバージョンコード
- 互換 ABI
- APK Manifest に記録された最小 Android
- 実際に確認した DPI
- そのセットを返した Android API プロファイル
- 固有 APK 数とダウンロードサイズ

**Universal** 行は、最新検出セットをバージョンコードを混在させずにまとめた選択肢です。4 ABI 系列をすべて調査しますが、そのアプリを Play が配信しない系列は捏造せず除外し、実際に返された系列を行に表示します。調査後も何も自動選択せず、利用者が結果を選びます。複数の split APK を書き換えて偽の単一 APK にすることはありません。

Google Play は端末機能やグラフィックテクスチャ形式など、さらに別の次元で配信を最適化する場合があります。1.2.1 が明示的に扱うのは ABI、密度、Android バージョン、言語です。

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

bin/aurora-pure variants com.google.android.apps.authenticator2

bin/aurora-pure download com.google.android.apps.authenticator2 \
  --variant universal --yes

bin/aurora-pure verify ~/Downloads/AuroraPure/example.apks
bin/aurora-pure history --json
```

CLI もオプションを省略すると全組み合わせを調査します。自動処理で意図的に範囲を絞る場合に限り、`--architecture`（`universal`、`both`、`64`、`32`、`arm64`、`arm32`、`x86_64`、`x86`）、`--density`（`current`、`all`、標準名、正確な DPI）、`--android-api` を使用できます。`config` は言語、ダウンロード並列数、保存先だけを保存し、`history` はトークン、Cookie、署名付き URL を保存しません。

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

最終アプリが要求する Android プラットフォーム権限は `android.permission.INTERNET` と `android.permission.ACCESS_NETWORK_STATE` だけです。インストール、全アプリ可視性、全ファイルアクセス、通知、フォアグラウンドサービス権限は要求しません。AndroidX は非公開の互換ブロードキャストを保護するため、アプリ ID 範囲の `signature` 権限もマージしますが、Android プラットフォーム機能は付与せず、同じ署名のアプリだけが保持できます。独自広告、行動分析、自動クラッシュ送信もありません。詳細は [PRIVACY.md](PRIVACY.md) を参照してください。

JDK 21 と Android SDK 36 でビルドします。

```bash
./gradlew --no-daemon --no-configuration-cache \
  :pure:testDebugUnitTest :pure:lintDebug :pure:assembleRelease \
  :cli:test :cli:distZip :cli:distTar
```

詳しい検証と署名方法は [BUILDING.md](BUILDING.md) にあります。Aurora Pure は [Aurora Store 4.8.3](https://gitlab.com/AuroraOSS/AuroraStore/-/tree/4.8.3) のコミット `e9be2c8293e02cc362d603df6b12b019fdb849f2` から派生し、GNU GPL v3 以降で配布されます。

「最新」とは、Aurora Pure が調査できる匿名配信プロファイル全体で現在確認されたバージョンを意味し、世界最高のバージョン番号を保証するものではありません。非公式 Google Play API と外部匿名認証サービスは変更または一時停止する場合があります。
