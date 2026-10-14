#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
rx_app.py - E220-900T22L 子機（受信専用）評価スクリプト

構成:
    E220-900T22L (M0=M1=GND: ノーマルモード)  <-UART(3.3V)->  FT232RL  <-USB->  Windows PC

ノーマルモードでは、受信した無線データがそのまま UART に出力されるため、
本スクリプトは初期化コマンドを一切送信しません。
（送信側が固定送信モードでも、宛先ヘッダ 3byte は受信側には出力されません。）

RSSI バイト（--rssi）:
    モジュールの「RSSI バイト有効」設定が ON の場合、無線パケットごとに末尾 1byte の
    RSSI が付加される。パケット境界は UART 出力の途切れ（無受信時間）で判定し、
    行の組み立てより前に RSSI バイトを取り除く。RSSI[dBm] = 値 - 256。

設計方針（Android 移植を考慮したレイヤ分離）:

    +---------------------------+        on_bytes(bytes) / on_idle()
    | [I/O層] SerialReceiver    | -----------------------------------+
    |   受信待ちループ(スレッド) |                                    |
    +---------------------------+                                    v
                                         +-------------------------------------+
                                         | [処理層] MessageProcessor            |
                                         |   PacketAssembler : 途切れでパケット化|
                                         |   split_rssi : 末尾 RSSI の分離      |
                                         |   LineFramer : バイト列 -> 1行フレーム |
                                         |   parse_message : フレーム -> 構造体   |
                                         |   RxStatistics : 連番抜け・RSSI集計   |
                                         +-------------------------------------+
                                                         | on_message(RxMessage)
                                                         v
                                         +-------------------------------------+
                                         | [UI層] ConsolePresenter              |
                                         |   コンソールへの表示のみ担当           |
                                         +-------------------------------------+

    Android 移植時の対応イメージ:
        SerialReceiver   -> usb-serial-for-android の SerialInputOutputManager.Listener
                            （on_idle 相当は Handler 等の周期タイマーで呼ぶ）
        MessageProcessor -> Kotlin の純粋ロジッククラス（ほぼそのまま移植可能）
        ConsolePresenter -> ViewModel / Activity (UI スレッドへ post して表示)

使用例:
    python rx_app.py --list           # 利用可能なCOMポート一覧
    python rx_app.py COM4             # 受信開始
    python rx_app.py COM4 --hex       # 生データの16進ダンプも表示
    python rx_app.py COM4 --rssi      # 末尾の RSSI バイトを分離して表示
"""

from __future__ import annotations

import argparse
import sys
import threading
import time
from dataclasses import dataclass, field
from datetime import datetime
from typing import Callable, Dict, List, Optional

try:
    import serial
    from serial.tools import list_ports
except ImportError:  # pragma: no cover
    sys.stderr.write("pyserial が見つかりません。'pip install pyserial' を実行してください。\n")
    sys.exit(1)


# =============================================================================
# 定数
# =============================================================================
DEFAULT_BAUDRATE = 9600       # E220 デフォルト UART 速度 (8N1)
ENCODING = "utf-8"
LINE_TERMINATOR = b"\n"
# 改行が来なくても、この時間データが途切れたらフレームを確定させる [秒]
DEFAULT_IDLE_FLUSH_SEC = 0.5
# 改行が来ないまま溜まり続けた場合の安全上限 [byte]
MAX_FRAME_BYTES = 1024
# RSSI モード時、この時間 [秒] UART 出力が途切れたら 1 無線パケットの終わりとみなす。
# 9600bps では 1byte 約 1ms で連続出力されるため、パケット内でこれだけ途切れることはない。
DEFAULT_PACKET_GAP_SEC = 0.08


# =============================================================================
# [I/O層] シリアル受信待ちループ
#   - バイト列を読んでコールバックに渡すことだけを担当する。
#   - 解析・表示は一切行わない。
# =============================================================================
class SerialReceiver:
    """別スレッドでシリアルポートを監視し、受信バイト列をコールバックへ通知する。"""

    def __init__(
        self,
        port: str,
        baudrate: int,
        on_bytes: Callable[[bytes], None],
        on_idle: Optional[Callable[[], None]] = None,
        on_error: Optional[Callable[[Exception], None]] = None,
        read_timeout: float = 0.1,
    ):
        self.port = port
        self.baudrate = baudrate
        self._on_bytes = on_bytes
        self._on_idle = on_idle
        self._on_error = on_error
        self._read_timeout = read_timeout
        self._ser: Optional[serial.Serial] = None
        self._thread: Optional[threading.Thread] = None
        self._stop_event = threading.Event()

    def open(self) -> None:
        # serial_for_url は "COM3" のような通常のポート名に加え、
        # "loop://" 等のテスト用URLも受け付ける
        self._ser = serial.serial_for_url(
            self.port,
            baudrate=self.baudrate,
            bytesize=serial.EIGHTBITS,
            parity=serial.PARITY_NONE,
            stopbits=serial.STOPBITS_ONE,
            timeout=self._read_timeout,
        )
        self._ser.reset_input_buffer()

    def start(self) -> None:
        if self._ser is None or not self._ser.is_open:
            self.open()
        self._stop_event.clear()
        self._thread = threading.Thread(target=self._run, name="SerialReceiver", daemon=True)
        self._thread.start()

    def stop(self) -> None:
        self._stop_event.set()
        if self._thread is not None:
            self._thread.join(timeout=2.0)
            self._thread = None
        if self._ser is not None and self._ser.is_open:
            self._ser.close()
        self._ser = None

    @property
    def is_running(self) -> bool:
        return self._thread is not None and self._thread.is_alive()

    def _run(self) -> None:
        """受信待ちループ本体。"""
        try:
            while not self._stop_event.is_set():
                # 1byte 以上届くまで最大 read_timeout 秒待ち、届いたら溜まっている分をまとめて読む
                data = self._ser.read(max(1, self._ser.in_waiting))
                if data:
                    self._on_bytes(data)
                elif self._on_idle is not None:
                    self._on_idle()
        except Exception as e:  # serial.SerialException（USB抜けなど）を含む
            if self._on_error is not None and not self._stop_event.is_set():
                self._on_error(e)


# =============================================================================
# [処理層] データ処理（純粋ロジック：I/O にも UI にも依存しない）
# =============================================================================
@dataclass
class RxMessage:
    """受信した1フレームを表すデータ構造。"""
    received_at: datetime
    raw: bytes
    text: str
    fields: Dict[str, str] = field(default_factory=dict)
    seq: Optional[int] = None
    lost_before: int = 0          # 直前フレームとの間で欠落したと推定される件数
    terminated: bool = True       # 改行で終端されたか（False: アイドルタイムアウトで確定）
    rssi_dbm: Optional[int] = None  # このフレームを完成させた無線パケットの RSSI（--rssi 時のみ）


class PacketAssembler:
    """UART 出力の途切れを境界として、無線パケット単位にまとめる。

    feed() でデータを追加し、定期的に flush_if_idle() を呼ぶと、
    gap_sec 以上新しいデータが来ていなければ 1 パケットとして返す。
    """

    def __init__(self, gap_sec: float = DEFAULT_PACKET_GAP_SEC,
                 clock: Callable[[], float] = time.monotonic):
        self._buf = bytearray()
        self._gap_sec = gap_sec
        self._clock = clock
        self._last_rx = 0.0

    def feed(self, data: bytes) -> None:
        self._buf.extend(data)
        self._last_rx = self._clock()

    def flush_if_idle(self, force: bool = False) -> Optional[bytes]:
        if self._buf and (force or (self._clock() - self._last_rx) >= self._gap_sec):
            packet = bytes(self._buf)
            self._buf.clear()
            return packet
        return None


def split_rssi(packet: bytes) -> "tuple[bytes, Optional[int]]":
    """無線パケット末尾の RSSI バイトを分離する。戻り値: (ペイロード, RSSI[dBm])。"""
    if not packet:
        return b"", None
    return packet[:-1], packet[-1] - 256


class LineFramer:
    """断片化したバイト列を改行区切りのフレームへ組み立てる。

    LoRa は空中でサブパケットに分割されたり、UART の読み出し単位が任意だったりするため、
    1回の read() で 1メッセージが揃う保証はない。
    """

    def __init__(self, idle_flush_sec: float = DEFAULT_IDLE_FLUSH_SEC,
                 max_frame_bytes: int = MAX_FRAME_BYTES,
                 clock: Callable[[], float] = time.monotonic):
        self._buf = bytearray()
        self._idle_flush_sec = idle_flush_sec
        self._max_frame_bytes = max_frame_bytes
        self._clock = clock
        self._last_rx = 0.0

    def feed(self, data: bytes) -> List[bytes]:
        """受信データを追加し、確定したフレーム（改行除去済み）のリストを返す。"""
        self._buf.extend(data)
        self._last_rx = self._clock()
        frames: List[bytes] = []
        while True:
            idx = self._buf.find(LINE_TERMINATOR)
            if idx < 0:
                break
            frame = bytes(self._buf[:idx]).rstrip(b"\r")
            del self._buf[: idx + 1]
            frames.append(frame)
        if len(self._buf) >= self._max_frame_bytes:
            frames.append(bytes(self._buf))
            self._buf.clear()
        return frames

    def flush_if_idle(self, force: bool = False) -> Optional[bytes]:
        """一定時間データが途切れていれば（または force=True なら）、
        未終端のバッファをフレームとして返す。"""
        if self._buf and (force or (self._clock() - self._last_rx) >= self._idle_flush_sec):
            frame = bytes(self._buf)
            self._buf.clear()
            return frame
        return None


def parse_message(frame: bytes) -> Dict[str, str]:
    """'KEY=VALUE,KEY=VALUE,...' 形式（tx_app.py の送信形式）を辞書に変換する。

    MSG は最後のフィールドで、本文中に ',' を含んでもよい。
    形式に合わない場合は空の辞書を返す（任意のバイト列も受信できるようにするため）。
    """
    text = frame.decode(ENCODING, errors="replace")
    result: Dict[str, str] = {}
    rest = text
    while rest:
        if rest.startswith("MSG="):
            result["MSG"] = rest[4:]
            break
        key_val, sep, rest = rest.partition(",")
        key, eq, val = key_val.partition("=")
        if not eq or not key:
            return {}
        result[key] = val
        if not sep:
            break
    return result


class RxStatistics:
    """受信件数、SEQ の連番抜け（パケットロス推定）、RSSI を集計する。"""

    def __init__(self):
        self.total_frames = 0
        self.total_bytes = 0
        self.lost = 0
        self.rssi_count = 0
        self.rssi_sum = 0
        self.rssi_min: Optional[int] = None
        self.rssi_max: Optional[int] = None
        self._last_seq: Optional[int] = None

    def update(self, seq: Optional[int], nbytes: int) -> int:
        """統計を更新し、今回推定された欠落件数を返す。"""
        self.total_frames += 1
        self.total_bytes += nbytes
        lost_now = 0
        if seq is not None:
            if self._last_seq is not None and seq > self._last_seq + 1:
                lost_now = seq - self._last_seq - 1
                self.lost += lost_now
            # seq <= last_seq の場合は送信側の再起動とみなし、基準をリセット
            self._last_seq = seq
        return lost_now

    def update_rssi(self, rssi_dbm: int) -> None:
        """無線パケット 1 つ分の RSSI を集計する。"""
        self.rssi_count += 1
        self.rssi_sum += rssi_dbm
        self.rssi_min = rssi_dbm if self.rssi_min is None else min(self.rssi_min, rssi_dbm)
        self.rssi_max = rssi_dbm if self.rssi_max is None else max(self.rssi_max, rssi_dbm)

    @property
    def rssi_avg(self) -> Optional[float]:
        return self.rssi_sum / self.rssi_count if self.rssi_count else None


class MessageProcessor:
    """I/O層から受け取ったバイト列を RxMessage に変換し、UI層へ通知する。

    rssi_enabled=True の場合:
        バイト列 -> PacketAssembler(途切れでパケット化) -> split_rssi -> LineFramer
    rssi_enabled=False の場合:
        バイト列 -> LineFramer
    いずれの場合も handle_idle() を定期的に呼ぶ必要がある。
    """

    def __init__(self, on_message: Callable[[RxMessage], None],
                 on_raw: Optional[Callable[[bytes], None]] = None,
                 idle_flush_sec: float = DEFAULT_IDLE_FLUSH_SEC,
                 rssi_enabled: bool = False,
                 packet_gap_sec: float = DEFAULT_PACKET_GAP_SEC):
        self._on_message = on_message
        self._on_raw = on_raw
        self._framer = LineFramer(idle_flush_sec=idle_flush_sec)
        self._rssi_enabled = rssi_enabled
        self._packets = PacketAssembler(gap_sec=packet_gap_sec) if rssi_enabled else None
        self._last_rssi: Optional[int] = None
        self.stats = RxStatistics()
        self._lock = threading.Lock()

    # --- SerialReceiver から呼ばれるコールバック ---------------------------
    def handle_bytes(self, data: bytes) -> None:
        if self._on_raw is not None:
            self._on_raw(data)
        if self._packets is not None:
            # RSSI モード: パケットが完成する（途切れる）まで溜める
            with self._lock:
                self._packets.feed(data)
            return
        with self._lock:
            frames = self._framer.feed(data)
        for frame in frames:
            self._emit(frame, terminated=True)

    def handle_idle(self, force: bool = False) -> None:
        if self._packets is not None:
            with self._lock:
                packet = self._packets.flush_if_idle(force)
                frames: List[bytes] = []
                if packet is not None:
                    payload, rssi = split_rssi(packet)
                    if rssi is not None:
                        self._last_rssi = rssi
                        self.stats.update_rssi(rssi)
                    frames = self._framer.feed(payload)
            for frame in frames:
                self._emit(frame, terminated=True)
        with self._lock:
            frame = self._framer.flush_if_idle(force)
        if frame is not None:
            self._emit(frame, terminated=False)

    # --- 内部処理 -----------------------------------------------------------
    def _emit(self, frame: bytes, terminated: bool) -> None:
        if not frame:
            return
        fields = parse_message(frame)
        seq: Optional[int] = None
        if "SEQ" in fields:
            try:
                seq = int(fields["SEQ"])
            except ValueError:
                seq = None
        lost = self.stats.update(seq, len(frame))
        msg = RxMessage(
            received_at=datetime.now(),
            raw=frame,
            text=frame.decode(ENCODING, errors="replace"),
            fields=fields,
            seq=seq,
            lost_before=lost,
            terminated=terminated,
            rssi_dbm=self._last_rssi if self._rssi_enabled else None,
        )
        self._on_message(msg)


# =============================================================================
# [UI層] コンソール表示（プラットフォーム依存部分）
# =============================================================================
class ConsolePresenter:
    """受信結果を標準出力へ表示する。Android では Activity/ViewModel に置き換える部分。"""

    def __init__(self, show_hex: bool = False):
        self._show_hex = show_hex
        self._print_lock = threading.Lock()

    @staticmethod
    def _now() -> str:
        return datetime.now().strftime("%H:%M:%S.%f")[:-3]

    def show_raw(self, data: bytes) -> None:
        if not self._show_hex:
            return
        with self._print_lock:
            print(f"[{self._now()}] RAW ({len(data):3d} bytes): {data.hex(' ')}", flush=True)

    def show_message(self, msg: RxMessage) -> None:
        ts = msg.received_at.strftime("%H:%M:%S.%f")[:-3]
        with self._print_lock:
            if msg.lost_before:
                print(f"  ! 欠落の可能性: {msg.lost_before} 件 (SEQ が飛びました)", flush=True)
            note = "" if msg.terminated else " (改行なし/タイムアウト確定)"
            rssi = f" RSSI={msg.rssi_dbm:4d}dBm" if msg.rssi_dbm is not None else ""
            print(f"[{ts}] RX ({len(msg.raw):3d} bytes){rssi}{note}: {msg.text}", flush=True)

    def show_error(self, e: Exception) -> None:
        with self._print_lock:
            print(f"エラー: シリアル通信エラー: {e}", file=sys.stderr, flush=True)

    def show_summary(self, stats: RxStatistics) -> None:
        print("---- 受信サマリ ----")
        print(f"  受信フレーム数 : {stats.total_frames}")
        print(f"  受信バイト数   : {stats.total_bytes}")
        print(f"  推定欠落数     : {stats.lost}")
        expected = stats.total_frames + stats.lost
        if expected and stats.lost:
            print(f"  推定ロス率     : {stats.lost / expected * 100:.1f} %")
        if stats.rssi_count:
            print(f"  RSSI [dBm]     : 最小 {stats.rssi_min} / 平均 {stats.rssi_avg:.1f} / "
                  f"最大 {stats.rssi_max}  ({stats.rssi_count} パケット)")


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
        description="E220-900T22L 子機（受信専用）評価スクリプト",
    )
    parser.add_argument("port", nargs="?", help="COMポート名 (例: COM4)")
    parser.add_argument("-b", "--baudrate", type=int, default=DEFAULT_BAUDRATE,
                        help=f"ボーレート (既定: {DEFAULT_BAUDRATE})")
    parser.add_argument("--hex", action="store_true",
                        help="受信した生データを16進ダンプでも表示する")
    parser.add_argument("--idle-flush", type=float, default=DEFAULT_IDLE_FLUSH_SEC,
                        help="改行なしデータを確定させるまでの無受信時間[秒] "
                             f"(既定: {DEFAULT_IDLE_FLUSH_SEC})")
    parser.add_argument("--rssi", action="store_true",
                        help="受信パケット末尾の RSSI バイトを分離して表示する"
                             "（モジュールの RSSI バイト有効設定が ON の場合に指定）")
    parser.add_argument("-l", "--list", action="store_true",
                        help="利用可能なCOMポートを表示して終了")
    args = parser.parse_args(argv)
    if not args.list and not args.port:
        parser.error("COMポートを指定してください (例: python rx_app.py COM4)。"
                     " 一覧は --list で確認できます。")
    return args


def main(argv=None) -> int:
    args = parse_args(argv)
    if args.list:
        print_ports()
        return 0

    # 各層を組み立てる（依存はコールバックで注入）
    presenter = ConsolePresenter(show_hex=args.hex)
    processor = MessageProcessor(
        on_message=presenter.show_message,
        on_raw=presenter.show_raw,
        idle_flush_sec=args.idle_flush,
        rssi_enabled=args.rssi,
    )
    error_event = threading.Event()

    def on_error(e: Exception) -> None:
        presenter.show_error(e)
        error_event.set()

    receiver = SerialReceiver(
        port=args.port,
        baudrate=args.baudrate,
        on_bytes=processor.handle_bytes,
        on_idle=processor.handle_idle,
        on_error=on_error,
    )

    try:
        receiver.open()
    except serial.SerialException as e:
        print(f"エラー: {args.port} を開けません: {e}", file=sys.stderr)
        print_ports()
        return 1

    print(f"{args.port} を開きました ({args.baudrate} bps, 8N1)。受信待機中... Ctrl+C で終了。",
          flush=True)
    receiver.start()
    exit_code = 0
    try:
        # メインスレッドは待機のみ（Ctrl+C を受け付けるため短い間隔で wait）
        while not error_event.wait(0.2):
            pass
        exit_code = 1
    except KeyboardInterrupt:
        print("\n中断しました。")
    finally:
        receiver.stop()
        processor.handle_idle(force=True)  # 残りのバッファがあれば表示
        presenter.show_summary(processor.stats)
        print(f"{args.port} を閉じました。")
    return exit_code


if __name__ == "__main__":
    sys.exit(main())
