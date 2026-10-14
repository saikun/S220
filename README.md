# S220 - E220-900T22L LoRa 通信評価ソフトウェア

Windows ⇔ FT232RL ⇔ E220-900T22L（ノーマルモード固定）での送受信を評価するための Python スクリプトです。
ハードウェア構成は [S220HW.md](S220HW.md)、ソフトウェア要件は [S220SW.md](S220SW.md) を参照してください。

| ファイル | 役割 |
| --- | --- |
| `tx_app.py` | 親機（送信専用）。キーボード入力または一定周期で送信 |
| `rx_app.py` | 子機（受信専用）。受信データをコンソールに表示 |
| `requirements.txt` | 依存ライブラリ |
| `sample_code/operation_code/` | Raspberry Pi 用サンプル（原本） |
| `sample_code/operation_code_win/` | 上記 `send.py` / `receive.py` の Windows 版（→ [7章](#7-raspberry-pi-サンプルの-windows-版)） |

## 1. セットアップ

Python 3.8 以上が必要です。

```powershell
pip install -r requirements.txt
# または
pip install pyserial
```

## 2. COMポートの確認

FT232RL を USB 接続した状態で以下を実行します（デバイスマネージャーの「ポート (COM と LPT)」でも確認できます）。

```powershell
python tx_app.py --list
```

```
利用可能なCOMポート:
  COM9     USB Serial Port (COM9)
  COM10    USB Serial Port (COM10)
```

## 3. 実行方法

### 子機（受信）を先に起動

```powershell
python rx_app.py COM10
python rx_app.py COM9 --rssi           # RSSIバイト付加設定のモジュールで使用
python rx_app.py COM10 --hex          # 生データの16進ダンプも表示
```

| オプション | 説明 | 既定値 |
| --- | --- | --- |
| `port` | COMポート名 | （必須） |
| `-b, --baudrate` | ボーレート | 9600 |
| `--rssi` | パケット末尾の RSSI バイトを分離・表示 | OFF |
| `--hex` | 受信生データを16進表示 | OFF |
| `--idle-flush` | 改行なしデータを確定させるまでの無受信時間 [秒] | 0.5 |
| `-l, --list` | COMポート一覧を表示 | - |

Ctrl+C で終了すると、受信フレーム数・推定欠落数（SEQ の連番抜け）・推定ロス率（および `--rssi` 指定時は最小/平均/最大の RSSI）が表示されます。

### 親機（送信）

```powershell
# キーボード入力モード（Enter で送信、exit で終了）
python tx_app.py COM9

# 周期送信モード（5秒間隔、無限）
python tx_app.py COM9 --interval 5

# 固定送信モード（モジュールが固定送信設定の場合：宛先 0、チャンネル 0）
python tx_app.py COM9 -f --address 0 --channel 0 --interval 5

# 周期送信モード（2秒間隔で100回、任意文字列）
python tx_app.py COM9 -f --interval 2 --count 100 --message "hello"
```

| オプション | 説明 | 既定値 |
| --- | --- | --- |
| `port` | COMポート名 | （必須） |
| `-b, --baudrate` | ボーレート | 9600 |
| `-f, --fixed` | 固定送信モード（先頭に宛先アドレス+チャンネル3byteを付加） | OFF |
| `--address` | 固定送信の宛先アドレス (0〜65535, 0x表記可) | 0 |
| `--channel` | 固定送信の宛先チャンネル (0〜255) | 0 |
| `-i, --interval` | 周期送信間隔 [秒]。省略時はキーボード入力モード | - |
| `-m, --message` | 周期送信する文字列 | `TEST` |
| `-n, --count` | 周期送信回数（0 = 無限） | 0 |
| `--no-timestamp` | タイムスタンプを付けない | OFF |
| `-l, --list` | COMポート一覧を表示 | - |

## 4. 送信データ形式

1メッセージ = 1行の UTF-8 テキスト（末尾 `\n`）です。

```
SEQ=000001,TS=2026-10-15T00:11:00.123,MSG=hello
```

- `SEQ` : 連番。受信側で欠落（パケットロス）の推定に使用
- `TS`  : 送信側PCの時刻
- `MSG` : 本文（`,` を含んでも可）

受信側はこの形式以外のバイト列も受け付けます（改行で区切り、改行がない場合は `--idle-flush` 秒の無受信で確定）。

> **注意:** E220 の既定サブパケット長は 200 byte です。これを超えるメッセージは無線上で分割されます（`tx_app.py` が警告を表示します）。
> AUX ピンを監視していないため、短い周期で長いデータを連続送信するとモジュール内バッファが溢れる可能性があります。

## 5. ソフトウェア構成（Android 移植を考慮）

`rx_app.py` は 3 層に分離されており、層の間はコールバックでのみ接続されています。

```mermaid
flowchart LR
    A["SerialReceiver<br/>受信待ちスレッド (I/O層)"] -- "on_bytes / on_idle" --> B["MessageProcessor<br/>LineFramer / parse_message / RxStatistics (処理層)"]
    B -- "on_message(RxMessage)" --> C["ConsolePresenter<br/>表示 (UI層)"]
```

| Python | Android (Kotlin) での置き換え先 |
| --- | --- |
| `SerialReceiver` | [usb-serial-for-android](https://github.com/mik3y/usb-serial-for-android) の `SerialInputOutputManager.Listener#onNewData` |
| `MessageProcessor` / `LineFramer` / `parse_message` / `RxStatistics` | 純粋ロジックのためほぼそのまま移植 |
| `ConsolePresenter` | `ViewModel` + `LiveData`/`StateFlow`（UI スレッドへ post） |

`tx_app.py` も同様に、`LoRaTransmitter` / `build_payload`（コア）と `run_interactive` / `run_periodic`（UI）に分離しています。

## 6. 受信できない場合のチェックリスト

1. **電源**: E220 の VCC に 5V(VBUS) が来ているか。パスコン(104)が VCC-GND 直近にあるか。
2. **UART クロス接続**: FT232RL TXD → E220 RXD、FT232RL RXD → E220 TXD になっているか。
3. **GND 共通**: FT232RL と E220 の GND が接続されているか。
4. **VCCIO**: FT232RL のジャンパーが 3.3V になっているか。
5. **モード**: M0 / M1 が両方 GND に接続されているか（開放だと内部プルアップでスリープ/設定モードになり送受信しない）。
6. **COMポートの取り違え**: `--list` で表示されるポートと、実際に送信機・受信機が繋がっているポートの対応を確認。
7. **モジュール設定の一致**: 2台のアドレス・チャンネル・空中データレート等が一致しているか（工場出荷時設定同士なら一致）。過去に設定を変更したモジュールは要確認。
8. **アンテナ**: 両モジュールにアンテナが接続されているか。
9. **切り分け**: 受信側を `--hex` 付きで起動し、1 バイトも表示されないか（無線/モジュール側の問題）、文字化けするか（ボーレート設定不一致）を確認。

## 7. Raspberry Pi サンプルの Windows 版

`sample_code/operation_code/send.py` / `receive.py` を Windows 向けに移植したものです。引数・送信データ・表示内容はラズパイ版と互換で、`hexdump` ライブラリは不要です（`pyserial` のみ）。

```powershell
cd sample_code\operation_code_win

# 受信
python receive.py COM10
python receive.py COM10 --rssi          # RSSIバイト付加が有効なモジュールの場合

# 送信（トランスペアレント）
python send.py COM9 -a "hello"          # 文字列
python send.py COM9 -p 100              # 0x00,0x01,... の連番 100 byte
python send.py COM9 -F ondo.txt         # ファイルの中身（Windows版で追加）

# 送信（固定送信モード：先頭に宛先アドレス+チャンネルを付加）
python send.py COM9 -f --target_address 0 --target_channel 0 -a "hello"
```

| ラズパイ版からの変更点 | 理由 |
| --- | --- |
| ポート名を `COMx` で指定、`--list` で一覧表示 | Windows のデバイス名体系 |
| `hexdump` を内蔵実装 | 追加インストール不要にするため |
| `send.py` に `-F/--file` を追加 | PowerShell では `<` リダイレクトが使えないため（コマンドプロンプトなら `<` も可） |
| `receive.py` の受信待ちをタイムアウト方式に変更 | 元の `in_waiting` ビジーループは CPU を 100% 使うため。「200ms 途切れたら 1 パケット」の判定は同じ |
| Ctrl+C での終了・ポートエラー表示 | 使い勝手の改善 |

> [!IMPORTANT]
> ラズパイ版の `tempdata_send.sh` は `mode3.py` でモジュールを設定したうえで、`-f`（固定送信モード）で送信しています。
> モジュールが**固定送信モードに設定済み**の場合、送信データの先頭 3 byte は常に「宛先アドレス(2) + チャンネル(1)」と解釈されます。
> その場合 `-f` なしで送ると（`tx_app.py` も同様）本文の先頭が宛先として扱われ、相手に届きません。
> M0/M1 を GND 固定した本構成では設定を変更できないため、ラズパイで設定済みのモジュールを流用する場合は注意してください。
