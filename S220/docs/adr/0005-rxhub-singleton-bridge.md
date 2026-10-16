# ADR-0005: サービスと画面の橋渡しにプロセス内シングルトン `RxHub` を使う

- ステータス: 承認
- 日付: 2026-10-15

## コンテキスト

- 受信は `RxService` で、表示は `MainActivity` で行う（[ADR-0004](0004-foreground-service.md)）。両者の生存期間は異なり、Activity は回転・バックグラウンド化で何度も破棄・再生成される。
- 受信結果は複数のスレッド（USB 受信・位置・メイン）から発生するが、View の更新はメインスレッドでしかできない。
- Activity を開き直したとき、それまでに受信した行と統計を復元したい。

## 決定

`object RxHub` を置き、以下を持たせる。

- 直近 **2000 行**の `LogEntry`（`ArrayDeque`）と、画面上部に出す `Status`（不変データクラス）
- `Listener` インターフェース（`onEntry` / `onStatus` / `onCleared`）
- `postEntry` / `updateStatus { copy(...) }` / `clear` は**どのスレッドから呼んでもメインスレッドで実行**する（`Handler(mainLooper)` へ post）

Activity は `onStart` でリスナー登録と `snapshot()` による復元、`onStop` で登録解除する。

## 理由

- 同一プロセス内の単純な橋渡しで十分で、Binder・AIDL などの仕組みは不要。
- 状態更新をメインスレッドに集約することで、`entries` と `status` のロックが不要になり、リスナーは常にメインスレッドで呼ばれる。
- `Status` を不変にして `copy` で更新するため、部分更新が競合しない。
- 依存ライブラリ（Lifecycle / Coroutines）を増やさずに済む。

## 結果

- Activity が破棄されていても直近 2000 行は保持され、再表示時に一覧と統計が復元される。2000 行を超えた分は画面からは消える（ファイルには残る）。
- プロセスが終了すると内容は消える（記録自体はファイルにある）。
- リスナー登録・解除を Activity 側で忘れるとリークするため、`onStart` / `onStop` で対にしている。
- テスト時に状態を差し替えにくい（グローバル状態）。

## 検討した代替案

- **`ViewModel` + `StateFlow` / `LiveData`**: PC 版 README で移植先として想定していた。ただし `ViewModel` は Activity にひもづくため、Service から値を流すには結局プロセス内の共有オブジェクトが必要になる。将来 UI を Compose 等に移す場合は、`RxHub` を `StateFlow` で公開する形に置き換えるのが自然。
- **Bound Service**: Activity からサービスにバインドして直接参照する。ライフサイクル処理（バインド・アンバインド）が増え、サービスが無いときの表示も別途扱う必要がある。
- **LocalBroadcastManager**: 非推奨。
