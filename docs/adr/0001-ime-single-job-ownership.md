# ADR 0001: IME の音声入力ジョブは1件だけ所有する

## 決定

`VoiceInputController` は最大1件の録音・送信ジョブだけを所有する。開始ごとに generation ID を発行し、IME は `Target(jobId, InputConnection)` を保持する。完了・失敗・`finally`・cancel は jobId が一致する対象だけを処理する。

## 理由

IME は入力欄やアプリが非同期処理中に変わる。古い結果が新しい `InputConnection` に挿入されたり、古い失敗が新ジョブの対象を消したりすると、誤送信になるため。

## 補足

cancel と入力終了は generation を無効化し、録音一時ファイルは `finally` で削除する。話題コンテキストの蒸留は入力確定後の非クリティカル処理であり、失敗しても文字入力を妨げない。
