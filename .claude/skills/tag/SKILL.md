---
name: tag
description: FreeVoice Androidの次のSemVerタグを決めてorigin/mainへpushし、GitHub Releaseを開始する。ユーザーが「タグを付けて」「バージョンを上げて」「リリースして」「APKを公開して」などと依頼したときに使う。
---

# Release tag

1. `origin/main` とタグを更新し、mainに到達可能な最新の `vMAJOR.MINOR.PATCH` 以降の全変更を確認する。
2. 破壊的変更はmajor、機能追加はminor、それ以外はpatchを提案する。初回は `app/build.gradle.kts` の既定 `versionName` を候補にする。
3. 変更要約、対象mainのSHA、提案タグを提示し、正確なタグが指定済みでなければ確認を得る。
4. 対象変更がmainに未反映なら、その変更のPRを必須CI `test` 経由でマージし、`origin/main` を更新する。
5. 同名タグがローカル・リモートにないことを確認し、最新の `origin/main` に注釈付きタグを作成してpushする。
6. タグと対象SHAを報告する。
