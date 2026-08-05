---
name: tag
description: FreeVoice Androidの次のSemVerリリースタグを安全に決定・作成・GitHubへpushし、署名済みAPKのGitHub Releaseを開始・確認する。ユーザーが「タグを付けて」「バージョンを上げて」「リリースして」「APKを公開して」「タグ付与」など、Android版の新バージョン公開を依頼したときに使う。
---

# Android Release Tag

`vMAJOR.MINOR.PATCH` タグを保護済みの `origin/main` に付け、Releaseワークフローを開始する。

## 安全原則

- `gh` は権限昇格して実行する。
- 既存タグを移動・上書き・削除しない。失敗時も同じタグを使い回さず、修正後に次のpatchを使う。
- タグはローカル作業ブランチや未コミット変更ではなく、必ず最新の `origin/main` に付ける。
- 署名鍵やパスワードを表示・取得・変更しない。確認するのはSecret名の存在だけにする。
- 新しいタグ、対象コミット、含まれる変更を提示し、タグ作成前にユーザーの確認を得る。ユーザーが今回の依頼で正確なタグを明示済みなら再確認は不要。

## 1. リリース前確認

1. GitHub認証、作業状態、リモートを確認する。
2. `origin/main` とタグを取得する。
3. `origin/main` に `.github/workflows/release.yml` があり、`v*.*.*` タグで起動することを確認する。
4. `gh secret list` で次の4名称が存在することだけを確認する。
   - `ANDROID_KEYSTORE_BASE64`
   - `ANDROID_STORE_PASSWORD`
   - `ANDROID_KEY_ALIAS`
   - `ANDROID_KEY_PASSWORD`
5. 未コミット変更がある場合、それはタグに含まれないと明示する。勝手にコミットしない。

```powershell
gh auth status
git status --short --branch
git remote get-url origin
git fetch origin main --tags --prune
git show origin/main:.github/workflows/release.yml
gh secret list --repo Tomodo1773/freevoice-android
```

## 2. バージョンを決める

リモートmainへ到達可能な正式なSemVerタグだけを対象に、最新タグとそれ以降の全コミットを確認する。

```powershell
$latestTag = git tag --merged origin/main --sort=-v:refname |
    Where-Object { $_ -match '^v\d+\.\d+\.\d+$' } |
    Select-Object -First 1

if ($latestTag) {
    git log "$latestTag..origin/main" --oneline --no-merges
} else {
    git log origin/main --oneline --no-merges
}
```

変更全体から次を提案する。

- fix・小さな修正のみ: patch
- 後方互換な機能追加を含む: minor
- 破壊的変更: major
- タグがまだない: `app/build.gradle.kts` の既定 `versionName` を初回候補にする

上位を増やしたら下位を0へ戻す。例: `v0.3.4` のminorは `v0.4.0`。Releaseワークフローの `versionCode` は `major * 1,000,000 + minor * 1,000 + patch` なので、minorとpatchは0〜999、新しい値は1〜2,100,000,000に収める。

ユーザーへ以下を短く提示する。

- 最新タグ
- 前回以降の変更要約
- 対象となる `origin/main` の短縮SHAと件名
- 提案タグと計算される `versionCode`

## 3. 必要な変更をmainへ入れる

リリース対象の変更が現在の作業ブランチにだけある場合、そのブランチのPRだけを対象にする。

1. PRがなければDraft PRを作る。
2. 内容と対象ブランチが正しいことを確認する。
3. DraftならReadyにする。
4. 必須CI `test` の成功を待つ。
5. ブランチ保護を迂回せずPRをマージする。
6. `git fetch origin main --tags` を再実行し、タグ対象SHAを確定する。

無関係なPRをまとめてマージしない。CI失敗時はタグを作らず、原因を報告する。

## 4. タグを作成してpushする

同名タグがローカルにもリモートにも存在しないことを確認してから、最新の `origin/main` へ注釈付きタグを作る。

```powershell
$newTag = 'v0.1.0'
if (git tag --list $newTag) { throw "Local tag already exists: $newTag" }
if (git ls-remote --tags origin "refs/tags/$newTag") { throw "Remote tag already exists: $newTag" }

git fetch origin main
$mainSha = git rev-parse origin/main
git tag -a $newTag $mainSha -m "Release $newTag"
git push origin "refs/tags/$newTag"
```

## 5. Releaseを確認する

タグpush後、対応するReleaseワークフローを特定して完了まで監視する。成功後、GitHub Releaseに次があることを確認する。

- `FreeVoice-<version>.apk`
- `FreeVoice-<version>.apk.sha256`

失敗時はActionsログを調べるが、タグを移動・削除しない。完了報告にはタグ、対象SHA、Release URL、APK名を含める。
