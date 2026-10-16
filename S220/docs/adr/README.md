# Architecture Decision Records

S220 RX Logger（Android）の主要な設計判断を記録します。

> [!NOTE]
> 0001〜0011 は、初版実装（2026-10-15, commit `b0ca296`）のコード・コメント・PC 版ドキュメント（[S220SW.md](../../../S220SW.md), [S220HW.md](../../../S220HW.md), [README.md](../../../README.md)）から**事後的に復元**したものです。
> 当時の検討過程で記録が残っていない部分は、コードから読み取れる意図として記述しています。

## 一覧

| No. | タイトル | ステータス |
| --- | --- | --- |
| [0001](0001-receive-only-logger.md) | 受信専用のロガーとし、モジュール設定・送信機能を持たない | ADR-0012 により置換 |
| [0002](0002-usb-serial-for-android.md) | USB シリアルドライバに usb-serial-for-android を採用する | 承認 |
| [0003](0003-port-layered-core-from-python.md) | `rx_app.py` の層構造を踏襲し、処理層を Android 非依存の純粋 Kotlin にする | 承認 |
| [0004](0004-foreground-service.md) | 受信・記録をフォアグラウンドサービスで実行する | 承認 |
| [0005](0005-rxhub-singleton-bridge.md) | サービスと画面の橋渡しにプロセス内シングルトン `RxHub` を使う | 承認 |
| [0006](0006-rssi-mode-and-packet-gap.md) | RSSI バイト付加を前提とし、無線パケット境界を UART 無出力 200 ms で判定する | 承認 |
| [0007](0007-rssi-split-heuristic.md) | RSSI バイトの分離に改行位置と値域によるヒューリスティックを使う | 承認 |
| [0008](0008-location-manager.md) | 位置情報に Android 標準の `LocationManager` を使う | 承認 |
| [0009](0009-crash-safe-csv-and-gpx.md) | ログを CSV と 2 種類の GPX に、中断に強い方式で逐次書き出す | 承認 |
| [0010](0010-app-specific-external-storage.md) | ログ保存先をアプリ固有外部ストレージ（端末 / SD カード）から選ばせる | 承認 |
| [0011](0011-rssi-audible-tone.md) | RSSI を音階ビープで可聴化する | 承認 |
| [0012](0012-tx-mode.md) | 送信モード（TX）を追加し、起動時に RX / TX を選ばせる | 承認 |

## 書き方

新しい判断を記録するときは、次の番号でファイルを追加し、上の一覧に 1 行加えてください。
既存の判断を覆す場合は元の ADR を書き換えず、新しい ADR を作って元のステータスを「〜により置換」に変更します。

```markdown
# ADR-NNNN: タイトル

- ステータス: 提案 / 承認 / 却下 / 廃止 / ADR-XXXX により置換
- 日付: YYYY-MM-DD

## コンテキスト
（判断が必要になった背景・制約）

## 決定
（何をすることにしたか）

## 理由
（なぜそれを選んだか）

## 結果
（得られるもの・受け入れるトレードオフ・今後の課題）

## 検討した代替案
（選ばなかった案と理由）
```
