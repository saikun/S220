package com.example.s220.core

/*
 * RxCore.kt - rx_app.py の [処理層] を Kotlin へ移植したもの。
 *
 * I/O（USB）にも UI（Android View）にも依存しない純粋ロジックのみを置く。
 * そのため JVM 単体テスト（src/test）でそのまま検証できる。
 *
 *   バイト列 -> PacketAssembler(途切れでパケット化) -> splitRssi -> LineFramer -> parseMessage
 *                                                                   -> RxStatistics
 */

/** 定数（rx_app.py の定数と同値） */
object RxConfig {
    /** E220 デフォルト UART 速度 (8N1) */
    const val DEFAULT_BAUDRATE = 9600

    /** 改行が来なくても、この時間データが途切れたらフレームを確定させる [ms] */
    const val DEFAULT_IDLE_FLUSH_MS = 500L

    /** 改行が来ないまま溜まり続けた場合の安全上限 [byte] */
    const val MAX_FRAME_BYTES = 1024

    /**
     * RSSI モード時、この時間 [ms] UART 出力が途切れたら 1 無線パケットの終わりとみなす。
     * E220 サンプルコード (operation_code_win/receive.py) の PACKET_GAP_SEC = 0.200 (200ms) に準拠。
     * ペイロード出力と RSSI バイト出力の間のディレイを確実に吸収する。
     */
    const val DEFAULT_PACKET_GAP_MS = 200L

    const val LINE_TERMINATOR: Byte = '\n'.code.toByte()
    const val CR: Byte = '\r'.code.toByte()
}

/** 単調増加クロック [ms]。テスト時は差し替える。 */
typealias Clock = () -> Long

val monotonicClock: Clock = { System.nanoTime() / 1_000_000L }

/** 受信した1フレームを表すデータ構造。 */
class RxMessage(
    /** 受信時刻（壁時計, epoch ms） */
    val receivedAtMillis: Long,
    val raw: ByteArray,
    val text: String,
    val fields: Map<String, String> = emptyMap(),
    val seq: Int? = null,
    /** 直前フレームとの間で欠落したと推定される件数 */
    val lostBefore: Int = 0,
    /** 改行で終端されたか（false: アイドルタイムアウトで確定） */
    val terminated: Boolean = true,
    /** このフレームを完成させた無線パケットの RSSI [dBm]（RSSI モード時のみ） */
    val rssiDbm: Int? = null,
)

/** 伸長可能なバイトバッファ（Python の bytearray 相当の最小実装）。 */
internal class ByteBuf(initial: Int = 256) {
    private var buf = ByteArray(initial)
    var size = 0
        private set

    fun isEmpty() = size == 0
    fun isNotEmpty() = size != 0

    operator fun get(i: Int): Byte = buf[i]

    fun append(data: ByteArray, off: Int = 0, len: Int = data.size) {
        if (size + len > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, size + len))
        System.arraycopy(data, off, buf, size, len)
        size += len
    }

    fun indexOf(b: Byte): Int {
        for (i in 0 until size) if (buf[i] == b) return i
        return -1
    }

    fun copy(from: Int = 0, to: Int = size): ByteArray = buf.copyOfRange(from, to)

    /** 先頭から n バイトを削除する */
    fun dropFirst(n: Int) {
        System.arraycopy(buf, n, buf, 0, size - n)
        size -= n
    }

    fun clear() {
        size = 0
    }
}

/**
 * UART 出力の途切れを境界として、無線パケット単位にまとめる。
 *
 * feed() でデータを追加し、定期的に flushIfIdle() を呼ぶと、
 * gapMs 以上新しいデータが来ていなければ 1 パケットとして返す。
 */
class PacketAssembler(
    private val gapMs: Long = RxConfig.DEFAULT_PACKET_GAP_MS,
    private val clock: Clock = monotonicClock,
) {
    private val buf = ByteBuf()
    private var lastRx = 0L

    fun feed(data: ByteArray, len: Int = data.size) {
        buf.append(data, 0, len)
        lastRx = clock()
    }

    fun flushIfIdle(force: Boolean = false): ByteArray? {
        if (buf.isNotEmpty() && (force || clock() - lastRx >= gapMs)) {
            val packet = buf.copy()
            buf.clear()
            return packet
        }
        return null
    }
}

/**
 * 無線パケット末尾の RSSI バイトを分離する。戻り値: (ペイロード, RSSI[dBm])。
 *
 * E220 仕様:
 *   「有効時、受信データを出力した後、続いてRSSIバイトを出力します。dBm = RSSIバイト - 256」
 *
 * 送信側（tx_app.py等）がテキスト（末尾 '\n' または '\r\n'）を送っている場合:
 *   テキスト + '\n' + RSSIバイト
 *
 * 判定ロジック:
 * 1. 末尾バイトが '\n' (0x0A) または '\r' (0x0D) の場合:
 *    末尾は改行コードそのものであり、RSSIバイトは付加されていない。
 *    （10 - 256 = -246 dBm に化けるのを確実に防止）
 *    → RSSI は null とし、パケット全体をペイロードとして返す。
 *
 * 2. 末尾バイト - 256 が妥当な LoRa RSSI 範囲 (-150 〜 0 dBm) であれば RSSI とみなす。
 *    → 末尾1バイトを取り出して RSSI (byte - 256) を計算し、残りをペイロードとする。
 *    改行の直後でも値域は必ず確認する（末尾 0x00 を -256 dBm と誤認しないため）。
 *
 * 3. それ以外は RSSI なしとして、パケット全体をペイロードとして返す。
 */
fun splitRssi(packet: ByteArray): Pair<ByteArray, Int?> {
    if (packet.isEmpty()) return ByteArray(0) to null

    val lastByte = packet.last().toInt() and 0xFF

    // 1. 末尾が改行コードそのものなら RSSI バイトは付いていない
    if (lastByte == 0x0A || lastByte == 0x0D) {
        return packet to null
    }

    // 2. 末尾が妥当な RSSI 範囲 (-150 〜 0 dBm, byte値 106〜255) かどうか
    val candidateRssi = lastByte - 256
    if (candidateRssi in RSSI_VALID_DBM) {
        return packet.copyOfRange(0, packet.size - 1) to candidateRssi
    }

    // 3. RSSI とみなせない場合はそのまま返す
    return packet to null
}

/** RSSI バイトとして受け付ける値域 [dBm]（LoRa の実用的な受信感度の範囲） */
private val RSSI_VALID_DBM = -150..0

/**
 * 断片化したバイト列を改行区切りのフレームへ組み立てる。
 *
 * LoRa は空中でサブパケットに分割されたり、UART の読み出し単位が任意だったりするため、
 * 1回の read() で 1メッセージが揃う保証はない。
 */
class LineFramer(
    private val idleFlushMs: Long = RxConfig.DEFAULT_IDLE_FLUSH_MS,
    private val maxFrameBytes: Int = RxConfig.MAX_FRAME_BYTES,
    private val clock: Clock = monotonicClock,
) {
    private val buf = ByteBuf()
    private var lastRx = 0L

    /** 受信データを追加し、確定したフレーム（改行除去済み）のリストを返す。 */
    fun feed(data: ByteArray, len: Int = data.size): List<ByteArray> {
        buf.append(data, 0, len)
        lastRx = clock()
        val frames = ArrayList<ByteArray>()
        while (true) {
            val idx = buf.indexOf(RxConfig.LINE_TERMINATOR)
            if (idx < 0) break
            var end = idx
            while (end > 0 && buf[end - 1] == RxConfig.CR) end-- // rstrip(b"\r")
            frames.add(buf.copy(0, end))
            buf.dropFirst(idx + 1)
        }
        if (buf.size >= maxFrameBytes) {
            frames.add(buf.copy())
            buf.clear()
        }
        return frames
    }

    /** 一定時間データが途切れていれば（または force=true なら）、未終端のバッファをフレームとして返す。 */
    fun flushIfIdle(force: Boolean = false): ByteArray? {
        if (buf.isNotEmpty() && (force || clock() - lastRx >= idleFlushMs)) {
            val frame = buf.copy()
            buf.clear()
            return frame
        }
        return null
    }
}

/**
 * 'KEY=VALUE,KEY=VALUE,...' 形式（tx_app.py の送信形式）をマップに変換する。
 *
 * MSG は最後のフィールドで、本文中に ',' を含んでもよい。
 * 形式に合わない場合は空のマップを返す（任意のバイト列も受信できるようにするため）。
 */
fun parseMessage(frame: ByteArray): Map<String, String> {
    val text = String(frame, Charsets.UTF_8)
    val result = LinkedHashMap<String, String>()
    var rest = text
    while (rest.isNotEmpty()) {
        if (rest.startsWith("MSG=")) {
            result["MSG"] = rest.substring(4)
            break
        }
        val comma = rest.indexOf(',')
        val keyVal = if (comma >= 0) rest.substring(0, comma) else rest
        rest = if (comma >= 0) rest.substring(comma + 1) else ""
        val eq = keyVal.indexOf('=')
        if (eq <= 0) return emptyMap() // '=' が無い or KEY が空
        result[keyVal.substring(0, eq)] = keyVal.substring(eq + 1)
        if (comma < 0) break
    }
    return result
}

/** 受信件数、SEQ の連番抜け（パケットロス推定）、RSSI を集計する。 */
class RxStatistics {
    var totalFrames = 0; private set
    var totalBytes = 0L; private set
    var lost = 0; private set
    var rssiCount = 0; private set
    var rssiSum = 0L; private set
    var rssiMin: Int? = null; private set
    var rssiMax: Int? = null; private set
    var rssiLast: Int? = null; private set
    private var lastSeq: Int? = null

    /** 統計を更新し、今回推定された欠落件数を返す。 */
    fun update(seq: Int?, nbytes: Int): Int {
        totalFrames += 1
        totalBytes += nbytes
        var lostNow = 0
        if (seq != null) {
            val last = lastSeq
            if (last != null && seq > last + 1) {
                lostNow = seq - last - 1
                lost += lostNow
            }
            // seq <= lastSeq の場合は送信側の再起動とみなし、基準をリセット
            lastSeq = seq
        }
        return lostNow
    }

    /** 無線パケット 1 つ分の RSSI を集計する。 */
    fun updateRssi(rssiDbm: Int) {
        rssiCount += 1
        rssiSum += rssiDbm
        rssiLast = rssiDbm
        rssiMin = rssiMin?.let { minOf(it, rssiDbm) } ?: rssiDbm
        rssiMax = rssiMax?.let { maxOf(it, rssiDbm) } ?: rssiDbm
    }

    val rssiAvg: Double?
        get() = if (rssiCount > 0) rssiSum.toDouble() / rssiCount else null
}

/**
 * I/O層から受け取ったバイト列を RxMessage に変換し、上位層へ通知する。
 *
 * rssiEnabled=true の場合:  バイト列 -> PacketAssembler -> splitRssi -> LineFramer
 * rssiEnabled=false の場合: バイト列 -> LineFramer
 * いずれの場合も handleIdle() を定期的に呼ぶ必要がある。
 *
 * onMessage は handleBytes / handleIdle を呼んだスレッド上で呼ばれる。
 */
class MessageProcessor(
    private val onMessage: (RxMessage) -> Unit,
    private val onRaw: ((ByteArray, Int) -> Unit)? = null,
    idleFlushMs: Long = RxConfig.DEFAULT_IDLE_FLUSH_MS,
    private val rssiEnabled: Boolean = false,
    packetGapMs: Long = RxConfig.DEFAULT_PACKET_GAP_MS,
    clock: Clock = monotonicClock,
    private val wallClock: () -> Long = { System.currentTimeMillis() },
) {
    private val framer = LineFramer(idleFlushMs = idleFlushMs, clock = clock)
    private val packets = if (rssiEnabled) PacketAssembler(packetGapMs, clock) else null
    private var lastRssi: Int? = null
    private val lock = Any()
    val stats = RxStatistics()

    // --- SerialReceiver から呼ばれるコールバック ---------------------------
    fun handleBytes(data: ByteArray, len: Int = data.size) {
        onRaw?.invoke(data, len)
        if (packets != null) {
            // RSSI モード: パケットが完成する（途切れる）まで溜める
            synchronized(lock) { packets.feed(data, len) }
            return
        }
        val frames = synchronized(lock) { framer.feed(data, len) }
        for (f in frames) emit(f, terminated = true)
    }

    fun handleIdle(force: Boolean = false) {
        if (packets != null) {
            val frames = synchronized(lock) {
                val packet = packets.flushIfIdle(force)
                if (packet != null) {
                    val (payload, rssi) = splitRssi(packet)
                    // RSSI を分離できなかったパケットに、前回パケットの RSSI を付けないようにする
                    lastRssi = rssi
                    if (rssi != null) stats.updateRssi(rssi)
                    framer.feed(payload)
                } else {
                    emptyList()
                }
            }
            for (f in frames) emit(f, terminated = true)
        }
        val frame = synchronized(lock) { framer.flushIfIdle(force) }
        if (frame != null) emit(frame, terminated = false)
    }

    // --- 内部処理 -----------------------------------------------------------
    private fun emit(frame: ByteArray, terminated: Boolean) {
        if (frame.isEmpty()) return
        val fields = parseMessage(frame)
        val seq = fields["SEQ"]?.toIntOrNull()
        val lost = synchronized(lock) { stats.update(seq, frame.size) }
        val msg = RxMessage(
            receivedAtMillis = wallClock(),
            raw = frame,
            text = String(frame, Charsets.UTF_8),
            fields = fields,
            seq = seq,
            lostBefore = lost,
            terminated = terminated,
            rssiDbm = if (rssiEnabled) lastRssi else null,
        )
        onMessage(msg)
    }
}
