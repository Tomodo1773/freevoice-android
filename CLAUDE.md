# FreeVoice Android 開発方針

Windows版由来の音声入力設計を Kotlin IME として実装する。Windowsのクリップボード・フック・オーバーレイは持ち込まず、`InputConnection.commitText()` を使う。

- 日本語、YAGNI/KISS/DRY、明示状態、単一ジョブ所有を守る。
- 正常系を小さく保ち、cancel・ライフサイクル・非同期完了の競合をテストする。
- 依存は最小限。依存取得は `sfw` 必須、offline Gradle は例外。
- APIキー・署名鍵は出力やGitへ入れない。

詳細な単一ジョブ判断は `docs/adr/0001-ime-single-job-ownership.md`。
