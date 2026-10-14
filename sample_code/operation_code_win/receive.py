#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
receive.py (Windows版) - E220-900JP 受信サンプル

Raspberry Pi 版 sample_code/operation_code/receive.py の Windows 移植です。
コマンドライン引数・表示内容はラズパイ版と互換です。

ラズパイ版からの主な変更点:
    - serial_port に COMポート名 (例: COM10) を指定する（/dev/ttyS0 の代わり）
    - 外部ライブラリ hexdump を不要にした（同じ書式の16進ダンプを内蔵）
    - in_waiting を回し続けるビジーループ（CPU 100%）をやめ、読み出しタイムアウトで
      「200ms 受信が途切れたら 1 パケット受信完了」と判定するようにした
    - Ctrl+C で正常に終了できるようにした
    - COMポート一覧を表示する -l/--list オプションを追加

使用例:
    python receive.py --list
    python receive.py COM10
    python receive.py COM10 --rssi     (モジュールの RSSI バイト付加設定が有効な場合)
"""

import argparse
import sys

try:
    import serial
    from serial.tools import list_ports
except ImportError:
    sys.stderr.write("pyserial が見つかりません。'pip install pyserial' を実行してください。\n")
    sys.exit(1)


# この時間 [秒] 受信が途切れたら 1 パケットの受信完了とみなす（ラズパイ版の sleep(0.200) 相当）
PACKET_GAP_SEC = 0.200


def hexdump(data: bytes) -> None:
    """hexdump ライブラリ (hexdump.hexdump) と同じ書式で16進ダンプを表示する。

    例: 00000000: 53 45 4E 44 20 54 45 53  54 0A                    SEND TEST.
    """
    for offset in range(0, len(data), 16):
        chunk = data[offset:offset + 16]
        hex_left = " ".join(f"{b:02X}" for b in chunk[:8])
        hex_right = " ".join(f"{b:02X}" for b in chunk[8:])
        hex_part = f"{hex_left:<23}  {hex_right:<23}"
        ascii_part = "".join(chr(b) if 0x20 <= b < 0x7F else "." for b in chunk)
        print(f"{offset:08X}: {hex_part}  {ascii_part}")


def print_ports() -> None:
    ports = list(list_ports.comports())
    if not ports:
        print("利用可能なCOMポートが見つかりません。")
        return
    print("利用可能なCOMポート:")
    for p in ports:
        print(f"  {p.device:8s} {p.description}")


def get_args():
    parser = argparse.ArgumentParser(description="E220-900JP 受信サンプル (Windows版)")

    parser.add_argument("serial_port", nargs="?", help="COMポート名 (例: COM10)")
    parser.add_argument("-b", "--baud", default="9600", help="ボーレート (既定: 9600)")
    parser.add_argument("-m", "--model", default="E220-900JP")
    parser.add_argument("--rssi", action="store_true",
                        help="受信データ末尾の 1byte を RSSI として表示")
    parser.add_argument("-l", "--list", action="store_true",
                        help="利用可能なCOMポートを表示して終了 (Windows版で追加)")

    args = parser.parse_args()

    if not args.list and args.serial_port is None:
        parser.error("serial_port を指定してください (例: python receive.py COM10)。"
                     " 一覧は --list で確認できます。")

    return args


def show_packet(payload: bytes, with_rssi: bool) -> None:
    """1 パケット分の受信データを表示する（ラズパイ版と同じ表示内容）。"""
    print("recv data hex dump:")
    hexdump(payload)
    if with_rssi:
        rssi = int(payload[-1]) - 256
        print(f"RSSI: {rssi} dBm")
    print("RECEIVED\n", flush=True)


def main():
    args = get_args()

    if args.list:
        print_ports()
        return 0

    if args.model != "E220-900JP":
        print("INVALID")
        return 1

    print("serial port:")
    print(args.serial_port)

    try:
        ser = serial.Serial(args.serial_port, int(args.baud), timeout=PACKET_GAP_SEC)
    except serial.SerialException as e:
        print(f"エラー: {args.serial_port} を開けません: {e}", file=sys.stderr)
        print_ports()
        return 1

    print("receive waiting... (Ctrl+C で終了)", flush=True)
    payload = bytes()
    try:
        with ser:
            while True:
                # 届いている分をまとめて読む。何も届かなければ最大 PACKET_GAP_SEC 秒待って b"" が返る
                data = ser.read(max(1, ser.in_waiting))
                if data:
                    payload = payload + data
                elif len(payload) != 0:
                    show_packet(payload, args.rssi)
                    payload = bytes()
    except KeyboardInterrupt:
        if payload:
            show_packet(payload, args.rssi)
        print("終了しました。")
    except serial.SerialException as e:
        print(f"エラー: シリアル通信エラー: {e}", file=sys.stderr)
        return 1

    return 0


if __name__ == "__main__":
    sys.exit(main())
