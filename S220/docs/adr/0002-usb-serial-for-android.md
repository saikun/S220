# ADR-0002: USB シリアルドライバに usb-serial-for-android を採用する

- ステータス: 承認
- 日付: 2026-10-15

## コンテキスト

- 受信モジュールは FT232RL（FTDI）経由で USB 接続する。Android には FTDI の標準ドライバがなく、USB Host API で直接制御する必要がある。
- FTDI は USB パケット（64 byte）ごとに 2 byte のモデムステータスを付加するなど、チップ固有のプロトコル処理が必要。
- PC 版の README で、移植先として [usb-serial-for-android](https://github.com/mik3y/usb-serial-for-android) を想定していた。

## 決定

`com.github.mik3y:usb-serial-for-android:3.10.0` を JitPack から利用する。
ポート検索は `UsbSerialProber.getDefaultProber()` で行い、`FtdiSerialDriver` を優先、なければ最初に見つかったドライバを使う。
読み込みはライブラリの `SerialInputOutputManager` を使わず、自前の `SerialReceiver` スレッドで `port.read(buf, 50ms)` をループする。

## 理由

- FTDI / CP21xx / CH34x / PL2303 / CDC-ACM に対応した実績のある OSS で、FTDI のステータスバイト除去もライブラリが行う。
- 自前のループにすることで、**「データが来なかった」タイミング（`onIdle`）を 50 ms 周期で得られる**。これはパケット境界（200 ms 無出力）と改行なしフレームの確定（500 ms）の判定に必須で、`rx_app.py` の `SerialReceiver`（`on_bytes` / `on_idle`）と同じ形にできる。
- 読み込みバッファは FTDI のパケット長の倍数（`maxPacketSize × 16`）で確保し、ステータスバイト付きの転送を取りこぼさない。

## 結果

- `settings.gradle` に JitPack リポジトリが必要になった。
- FTDI 以外のチップもドライバ上は動作するが、接続時の自動起動（`device_filter.xml`）は FTDI（VID 0x0403）のみ。
- USB 権限の要求・切断検知はアプリ側（`RxService`）で実装する。

## 検討した代替案

- **FTDI 公式 D2XX for Android**: FTDI 専用で、配布形態（AAR の手動導入）とライセンス確認の手間がある。
- **Android USB Host API を直接使用**: FTDI のベンダリクエスト（ボーレート設定等）とステータスバイト処理を自前で実装する必要があり、工数と不具合リスクが大きい。
- **`SerialInputOutputManager`**: データ到着時のコールバックのみで、アイドル通知がないため、境界判定に別タイマーが必要になる。
