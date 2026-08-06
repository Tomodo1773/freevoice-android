# FreeVoice Android

日本語で簡潔に書く。Windows版とはコードを共有せず、設計とプロンプトだけを移植する。

- YAGNI / KISS / DRY。状態は明示し、音声入力ジョブの所有者は常に1つ。
- 正常系を先に保ち、cancel・画面遷移・二重開始などの競合をテストする。
- 外部依存は最小にする。ビルドは `--offline` を基本とする。依存追加はメジャーで枯れたもののみ採用し、素性不明なものは根拠付きで相談する（Socket Firewall Free は JVM/Gradle 非対応のため `sfw` は効かない）。
- APIキーや `.signing/` を表示・コミットしない。

主要コマンド: `.\gradlew.bat --offline assembleDebug`、`.\gradlew.bat --offline assembleRelease`、`.\gradlew.bat --offline :app:signingReport`。依存を新規取得する初回のみ `--offline` を外す。

構成: `ime/` は InputMethodService と単一ジョブ制御、`audio/` は WAV 録音、`network/` は Azure/OpenAI、`data/` は暗号化設定、`context/` はアプリ単位話題メモ。

同期: `AGENTS.md` と `CLAUDE.md`、`.agents/skills` と `.claude/skills` は同じ内容の別実体。片方を変更したら、もう一方も揃える。クローン後に `git config core.hooksPath .githooks` を実行する。
