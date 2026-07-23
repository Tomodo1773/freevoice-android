# FreeVoice Android

日本語で簡潔に書く。Windows版とはコードを共有せず、設計とプロンプトだけを移植する。

- YAGNI / KISS / DRY。状態は明示し、音声入力ジョブの所有者は常に1つ。
- 正常系を先に保ち、cancel・画面遷移・二重開始などの競合をテストする。
- 外部依存は最小にする。依存取得を伴う Gradle 実行は必ず `sfw` 経由。`--offline` は例外。
- APIキーや `.signing/` を表示・コミットしない。

主要コマンド: `sfw .\gradlew.bat assembleDebug`、`sfw .\gradlew.bat assembleRelease`、`.\gradlew.bat --offline :app:signingReport`。

構成: `ime/` は InputMethodService と単一ジョブ制御、`audio/` は WAV 録音、`network/` は Azure/OpenAI、`data/` は暗号化設定、`context/` はアプリ単位話題メモ。
