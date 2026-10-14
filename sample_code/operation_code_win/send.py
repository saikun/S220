#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
send.py (Windows版) - E220-900JP 送信サンプル

Raspberry Pi 版 sample_code/operation_code/send.py の Windows 移植です。
コマンドライン引数・送信データの組み立て方はラズパイ版と互換です。

ラズパイ版からの主な変更点:
    - serial_port に COMポート名 (例: COM9) を指定する（/dev/ttyS0 の代わり）
    - 外部ライブラリ hexdump を不要にした（同じ書式の16進ダンプを内蔵）
    - Windows の PowerShell では '<' リダイレクトが使えないため、
      ファイルから送信する -F/--file オプションを追加
    - COMポート一覧を表示する -l/--list オプションを追加
    - ポートが開けない等のエラーを分かりやすく表示

使用例:
    python send.py --list
    python send.py COM9 -a "hello"
    python send.py COM9 -p 100
    python send.py COM9 -F ondo.txt
    python send.py COM9 -f --target_address 0 --target_channel 0 -a "hello"
    type ondo.txt | python send.py COM9        (コマンドプロンプト)
    python send.py COM9 < ondo.txt             (コマンドプロンプト)
"""

import argparse
import sys

try:
    import serial
    from serial.tools import list_ports
except ImportError:
    sys.stderr.write("pyserial が見つかりません。'pip install pyserial' を実行してください。\n")
    sys.exit(1)


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
    parser = argparse.ArgumentParser(description="E220-900JP 送信サンプル (Windows版)")

    parser.add_argument("serial_port", nargs="?", help="COMポート名 (例: COM9)")
    parser.add_argument("-b", "--baud", default="9600", help="ボーレート (既定: 9600)")
    parser.add_argument("-m", "--model", default="E220-900JP")
    parser.add_argument("-p", "--payload_length", help="0x00,0x01,... の連番データを指定バイト数送信")
    parser.add_argument("-a", "--ascii_text", help="指定した文字列を送信")
    parser.add_argument("-F", "--file", help="指定したファイルの内容をそのまま送信 (Windows版で追加)")
    parser.add_argument("-f", "--fixed_mode", action="store_true",
                        help="固定送信モード: 先頭に送信先アドレス(2byte)+チャンネル(1byte)を付加")
    parser.add_argument("--target_address", help="固定送信モードの送信先アドレス (0-65535)")
    parser.add_argument("--target_channel", help="固定送信モードの送信先チャンネル")
    parser.add_argument("-l", "--list", action="store_true",
                        help="利用可能なCOMポートを表示して終了 (Windows版で追加)")

    args = parser.parse_args()

    if not args.list and args.serial_port is None:
        parser.error("serial_port を指定してください (例: python send.py COM9 -a hello)。"
                     " 一覧は --list で確認できます。")

    return args


def build_payload(args):
    """ラズパイ版と同じ規則で送信データを組み立てる。不正な指定の場合は None。"""
    if args.fixed_mode:
        if (args.target_address is not None) and (args.target_channel is not None):
            t_addr = int(args.target_address)
            t_addr_H = t_addr >> 8
            t_addr_L = t_addr & 0xFF
            t_ch = int(args.target_channel)
            payload = bytes([t_addr_H, t_addr_L, t_ch])
        else:
            return None
    else:
        payload = bytes([])

    if args.payload_length is not None:
        length = int(args.payload_length)
        # 0x00..0xFF を繰り返した連番データ（ラズパイ版と同一の内容）
        payload = payload + bytes(i & 0xFF for i in range(length))
    elif args.ascii_text is not None:
        payload = payload + args.ascii_text.encode()
    elif args.file is not None:
        with open(args.file, "rb") as f:
            payload = payload + f.read()
    else:
        if sys.stdin.isatty():
            print("送信データを入力してください (入力終了: 新しい行で Ctrl+Z → Enter)")
        payload = payload + sys.stdin.buffer.read()

    return payload


def main():
    args = get_args()

    if args.list:
        print_ports()
        return 0

    if args.model != "E220-900JP":
        print("INVALID")
        return 1

    payload = build_payload(args)
    if payload is None:
        print("INVALID")
        print("  -f (固定送信モード) には --target_address と --target_channel が必要です。")
        return 1

    print("serial port:")
    print(args.serial_port)

    print("send data hex dump:")
    hexdump(payload)

    try:
        with serial.Serial(args.serial_port, int(args.baud), timeout=None, write_timeout=5) as ser:
            ser.write(payload)
            ser.flush()
            print("SENT")
    except serial.SerialTimeoutException:
        print(f"エラー: {args.serial_port} への書き込みがタイムアウトしました。", file=sys.stderr)
        return 1
    except serial.SerialException as e:
        print(f"エラー: {args.serial_port} を開けません: {e}", file=sys.stderr)
        print_ports()
        return 1

    return 0


if __name__ == "__main__":
    sys.exit(main())
