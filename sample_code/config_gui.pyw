import PySimpleGUI as sg
import sys
import glob
import serial
import time

# E220-900T22S(JP) / E220-900T22L(JP) ver.2.0 設定ツール
# モジュールを設定モード (M0=1, M1=1) にしてから使用してください。
# 設定モードでの UART は 9600bps 8N1 固定です。

serial_port_values = []

MODEL_22S = "E220-900T22S(JP)"
MODEL_22L = "E220-900T22L(JP)"
MODEL_LEGACY = "ver1.x 互換"
model_values = [MODEL_22S, MODEL_22L, MODEL_LEGACY]

uart_values = ["1200", "2400", "4800", "9600", "19200", "38400", "57600", "115200"]
bw_values = ["125", "250", "500"]
sf_values = ["5", "6", "7", "8", "9", "10", "11"]
sub_packet_values = ["200", "128", "64", "32"]
on_off_values = ["有効", "無効"]
transmission_method_values = ["トランスペアレント送信モード", "固定送信モード"]
wor_cycle_values = ["500", "1000", "1500", "2000", "2500", "3000"]
power_table_values = ["テーブルA", "テーブルB"]
uvlo_values = ["有効 (閾値2.0V)", "無効 (閾値2.9V)"]
aux_wait_values = ["2～3", "512", "1024", "0 (待機なし)"]

# (SFインデックス, BWインデックス) -> エアデータレート [bps]
AIR_DATA_RATE = {
    (0, 0): 15625, (1, 0): 9375, (2, 0): 5469, (3, 0): 3125, (4, 0): 1758,
    (0, 1): 31250, (1, 1): 18750, (2, 1): 10938, (3, 1): 6250, (4, 1): 3516,
    (5, 1): 1953,
    (0, 2): 62500, (1, 2): 37500, (2, 2): 21875, (3, 2): 12500, (4, 2): 7031,
    (5, 2): 3906, (6, 2): 2148,
}

# BWごとの チャネル上限 / 基準周波数 / 22L で送信可能な最大チャネル
CH_MAX = [37, 36, 30]
FREQ_BASE = [920.6, 920.7, 920.8]
CH_22L_TX_MAX = [14, 13, 12]

# 送信電力テーブル (設定値, dBm)
POWER_22S = [
    (1, 13), (2, 7), (3, 0), (4, 1), (5, 2), (6, 3), (7, 4), (8, 5),
    (9, 6), (10, 7), (11, 8), (12, 9), (13, 10), (14, 11), (15, 12),
]
POWER_22L_A = [
    (1, 13), (2, 7), (3, 0), (4, 20), (5, 17), (6, 10), (7, 10), (8, 10),
    (9, 22), (10, 21), (11, 20), (12, 19), (13, 18), (14, 17), (15, 16),
]
POWER_22L_B = [(code, 15 - code) for code in range(16)]
POWER_LEGACY = [(0, 13), (1, 12), (2, 7), (3, 0)]

DEFAULT_POWER_CODE = 1

CONFIG_KEYS = [
    "model_combo", "addr", "uart_combo", "sf_combo", "bw_combo",
    "sub_packet_combo", "rssi_ambient_combo", "transmitting_power_combo",
    "power_table_combo", "channel", "rssi_byte_combo",
    "transmission_method_combo", "wor_cycle_combo", "encryption_key",
]
EXT_KEYS = [
    "uvlo_combo", "strict_combo", "dest_addr_combo", "checksum_in_combo",
    "checksum_out_combo", "size_out_combo", "aux_wait_combo", "cs_timeout",
    "uid", "uid_get_button", "uid_set_button",
]

# 読み込み時の状態
# checksum_in / size_out / checksum_out はモジュールに現在設定されている
# EXT REG (09H) の値で、コマンドの送受信形式に影響する
state = {
    "legacy": False,
    "strict": 0,
    "enabled": False,
    "checksum_in": 0,
    "checksum_out": 0,
    "size_out": 0,
}

VALUE_KEYS = CONFIG_KEYS + [
    "uvlo_combo", "strict_combo", "dest_addr_combo", "checksum_in_combo",
    "checksum_out_combo", "size_out_combo", "aux_wait_combo", "cs_timeout",
]


class CommandError(Exception):
    pass


def serial_ports():
    """Lists serial port names

    :raises EnvironmentError:
        On unsupported or unknown platforms
    :returns:
        A list of the serial ports available on the system
    """
    if sys.platform.startswith("win"):
        ports = ["COM%s" % (i + 1) for i in range(256)]
    elif sys.platform.startswith("linux") or sys.platform.startswith("cygwin"):
        # this excludes your current terminal "/dev/tty"
        ports = glob.glob("/dev/tty[A-Za-z]*")
    elif sys.platform.startswith("darwin"):
        ports = glob.glob("/dev/tty.*")
    else:
        raise EnvironmentError("Unsupported platform")

    result = []
    for port in ports:
        try:
            s = serial.Serial(port)
            s.close()
            result.append(port)
        except (OSError, serial.SerialException):
            pass
    return result


def serial_open(port):
    try:
        ser = serial.Serial(port, baudrate=9600, timeout=0)
    except (OSError, serial.SerialException):
        return None

    return ser


def serial_close(ser: serial.Serial):
    try:
        ser.close()
    except (OSError, serial.SerialException):
        pass


def to_hex(data):
    return list(map(lambda n: "0x" + format(n, "02x"), data))


def checksum(data):
    return sum(data) & 0xFF


def send_command(ser: serial.Serial, command, expected_len, timeout=1.5):
    """コマンドを送信して応答を受信する

    EXT REG (09H) の設定に応じて、送信時は CHECKSUM を付加し、
    受信時はサイズ・CHECKSUM の付加分も待ち受ける。
    """
    ser.reset_input_buffer()

    if state["checksum_in"]:
        command = command + [checksum(command)]
    expected_len += state["size_out"] + state["checksum_out"]

    print("# Command Request")
    print(to_hex(command))

    ser.write(bytes(command))
    ser.flush()

    error = bytes([0xFF, 0xFF, 0xFF])
    response = bytearray()
    deadline = time.time() + timeout
    while time.time() < deadline and len(response) < expected_len:
        if error in response[:4]:
            break
        n = ser.in_waiting
        if n:
            response += ser.read(n)
        else:
            time.sleep(0.01)

    # 想定外の付加バイトがあれば少し待って受け取る
    time.sleep(0.05)
    if ser.in_waiting:
        response += ser.read(ser.in_waiting)

    print("# Command Response")
    print(to_hex(response))

    if error in response[:4]:
        raise CommandError("Command error (FF FF FF)")
    return bytes(response)


def extract_data(response, header, length):
    """応答から header に続く length バイトを取り出す (先頭のサイズ・末尾のCHECKSUMは無視)"""
    index = bytes(response).find(bytes(header))
    if index < 0 or len(response) < index + len(header) + length:
        raise CommandError("Invalid response")
    start = index + len(header)
    return list(response[start : start + length])


def read_registers(ser: serial.Serial, address, length):
    response = send_command(ser, [0xC1, address, length], 3 + length)
    return extract_data(response, [0xC1, address, length], length)


def write_registers(ser: serial.Serial, address, data):
    response = send_command(ser, [0xC0, address, len(data)] + data, 3 + len(data))
    return extract_data(response, [0xC1, address, len(data)], len(data))


def set_framing(ext):
    """09H の値から送受信形式を更新する"""
    state["checksum_in"] = (ext & 0b0001_0000) >> 4
    state["checksum_out"] = (ext & 0b0000_1000) >> 3
    state["size_out"] = (ext & 0b0000_0100) >> 2


def read_config_registers(ser: serial.Serial):
    """00H～0AH を読み込む。CHECKSUM 必須設定の有無が不明なため両方を試す"""
    for checksum_in in (state["checksum_in"], 1 - state["checksum_in"]):
        state["checksum_in"] = checksum_in
        try:
            registers = read_registers(ser, 0x00, 0x0B)
        except CommandError:
            continue
        set_framing(registers[9])
        return registers, False

    # ver.1.x: 00H～07H のみ
    set_framing(0)
    return read_registers(ser, 0x00, 0x08), True


def read_registers_each(ser: serial.Serial, address, length):
    """一括読み込みが拒否された場合に 1 バイトずつ読み込む"""
    try:
        return read_registers(ser, address, length)
    except CommandError:
        return [read_registers(ser, address + i, 1)[0] for i in range(length)]


def write_registers_each(ser: serial.Serial, address, data):
    """一括書き込みが拒否された場合に 1 バイトずつ書き込む"""
    try:
        return write_registers(ser, address, data)
    except CommandError:
        echo = []
        for i, value in enumerate(data):
            try:
                echo += write_registers(ser, address + i, [value])
            except CommandError:
                raise CommandError(f"{address + i:02X}H への書き込みが拒否されました")
        return echo


def parse_int(text, minimum, maximum, name):
    text = str(text).strip()
    try:
        value = int(text, 0)
    except ValueError:
        try:
            value = int(text, 10)
        except ValueError:
            raise ValueError(f"{name}: 数値を入力してください")
    if not minimum <= value <= maximum:
        raise ValueError(f"{name}: {minimum}～{maximum} の範囲で入力してください")
    return value


def on_off_to_bit(label):
    return 1 if label == on_off_values[0] else 0


def bit_to_on_off(bit):
    return on_off_values[0] if bit else on_off_values[1]


def power_table(model, table_index):
    if model == MODEL_LEGACY:
        return POWER_LEGACY
    if model == MODEL_22L:
        return POWER_22L_B if table_index == 1 else POWER_22L_A
    return POWER_22S


def power_labels(model, table_index):
    width = 2 if model == MODEL_LEGACY else 4
    table = sorted(power_table(model, table_index), key=lambda p: (-p[1], p[0]))
    return [f"{dbm} dBm ({code:0{width}b})" for code, dbm in table]


def power_label(model, table_index, code):
    for label in power_labels(model, table_index):
        if power_code(label) == code:
            return label
    return None


def power_code(label):
    return int(label[label.index("(") + 1 : -1], 2)


def update_power_combo(window: sg.Window, values, code=None):
    model = values["model_combo"]
    table_index = power_table_values.index(values["power_table_combo"])
    if code is None:
        try:
            code = power_code(values["transmitting_power_combo"])
        except (ValueError, TypeError, AttributeError):
            code = DEFAULT_POWER_CODE
    labels = power_labels(model, table_index)
    label = power_label(model, table_index, code)
    if label is None:
        label = power_label(model, table_index, DEFAULT_POWER_CODE) or labels[0]
    disabled = not state["enabled"]
    window["transmitting_power_combo"].update(
        values=labels, value=label, disabled=disabled
    )
    window["power_table_combo"].update(disabled=disabled or model != MODEL_22L)


def update_derived(window: sg.Window, values):
    """エアデータレート、周波数、キャリアセンスタイムアウトの表示を更新する"""
    try:
        bw = bw_values.index(values["bw_combo"])
        sf = sf_values.index(values["sf_combo"])
    except ValueError:
        bw = sf = None

    rate = AIR_DATA_RATE.get((sf, bw))
    if rate is None:
        window["air_rate"].update("無効なSF/BWの組み合わせ", text_color="red")
    else:
        window["air_rate"].update(f"エアデータレート: {rate:,} bps", text_color="black")

    try:
        ch = int(str(values["channel"]).strip())
    except ValueError:
        ch = None

    if bw is None or ch is None:
        window["freq"].update("")
    elif not 0 <= ch <= CH_MAX[bw]:
        window["freq"].update(f"CH0～{CH_MAX[bw]} の範囲外", text_color="red")
    else:
        frequency = FREQ_BASE[bw] + ch * 0.2
        note = ""
        if values["model_combo"] == MODEL_22L and ch > CH_22L_TX_MAX[bw]:
            note = " (22Lは受信のみ)"
        window["freq"].update(f"Frequency: {frequency:.1f}MHz{note}", text_color="black")

    try:
        timeout = int(str(values["cs_timeout"]).strip())
    except ValueError:
        timeout = None

    if timeout is None or not 0 <= timeout <= 255:
        window["cs_timeout_text"].update("0～255 で入力")
    elif timeout == 0:
        window["cs_timeout_text"].update("タイムアウトなし")
    elif timeout == 255:
        window["cs_timeout_text"].update("5000 ms")
    else:
        window["cs_timeout_text"].update(f"{timeout + 4} ms")


def current_values(window: sg.Window):
    return {key: window[key].get() for key in VALUE_KEYS}


def set_enabled(window: sg.Window, keys, enabled):
    if "addr" in keys:
        state["enabled"] = enabled
    for key in keys:
        element = window[key]
        if isinstance(element, sg.Input):
            element.update(
                disabled=not enabled, text_color="#000000" if enabled else "#7f7f7f"
            )
        else:
            element.update(disabled=not enabled)


def load_parameters(ser: serial.Serial, window: sg.Window):
    try:
        registers, state["legacy"] = read_config_registers(ser)

        ADDH, ADDL, REG0, REG1, REG2, REG3 = registers[:6]

        if state["legacy"]:
            model = MODEL_LEGACY
            window["version"].update("取得不可 (ver.1.x)")
        else:
            VERSION = registers[8]
            model = MODEL_22L if VERSION & 0b0000_1000 else MODEL_22S
            window["version"].update(
                # 下位ニブルの最上位ビットは 22L(ハイパワー版) を示す
                f"0x{VERSION:02X} (ver.{VERSION >> 4}.{VERSION & 0b0000_0111})"
            )

        set_enabled(window, CONFIG_KEYS, True)
        window["model_combo"].update(model)

        address = (ADDH << 8) + ADDL
        window["addr"].update(address)

        # Address 02H
        UART = (REG0 & 0b1110_0000) >> 5
        window["uart_combo"].update(uart_values[UART])
        window["status_baud_combo"].update(uart_values[UART])

        SF = (REG0 & 0b0001_1100) >> 2
        BW = REG0 & 0b0000_0011
        window["bw_combo"].update(bw_values[BW] if BW < len(bw_values) else "")
        window["sf_combo"].update(sf_values[SF] if SF < len(sf_values) else "")

        # Address 03H
        SUB_PACKET_LEN = (REG1 & 0b1100_0000) >> 6
        window["sub_packet_combo"].update(sub_packet_values[SUB_PACKET_LEN])

        RSSI_AMBIENT = (REG1 & 0b0010_0000) >> 5
        window["rssi_ambient_combo"].update(bit_to_on_off(RSSI_AMBIENT))

        if state["legacy"]:
            TRANSMIT_POWER = REG1 & 0b0000_0011
        else:
            TRANSMIT_POWER = REG1 & 0b0000_1111

        # Address 04H
        CH = REG2
        window["channel"].update(CH)

        # Address 05H
        RSSI_BYTE = (REG3 & 0b1000_0000) >> 7
        window["rssi_byte_combo"].update(bit_to_on_off(RSSI_BYTE))

        TRANSMIT_METHOD = (REG3 & 0b0100_0000) >> 6
        window["transmission_method_combo"].update(
            transmission_method_values[TRANSMIT_METHOD]
        )

        POWER_TABLE = (REG3 & 0b0010_0000) >> 5 if model == MODEL_22L else 0
        window["power_table_combo"].update(power_table_values[POWER_TABLE])

        WOR_CYCLE = REG3 & 0b0000_0111
        window["wor_cycle_combo"].update(
            wor_cycle_values[WOR_CYCLE] if WOR_CYCLE < len(wor_cycle_values) else ""
        )

        # Address 06H, 07H は書き込み専用のため読み出さない
        window["encryption_key"].update("")

        if not state["legacy"]:
            # Address 09H
            EXT = registers[9]
            state["strict"] = (EXT & 0b0100_0000) >> 6
            window["uvlo_combo"].update(uvlo_values[(EXT & 0b1000_0000) >> 7])
            window["strict_combo"].update(bit_to_on_off(state["strict"]))
            window["dest_addr_combo"].update(bit_to_on_off((EXT & 0b0010_0000) >> 5))
            window["checksum_in_combo"].update(bit_to_on_off((EXT & 0b0001_0000) >> 4))
            window["checksum_out_combo"].update(bit_to_on_off((EXT & 0b0000_1000) >> 3))
            window["size_out_combo"].update(bit_to_on_off((EXT & 0b0000_0100) >> 2))
            window["aux_wait_combo"].update(aux_wait_values[EXT & 0b0000_0011])

            # Address 0AH
            window["cs_timeout"].update(registers[10])

        set_enabled(window, EXT_KEYS, not state["legacy"])

        values = current_values(window)
        update_power_combo(window, values, TRANSMIT_POWER)
        update_derived(window, values)
    except Exception as e:
        print(e)
        sg.popup("Module not responding.")
        return False
    else:
        if state["legacy"]:
            sg.popup(
                "Loading parameters succeeded.\n"
                "ver.2.0 の拡張レジスタに応答がないため ver.1.x 互換で表示しています。"
            )
        else:
            sg.popup("Loading parameters succeeded.")
        return True


def collect_parameters(values):
    """GUIの値を検証してレジスタ値に変換する"""
    errors = []
    legacy = values["model_combo"] == MODEL_LEGACY
    result = {}

    def check(func):
        try:
            return func()
        except ValueError as e:
            errors.append(str(e))
            return 0

    address = check(lambda: parse_int(values["addr"], 0, 0xFFFF, "アドレス"))

    uart = uart_values.index(values["uart_combo"])
    bw = bw_values.index(values["bw_combo"]) if values["bw_combo"] in bw_values else None
    sf = sf_values.index(values["sf_combo"]) if values["sf_combo"] in sf_values else None
    if (sf, bw) not in AIR_DATA_RATE:
        errors.append("SF/BW: 無効な組み合わせです (SF10はBW250/500、SF11はBW500のみ)")
        sf = sf or 0
        bw = bw or 0

    sub_packet_size = sub_packet_values.index(values["sub_packet_combo"])
    rssi_ambient_noise = on_off_to_bit(values["rssi_ambient_combo"])
    transmitting_power = power_code(values["transmitting_power_combo"])

    ch = check(lambda: parse_int(values["channel"], 0, CH_MAX[bw], "周波数チャネル"))

    rssi_byte = on_off_to_bit(values["rssi_byte_combo"])
    transmission_method = transmission_method_values.index(
        values["transmission_method_combo"]
    )
    power_table_select = 0
    if values["model_combo"] == MODEL_22L:
        power_table_select = power_table_values.index(values["power_table_combo"])
    wor_cycle = wor_cycle_values.index(values["wor_cycle_combo"])

    key = None
    if str(values["encryption_key"]).strip() != "":
        key = check(lambda: parse_int(values["encryption_key"], 0, 0xFFFF, "暗号化キー"))

    # Address 00H, 01H
    ADDH = address >> 8
    ADDL = address & 0b1111_1111

    # Address 02H
    REG0 = (uart << 5) | (sf << 2) | bw

    # Address 03H
    REG1 = (sub_packet_size << 6) | (rssi_ambient_noise << 5) | transmitting_power

    # Address 04H
    REG2 = ch

    # Address 05H
    REG3 = (rssi_byte << 7) | (transmission_method << 6) | wor_cycle
    REG3 = REG3 | (power_table_select << 5)

    result["basic"] = [ADDH, ADDL, REG0, REG1, REG2, REG3]
    result["key"] = None if key is None else [key >> 8, key & 0b1111_1111]
    result["frequency"] = FREQ_BASE[bw] + ch * 0.2

    if not legacy:
        # Address 09H
        EXT = uvlo_values.index(values["uvlo_combo"]) << 7
        EXT |= on_off_to_bit(values["strict_combo"]) << 6
        EXT |= on_off_to_bit(values["dest_addr_combo"]) << 5
        EXT |= on_off_to_bit(values["checksum_in_combo"]) << 4
        EXT |= on_off_to_bit(values["checksum_out_combo"]) << 3
        EXT |= on_off_to_bit(values["size_out_combo"]) << 2
        EXT |= aux_wait_values.index(values["aux_wait_combo"])

        # Address 0AH
        cs_timeout = check(
            lambda: parse_int(values["cs_timeout"], 0, 255, "キャリアセンスタイムアウト")
        )
        result["ext"] = [EXT, cs_timeout]

    return result, errors


def write_parameters(ser: serial.Serial, window: sg.Window, values):
    try:
        params, errors = collect_parameters(values)
    except (ValueError, TypeError) as e:
        sg.popup(f"未設定の項目があります。\n{e}")
        return

    if errors:
        sg.popup("入力値に誤りがあります。\n\n" + "\n".join(errors))
        return

    if "ext" in params and params["ext"][0] & 0b0100_0000 and not state["strict"]:
        answer = sg.popup_yes_no(
            "Strict Mode を有効にすると ver.1 互換コマンドが使えなくなります。\n"
            "書き込みを続けますか？"
        )
        if answer != "Yes":
            return

    step = ""
    try:
        # Address 00H～05H
        step = "00H～05H"
        echo = write_registers(ser, 0x00, params["basic"])
        if echo != params["basic"]:
            raise CommandError("書き込み後の応答が一致しません")

        # Address 06H, 07H (空欄の場合は書き込まない)
        if params["key"] is not None:
            step = "06H～07H (暗号化キー)"
            write_registers(ser, 0x06, params["key"])

        # Address 0AH, 09H (データシートの例に合わせて 1 バイトずつ書き込む)
        # 09H は CHECKSUM 必須など送受信形式を変えるため最後に書き込む
        if "ext" in params:
            for address, name in [(0x0A, "0AH (キャリアセンスタイムアウト)"), (0x09, "09H (拡張設定)")]:
                step = name
                data = [params["ext"][address - 0x09]]
                echo = write_registers(ser, address, data)
                if echo != data:
                    raise CommandError("書き込み後の応答が一致しません")
            state["strict"] = (params["ext"][0] & 0b0100_0000) >> 6
            set_framing(params["ext"][0])

        window["freq"].update(f"Frequency: {params['frequency']:.1f}MHz")
    except Exception as e:
        print(e)
        sg.popup(f"Writing parameters failed.\n\n{step} の書き込みに失敗しました。\n{e}")
    else:
        sg.popup("Writing parameters succeeded.")


def load_status(ser: serial.Serial, window: sg.Window, values):
    # ステータスレジスタは 通常モード(M0=0, M1=0) かつ Strict Mode 有効時のみ
    # FF FF C1 コマンドで読み出せる (設定モードでは読み出せない)
    # 通常モードの UART は REG0 の設定値で通信する
    command = [0xFF, 0xFF, 0xC1, 0xA0, 0x05]
    data = None
    try:
        ser.baudrate = int(values["status_baud_combo"])
        # Get 前は CHECKSUM 必須設定が不明なため、なし/ありの両方を試す
        for checksum_in in (state["checksum_in"], 1 - state["checksum_in"]):
            state["checksum_in"] = checksum_in
            try:
                response = send_command(ser, command, len(command) + 5)
                data = extract_data(response, command, 5)
                break
            except CommandError as e:
                print(e)
    finally:
        ser.baudrate = 9600

    if data is None:
        sg.popup(
            "Reading status failed.\n\n"
            "・通常モード(M0=0, M1=0) になっているか\n"
            "・Strict Mode が有効か\n"
            "・UARTレートがモジュールの設定と一致しているか\n"
            "を確認してください。"
        )
        return

    RECV_STATE, SEND_STATE, COLLISION, NOISE_RSSI, LAST_RSSI = data

    def flag(value, bit, on_text):
        return on_text if value & (1 << bit) else "正常"

    window["recv_overflow"].update(flag(RECV_STATE, 0, "オーバーフロー (データ破棄)"))
    window["send_cs_timeout"].update(flag(SEND_STATE, 2, "タイムアウト発生"))
    window["send_overflow"].update(flag(SEND_STATE, 1, "オーバーフロー (データ破棄)"))
    window["send_checksum"].update(flag(SEND_STATE, 0, "CHECKSUMエラー"))
    window["collision"].update(f"{COLLISION} 回")
    window["noise_rssi"].update(f"{NOISE_RSSI - 256} dBm (0x{NOISE_RSSI:02X})")
    window["last_rssi"].update(f"{LAST_RSSI - 256} dBm (0x{LAST_RSSI:02X})")
    sg.popup("Reading status succeeded.")


def load_user_id(ser: serial.Serial, window: sg.Window):
    try:
        data = read_registers_each(ser, 0xE0, 0x10)
    except Exception as e:
        print(e)
        sg.popup(
            "Reading user ID failed.\n\n"
            "E0H～EFH の読み込みが拒否されました。\n"
            "ファームウェアがユーザーID領域に対応していない可能性があります。"
        )
        return

    # E0H が最下位バイトのため、EFH から順に表示する
    window["uid"].update("".join(format(b, "02X") for b in reversed(data)))


def write_user_id(ser: serial.Serial, window: sg.Window, values):
    text = str(values["uid"]).strip().replace(" ", "")
    if text.lower().startswith("0x"):
        text = text[2:]
    try:
        if len(text) > 32:
            raise ValueError
        value = int(text or "0", 16)
    except ValueError:
        sg.popup("ユーザーID: 16進数で最大32桁を入力してください")
        return

    data = list(value.to_bytes(16, "little"))
    try:
        echo = write_registers_each(ser, 0xE0, data)
        if echo != data:
            raise CommandError("書き込み後の応答が一致しません")
    except Exception as e:
        print(e)
        sg.popup(f"Writing user ID failed.\n\n{e}")
    else:
        window["uid"].update(format(value, "032X"))
        sg.popup("Writing user ID succeeded.")


def reset_parameters(window: sg.Window, values):
    # データシート記載のデフォルト値
    window["addr"].update(0)
    window["uart_combo"].update(uart_values[3])
    window["bw_combo"].update(bw_values[0])
    window["sf_combo"].update(sf_values[4])
    window["sub_packet_combo"].update(sub_packet_values[0])
    window["rssi_ambient_combo"].update(on_off_values[1])
    window["power_table_combo"].update(power_table_values[0])
    window["channel"].update(0)
    window["rssi_byte_combo"].update(on_off_values[1])
    window["transmission_method_combo"].update(transmission_method_values[1])
    window["wor_cycle_combo"].update(wor_cycle_values[3])
    window["encryption_key"].update(0)

    if values["model_combo"] != MODEL_LEGACY:
        window["uvlo_combo"].update(uvlo_values[0])
        window["strict_combo"].update(on_off_values[1])
        window["dest_addr_combo"].update(on_off_values[1])
        window["checksum_in_combo"].update(on_off_values[1])
        window["checksum_out_combo"].update(on_off_values[1])
        window["size_out_combo"].update(on_off_values[1])
        window["aux_wait_combo"].update(aux_wait_values[0])
        window["cs_timeout"].update(0)

    values = current_values(window)
    update_power_combo(window, values, DEFAULT_POWER_CODE)
    update_derived(window, values)

    sg.popup("Restore default parameters.")


def reload_port(window: sg.Window, values):
    serial_port_values = serial_ports()

    if len(serial_port_values) == 0:
        serial_port_values = [""]

    window["serial_port_combo"].update(
        values=serial_port_values, value=serial_port_values[0]
    )


def label(text):
    return sg.Text(text, size=18, justification="right")


def combo(values, key, size=8, events=False):
    return sg.Combo(
        values,
        size=size,
        readonly=True,
        disabled=True,
        key=key,
        enable_events=events,
    )


def create_layout():
    serial_port_values = serial_ports()

    if len(serial_port_values) == 0:
        serial_port_values = [""]

    basic_tab = [
        [
            label("モデル"),
            combo(model_values, "model_combo", size=18, events=True),
        ],
        [label("バージョン"), sg.Text("", size=24, key="version")],
        [
            label("アドレス"),
            sg.Input(size=8, disabled=True, key="addr"),
            sg.Text("0～65535"),
        ],
        [label("UART"), combo(uart_values, "uart_combo"), sg.Text("bps")],
        [label("SF"), combo(sf_values, "sf_combo", events=True)],
        [
            label("BW"),
            combo(bw_values, "bw_combo", events=True),
            sg.Text("kHz"),
        ],
        [label(""), sg.Text("", size=30, key="air_rate")],
        [
            label("サブパケット長"),
            combo(sub_packet_values, "sub_packet_combo"),
            sg.Text("bytes"),
        ],
        [label("RSSI環境ノイズ"), combo(on_off_values, "rssi_ambient_combo")],
        [
            label("送信電力テーブル"),
            combo(power_table_values, "power_table_combo", events=True),
            sg.Text("(22Lのみ)"),
        ],
        [
            label("送信電力"),
            combo(power_labels(MODEL_22S, 0), "transmitting_power_combo", size=14),
        ],
        [
            label("周波数チャネル"),
            sg.Input(size=8, disabled=True, key="channel", enable_events=True),
            sg.Text(size=(28, 1), key="freq"),
        ],
        [label("RSSIバイト"), combo(on_off_values, "rssi_byte_combo")],
        [
            label("送信方法"),
            combo(transmission_method_values, "transmission_method_combo", size=29),
        ],
        [label("WORサイクル"), combo(wor_cycle_values, "wor_cycle_combo"), sg.Text("ms")],
        [
            label("暗号化キー"),
            sg.Input(size=8, disabled=True, key="encryption_key"),
            sg.Text("書込専用 (空欄なら変更しない)"),
        ],
    ]

    ext_tab = [
        [label("低電圧保護"), combo(uvlo_values, "uvlo_combo", size=16)],
        [label("Strict Mode"), combo(on_off_values, "strict_combo")],
        [label("受信時 宛先アドレス出力"), combo(on_off_values, "dest_addr_combo")],
        [label("UART入力 CHECKSUM必須"), combo(on_off_values, "checksum_in_combo")],
        [label("UART出力 CHECKSUM付加"), combo(on_off_values, "checksum_out_combo")],
        [label("UART出力 サイズ付加"), combo(on_off_values, "size_out_combo")],
        [
            label("受信時 AUX→UART待機"),
            combo(aux_wait_values, "aux_wait_combo", size=12),
            sg.Text("ms"),
        ],
        [
            label("キャリアセンス\nタイムアウト"),
            sg.Input(size=8, disabled=True, key="cs_timeout", enable_events=True),
            sg.Text("", size=16, key="cs_timeout_text"),
        ],
    ]

    status_tab = [
        [sg.Text("※ 通常モード(M0=0, M1=0) かつ Strict Mode 有効時のみ読み出し可能")],
        [
            label("UARTレート"),
            sg.Combo(
                uart_values,
                default_value="9600",
                size=8,
                readonly=True,
                key="status_baud_combo",
            ),
            sg.Text("bps (モジュールのUART設定値)"),
        ],
        [label("受信バッファ"), sg.Text("", size=28, key="recv_overflow")],
        [label("キャリアセンス"), sg.Text("", size=28, key="send_cs_timeout")],
        [label("送信バッファ"), sg.Text("", size=28, key="send_overflow")],
        [label("CHECKSUM"), sg.Text("", size=28, key="send_checksum")],
        [label("衝突回避回数"), sg.Text("", size=28, key="collision")],
        [label("環境ノイズRSSI"), sg.Text("", size=28, key="noise_rssi")],
        [label("最終受信RSSI"), sg.Text("", size=28, key="last_rssi")],
        [sg.Button("Read Status", size=12, key="status_button", disabled=True)],
    ]

    uid_tab = [
        [sg.Text("ユーザーID (E0H～EFH, 16バイト)")],
        [sg.Text("16進数で入力 (左がEFH、右がE0H)")],
        [sg.Input(size=36, disabled=True, key="uid")],
        [
            sg.Button("Get ID", size=10, key="uid_get_button", disabled=True),
            sg.Button("Set ID", size=10, key="uid_set_button", disabled=True),
        ],
    ]

    layout = [
        [
            sg.Text("シリアルポート", size=14, justification="right"),
            sg.Combo(
                serial_port_values,
                default_value=serial_port_values[0],
                size=8,
                readonly=True,
                key="serial_port_combo",
            ),
            sg.Button("Open", size=6, key="port"),
            sg.Button("Reload", size=6, key="reload"),
        ],
        [
            sg.TabGroup(
                [
                    [
                        sg.Tab("基本設定", basic_tab),
                        sg.Tab("拡張設定", ext_tab),
                        sg.Tab("ステータス", status_tab),
                        sg.Tab("ユーザーID", uid_tab),
                    ]
                ]
            )
        ],
        [
            sg.Button("Get", size=10, key="get_button", disabled=True),
            sg.Button("Set Param", size=10, key="set_button", disabled=True),
            sg.Button("Param Reset", size=10, key="reset_button", disabled=True),
        ],
    ]

    return layout


def main():
    layout = create_layout()
    window = sg.Window("E220-900T22S/22L(JP) Config", layout, finalize=True)
    set_enabled(window, CONFIG_KEYS + EXT_KEYS, False)

    while True:
        event, values = window.read()

        if event == sg.WIN_CLOSED:
            if window["port"].get_text() == "Close":
                serial_close(ser)
            break
        elif event == "port" and window["port"].get_text() == "Open":
            ser = serial_open(window["serial_port_combo"].get())
            if ser == None:
                sg.popup("Failed to open serial port!!!")
                continue
            window["get_button"].update(disabled=False)
            window["status_button"].update(disabled=False)
            window["serial_port_combo"].update(disabled=True)
            window["reload"].update(disabled=True)
            window["port"].update("Close")
        elif event == "port" and window["port"].get_text() == "Close":
            serial_close(ser)
            window["get_button"].update(disabled=True)
            window["status_button"].update(disabled=True)
            window["set_button"].update(disabled=True)
            window["reset_button"].update(disabled=True)
            window["serial_port_combo"].update(disabled=False)
            window["reload"].update(disabled=False)
            set_enabled(window, CONFIG_KEYS + EXT_KEYS, False)
            window["port"].update("Open")
        elif event == "reload":
            reload_port(window, values)
        elif event == "get_button":
            if load_parameters(ser, window):
                window["set_button"].update(disabled=False)
                window["reset_button"].update(disabled=False)
        elif event == "set_button":
            write_parameters(ser, window, values)
        elif event == "reset_button":
            reset_parameters(window, values)
        elif event == "model_combo":
            set_enabled(window, EXT_KEYS, values["model_combo"] != MODEL_LEGACY)
            update_power_combo(window, values)
            update_derived(window, values)
        elif event == "power_table_combo":
            update_power_combo(window, values)
        elif event in ("sf_combo", "bw_combo", "channel", "cs_timeout"):
            update_derived(window, values)
        elif event == "status_button":
            load_status(ser, window, values)
        elif event == "uid_get_button":
            load_user_id(ser, window)
        elif event == "uid_set_button":
            write_user_id(ser, window, values)


if __name__ == "__main__":
    main()
