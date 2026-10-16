# ADR-0003: `rx_app.py` の層構造を踏襲し、処理層を Android 非依存の純粋 Kotlin にする

- ステータス: 承認
- 日付: 2026-10-15

## コンテキスト

- PC 版 `rx_app.py` は、要件どおり「I/O 層（`SerialReceiver`）→ 処理層（`MessageProcessor` / `LineFramer` / `parse_message` / `RxStatistics`）→ UI 層（`ConsolePresenter`）」に分離され、層間はコールバックのみで接続されている。
- PC 版で動作確認済みのフレーミング・欠落推定のロジックを、挙動を変えずに Android へ持ち込みたい。
- 受信処理は時間（無出力時間）に依存するため、実機なしで検証できる仕組みが欲しい。

## 決定

- 処理層を `core/RxCore.kt` に**クラス・関数名をほぼ 1 対 1 で移植**する（`PacketAssembler`, `splitRssi`, `LineFramer`, `parseMessage`, `RxStatistics`, `MessageProcessor`, `RxMessage`, `RxConfig`）。
- `core/` パッケージは **Android SDK に依存させない**（Kotlin 標準ライブラリのみ）。
- 時刻は `Clock` 型（`() -> Long`）で注入し、既定は単調増加クロック（`System.nanoTime`）、テストでは任意の値に差し替える。
- I/O 層は `usb/SerialReceiver`、UI 層は `RxHub` + `MainActivity` に置き換え、`RxService` が各層を組み立てる。

## 理由

- PC 版と同じ構造にすることで、PC 版で確認した挙動と Android 版の挙動の差を追いやすい（不具合時に両方で再現確認できる）。
- Android 非依存にすると `src/test` の JVM 単体テストで高速に検証でき、時間依存の処理も `Clock` 差し替えで実時間を待たずにテストできる。
- 将来 UI を作り替えても（Compose 化など）処理層は影響を受けない。

## 結果

- `RxCoreTest` で行分割・RSSI 分離・解析・統計・音階を端末なしで検証できる。
- Python との同期を保つため、片方を変更したらもう片方も見直す必要がある。現時点で既に差分がある（パケット境界 80 ms → 200 ms、RSSI 分離ロジック。[ADR-0006](0006-rssi-mode-and-packet-gap.md), [ADR-0007](0007-rssi-split-heuristic.md)）。
- `ByteBuf`（Python の `bytearray` 相当）を自前で実装した。

## 検討した代替案

- **Android 向けに一から設計**（Flow / Coroutines ベースのストリーム処理など）: Kotlin らしい書き方になるが、PC 版との対応が崩れ、検証済みロジックを再検証する必要がある。
- **Kotlin Multiplatform / 共有ライブラリ化**: PC 版が Python のため共有できない。
