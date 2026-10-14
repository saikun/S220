#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
tx_app.py - E220-900T22L 親機（送信専用）評価スクリプト

構成:
    Windows PC  <-USB->  FT232RL  <-UART(3.3V)->  E220-900T22L (M0=M1=GND: ノーマルモード)

ノーマルモードでは、シリアルに書き込んだバイト列がそのまま無線送信されるため、
本スクリプトは初期化コマンドを一切送信しません。

送信方式（モジュールの「送信方法」設定に合わせて選択）:
    - トランスペアレント送信モード（工場出荷時）: データをそのまま書き込む
    - 固定送信モード (-f)                : 先頭に 宛先アドレス(2byte) + チャンネル(1byte)
                                         を付加して書き込む（この 3byte は受信側には出力されない）

設計方針:
    - [コア層]  LoRaTransmitter / build_payload / build_fixed_header : UART送信とペイロード生成（UI非依存）
    - [UI層]    run_interactive / run_periodic / main : コンソール入出力・引数処理

使用例:
    python tx_app.py --list                     # 利用可能なCOMポート一覧
    python tx_app.py COM3                       # キーボード入力モード
    python tx_app.py COM3 --interval 5          # 5秒周期の自動送信モード
    python tx_app.py COM3 --interval 5 --message "hello"
    python tx_app.py COM3 -f --address 0 --channel 0 --interval 5   # 固定送信モード
"""

from __future__ import annotations

import argparse
import sys
import threading
import time
from datetime import datetime
from typing import Optional, Tuple

try:
    import serial
    from serial.tools import list_ports
except ImportError:  # pragma: no cover
    sys.stderr.write("pyserial が見つかりません。'pip install pyserial' を実行してください。\n")
    sys.exit(1)


# =============================================================================
# 定数
# =============================================================================
DEFAULT_BAUDRATE = 9600          # E220 デフォルト UART 速度 (8N1)
ENCODING = "utf-8"
LINE_TERMINATOR = b"\n"          # 受信側でのフレーム区切りに使用
# E220 のデフォルト「サブパケット長」は 200 byte。
# これを超えるデータはモジュール内で分割送信されるため、目安として警告を出す。
MAX_SUBPACKET_BYTES = 200


# =============================================================================
# コア層（UART送信ロジック：UI非依存）
# =============================================================================
def build_payload(seq: int, message: str, with_timestamp: bool = True) -> bytes:
    """送信用ペイロードを生成する。

    フォーマット（1行テキスト、末尾 '\\n'）:
        SEQ=000001,TS=2026-10-15T00:11:00.123,MSG=<message>\\n

    受信側では SEQ の連番抜けからパケットロスを、TS から遅延の目安を確認できる。
    """
    fields = [f"SEQ={seq:06d}"]
    if with_timestamp:
        fields.append("TS=" + datetime.now().isoformat(timespec="milliseconds"))
    fields.append(f"MSG={message}")
    return ",".join(fields).encode(ENCODING) + LINE_TERMINATOR


def build_fixed_header(address: int, channel: int) -> bytes:
    """固定送信モード用の先頭 3byte（宛先アドレス H, L, チャンネル）を生成する。"""
    if not 0 <= address <= 0xFFFF:
        raise ValueError(f"address は 0～65535 の範囲で指定してください: {address}")
    if not 0 <= channel <= 0xFF:
        raise ValueError(f"channel は 0～255 の範囲で指定してください: {channel}")
    return bytes([address >> 8, address & 0xFF, channel])


class LoRaTransmitter:
    """E220 へバイト列を書き込む薄いラッパー（スレッドセーフ）。

    target=(address, channel) を指定すると固定送信モードとなり、
    send() のたびに宛先ヘッダ 3byte を先頭に付加する。
    """

    def __init__(self, port: str, baudrate: int = DEFAULT_BAUDRATE, write_timeout: float = 2.0,
                 target: Optional[Tuple[int, int]] = None):
        self.port = port
        self.baudrate = baudrate
        self.write_timeout = write_timeout
        self.target = target
        self._header = build_fixed_header(*target) if target is not None else b""
        self._ser: Optional[serial.Serial] = None
        self._lock = threading.Lock()

    def open(self) -> None:
        # serial_for_url は "COM3" のような通常のポート名に加え、
        # "loop://" 等のテスト用URLも受け付ける
        self._ser = serial.serial_for_url(
            self.port,
            baudrate=self.baudrate,
            bytesize=serial.EIGHTBITS,
            parity=serial.PARITY_NONE,
            stopbits=serial.STOPBITS_ONE,
            timeout=0.1,
            write_timeout=self.write_timeout,
        )
        # 開いた直後に溜まっている不要データを捨てる
        self._ser.reset_input_buffer()
        self._ser.reset_output_buffer()

    def close(self) -> None:
        if self._ser is not None and self._ser.is_open:
            self._ser.close()
        self._ser = None

    @property
    def is_open(self) -> bool:
        return self._ser is not None and self._ser.is_open

    def send(self, payload: bytes) -> int:
        """ペイロードを送信し、UART に書き込んだバイト数（固定モード時はヘッダ含む）を返す。"""
        if not self.is_open:
            raise RuntimeError("シリアルポートが開かれていません。")
        with self._lock:
            written = self._ser.write(self._header + payload)
            self._ser.flush()  # OS バッファから FT232RL へ確実に送り出す
        return written

    # with 文対応
    def __enter__(self) -> "LoRaTransmitter":
        self.open()
        return self

    def __exit__(self, exc_type, exc, tb) -> None:
        self.close()


# =============================================================================
# UI層（コンソール）
# =============================================================================
def log_sent(payload: bytes, written: int) -> None:
    """送信内容をコンソールに表示する（バイト数は UART に書き込んだ総数）。"""
    now = datetime.now().strftime("%H:%M:%S.%f")[:-3]
    text = payload.decode(ENCODING, errors="replace").rstrip("\r\n")
    print(f"[{now}] TX ({written:3d} bytes): {text}", flush=True)
    # 固定モードのヘッダ 3byte は無線ペイロードに含まれないため、ペイロード長で判定する
    if len(payload) > MAX_SUBPACKET_BYTES:
        print(f"  ! 警告: {len(payload)} bytes はサブパケット長 {MAX_SUBPACKET_BYTES} bytes を"
              f"超えるため、無線上で分割送信されます。", flush=True)


def run_interactive(tx: LoRaTransmitter, with_timestamp: bool) -> None:
    """キーボードから1行入力するたびに送信する。"""
    print("キーボード入力モード: 文字列を入力して Enter で送信します。")
    print("  空行 Enter -> タイムスタンプのみ送信 / 'exit' または Ctrl+C -> 終了")
    seq = 0
    while True:
        try:
            line = input("> ")
        except EOFError:
            break
        if line.strip().lower() in ("exit", "quit"):
            break
        seq += 1
        payload = build_payload(seq, line if line else "ping", with_timestamp)
        written = tx.send(payload)
        log_sent(payload, written)


def run_periodic(tx: LoRaTransmitter, interval: float, message: str,
                 count: int, with_timestamp: bool) -> None:
    """一定周期でテストデータを送信する。count=0 なら無限。"""
    print(f"周期送信モード: {interval} 秒間隔で送信します。Ctrl+C で終了。")
    seq = 0
    next_time = time.monotonic()
    while count == 0 or seq < count:
        seq += 1
        payload = build_payload(seq, message, with_timestamp)
        written = tx.send(payload)
        log_sent(payload, written)

        # 周期のずれ（ドリフト）が蓄積しないよう、絶対時刻基準で待つ
        next_time += interval
        sleep_sec = next_time - time.monotonic()
        if sleep_sec > 0:
            time.sleep(sleep_sec)
        else:
            next_time = time.monotonic()


def print_ports() -> None:
    ports = list(list_ports.comports())
    if not ports:
        print("利用可能なCOMポートが見つかりません。")
        return
    print("利用可能なCOMポート:")
    for p in ports:
        print(f"  {p.device:8s} {p.description}")


def parse_args(argv=None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="E220-900T22L 親機（送信専用）評価スクリプト",
    )
    parser.add_argument("port", nargs="?", help="COMポート名 (例: COM3)")
    parser.add_argument("-b", "--baudrate", type=int, default=DEFAULT_BAUDRATE,
                        help=f"ボーレート (既定: {DEFAULT_BAUDRATE})")
    parser.add_argument("-i", "--interval", type=float, default=None,
                        help="周期送信の間隔[秒]。指定しない場合はキーボード入力モード")
    parser.add_argument("-m", "--message", default="TEST",
                        help="周期送信モードで送る文字列 (既定: TEST)")
    parser.add_argument("-n", "--count", type=int, default=0,
                        help="周期送信の回数。0 で無限 (既定: 0)")
    parser.add_argument("--no-timestamp", action="store_true",
                        help="ペイロードにタイムスタンプを含めない")
    parser.add_argument("-f", "--fixed", action="store_true",
                        help="固定送信モード: 先頭に宛先アドレス+チャンネル(3byte)を付加する")
    parser.add_argument("--address", type=lambda s: int(s, 0), default=0,
                        help="固定送信モードの宛先アドレス 0～65535 (既定: 0, 0x 表記可)")
    parser.add_argument("--channel", type=lambda s: int(s, 0), default=0,
                        help="固定送信モードの宛先チャンネル (既定: 0)")
    parser.add_argument("-l", "--list", action="store_true",
                        help="利用可能なCOMポートを表示して終了")
    args = parser.parse_args(argv)

    if not args.list and not args.port:
        parser.error("COMポートを指定してください (例: python tx_app.py COM3)。"
                     " 一覧は --list で確認できます。")
    if args.interval is not None and args.interval <= 0:
        parser.error("--interval は正の値を指定してください。")
    if args.fixed:
        try:
            build_fixed_header(args.address, args.channel)
        except ValueError as e:
            parser.error(str(e))
    return args


def main(argv=None) -> int:
    args = parse_args(argv)
    if args.list:
        print_ports()
        return 0

    with_ts = not args.no_timestamp
    target = (args.address, args.channel) if args.fixed else None
    tx = LoRaTransmitter(args.port, args.baudrate, target=target)
    try:
        tx.open()
    except serial.SerialException as e:
        print(f"エラー: {args.port} を開けません: {e}", file=sys.stderr)
        print_ports()
        return 1

    print(f"{args.port} を開きました ({args.baudrate} bps, 8N1)")
    if target is not None:
        print(f"固定送信モード: 宛先アドレス={args.address} (0x{args.address:04X}), "
              f"チャンネル={args.channel}")
    else:
        print("トランスペアレント送信モード（モジュールが固定送信設定の場合は -f を指定）")
    try:
        if args.interval is None:
            run_interactive(tx, with_ts)
        else:
            run_periodic(tx, args.interval, args.message, args.count, with_ts)
    except KeyboardInterrupt:
        print("\n中断しました。")
    except serial.SerialTimeoutException:
        print("エラー: 書き込みタイムアウト。配線・電源を確認してください。", file=sys.stderr)
        return 1
    except serial.SerialException as e:
        print(f"エラー: シリアル通信エラー: {e}", file=sys.stderr)
        return 1
    finally:
        tx.close()
        print(f"{args.port} を閉じました。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
