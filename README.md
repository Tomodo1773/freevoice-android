# FreeVoice Android

Android のカスタム IME（キーボード）として動く、個人用の音声入力アプリ。キーボード上で録音し、Azure の文字起こしと任意の LLM 整形を経て、現在の入力欄へ直接挿入する。

## 開発環境

- Android Studio の JDK 17 以降
- Android SDK 36
- AGP 8.11.1 / Kotlin 2.2.20 / minSdk 26

外部依存は最小限に保つ。依存取得済みの環境では `--offline` でビルド・テストする。

```powershell
.\gradlew.bat --offline testDebugUnitTest lintDebug assembleDebug
```

初回だけ `--offline` を外して依存を取得する。Socket Firewall Free は JVM / Gradle に未対応のため、依存追加時は内容と配布元を確認する。

署名済み release APK は [GitHub Releases](../../releases) からダウンロードする。

## 端末のセットアップ

対象は Android 8 以降。端末で「提供元不明のアプリ」のインストールを許可してから、GitHub Releases の `FreeVoice-<version>.apk` をインストールする。

1. FreeVoice を開き、マイク権限と API 情報を設定する。
2. 「キーボード設定を開く」から FreeVoice 音声入力を有効にする。
3. 任意の入力欄でキーボード選択を開き、FreeVoice に切り替える。

設定画面は「モデル」「プロンプト」「履歴」「ログ」の4タブ構成。マイク権限・IME有効化・キーボード選択が未完了の間だけ、上部に端末セットアップが表示される。履歴は入力結果、ログはレベル付きの診断情報として、それぞれ確認・コピー・消去できる。

開発用の debug APK は `app/build/outputs/apk/debug/app-debug.apk`。配布・更新確認には使わない。

### LangSmith トレーシング（任意）

「モデル」タブの LangSmith セクションで API キー・プロジェクト・リージョン（US/EU）を設定すると、文章整形と話題コンテキスト生成の LLM 呼び出しを LangSmith へ送信できる。後処理プロンプトの改善やデバッグに使う。既定はオフ。

- 送信は録音・整形とは別スレッドで行い、失敗しても音声入力には影響しない（診断ログに WARN を残すだけ）。
- 「プロンプトと本文もトレースに含める」をオフにすると、モデル名・トークン数・所要時間だけを送る。発話内容を外部に出したくない場合に使う。
- LangSmith API キーも他の API キーと同じく Android Keystore で暗号化して保存する。

API キーは Android Keystore の AES/GCM 鍵で暗号化して SharedPreferences に保存する。端末の root 化や侵害に対する完全な防御ではないため、個人端末でのみ使うこと。

## 配布用 APK の署名

配布 APK は release 署名を使う。更新互換性のため、`.signing/signing.properties` とキーストアは安全な場所へバックアップし、Git には絶対に入れない。署名ファイルがない開発環境でも、unsigned release の設定評価はできる。

GitHub Actions から配布する場合は、リポジトリの Actions secrets に以下を登録する。

- `ANDROID_KEYSTORE_BASE64`: release キーストアを Base64 化した値
- `ANDROID_STORE_PASSWORD`: キーストアのパスワード
- `ANDROID_KEY_ALIAS`: 署名鍵のエイリアス
- `ANDROID_KEY_PASSWORD`: 署名鍵のパスワード

既存キーストアは PowerShell でBase64化し、クリップボードへコピーできる。

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes(".signing\freevoice-release.jks")) | Set-Clipboard
```

`v0.1.1` のような `vMAJOR.MINOR.PATCH` タグを push すると、テスト・lint・署名確認後にAPKとSHA-256ファイルをGitHub Releaseへ公開する。端末の既存release版は、同じ署名鍵かつ新しいバージョンなら上書き更新できる。
