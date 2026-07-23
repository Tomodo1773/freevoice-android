# FreeVoice Android

Android のカスタム IME（キーボード）として動く、個人用の音声入力アプリ。キーボード上で録音し、Azure の文字起こしと任意の LLM 整形を経て、現在の入力欄へ直接挿入する。

## 開発環境

- Android Studio の JDK 17 以降
- Android SDK 36
- AGP 8.11.1 / Kotlin 2.2.20 / minSdk 26

外部依存は最小限に保つ。初回の依存取得を伴う Gradle 実行は必ず Socket Firewall 経由で行う。

```sh
sfw .\gradlew.bat assembleRelease
```

署名済み release APK を `dist/FreeVoice-0.1.0.apk` として配布する。release 署名と配布用コピーの手順はリリース時に実行する。キャッシュだけを使う確認は `.\gradlew.bat --offline …` でよい。

## 端末のセットアップ

対象は Android 8 以降。端末で「提供元不明のアプリ」のインストールを許可してから、`dist/FreeVoice-0.1.0.apk`（release版）をインストールする。

1. FreeVoice を開き、マイク権限と API 情報を設定する。
2. 「キーボード設定を開く」から FreeVoice 音声入力を有効にする。
3. 任意の入力欄でキーボード選択を開き、FreeVoice に切り替える。

設定画面上部にマイク権限と IME 有効状態が表示され、履歴・診断ログは画面下部から確認・消去できる。

開発用の debug APK は `app/build/outputs/apk/debug/app-debug.apk`。配布・更新確認には使わない。

API キーは Android Keystore の AES/GCM 鍵で暗号化して SharedPreferences に保存する。端末の root 化や侵害に対する完全な防御ではないため、個人端末でのみ使うこと。

## 配布用 APK の署名

配布 APK は release 署名を使う。更新互換性のため、`.signing/signing.properties` とキーストアは安全な場所へバックアップし、Git には絶対に入れない。署名ファイルがない開発環境でも、unsigned release の設定評価はできる。
