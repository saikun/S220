package com.example.s220.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * TxCore.kt - tx_app.py の [コア層]（build_payload / build_fixed_header）を Kotlin へ移植したもの。
 *
 * I/O（USB）にも UI にも依存しない純粋ロジックのみを置く（JVM 単体テストで検証できる）。
 */

/** アプリの動作モード（起動時に選択する） */
enum class AppMode { RX, TX }

/** 送信側の定数（tx_app.py の既定値 + 普段の運用値） */
object TxConfig {
    /** 選択できる送信間隔 [秒] */
    val INTERVALS_SEC = intArrayOf(1, 2, 3, 5, 13, 34, 55)

    /** 既定の送信間隔 [秒] */
    const val DEFAULT_INTERVAL_SEC = 3

    /** 固定送信モードで送る（python tx_app.py COMx -f --address 0 --channel 0 相当） */
    const val FIXED_MODE = true
    const val ADDRESS = 0
    const val CHANNEL = 0

    /** 周期送信で送る文字列（tx_app.py の --message 既定値） */
    const val MESSAGE = "TEST"

    /** UART 書き込みタイムアウト [ms]（tx_app.py の write_timeout = 2.0 秒） */
    const val WRITE_TIMEOUT_MS = 2000

    /**
     * 送信モードで測位する間隔 [ms]（送信側は基本的に移動しないため疎でよい）。
     * 電池消費を抑えるため、測位はこの間隔ごとに短時間だけ行い、それ以外は GPS を止める。
     */
    const val TRACK_INTERVAL_MS = 5 * 60 * 1000L

    /** 1 回の測位の打ち切り時間 [ms]。それまでに得た最良の位置を記録する（屋内で GPS が取れない場合など） */
    const val FIX_TIMEOUT_MS = 60 * 1000L

    /** この精度 [m] 以内の位置が得られたら、打ち切りを待たずに測位を終える */
    const val FIX_GOOD_ACCURACY_M = 20f

    /** 記録してよい位置の古さの上限 [秒]（測位開始時に返る前回の古い位置 getLastKnownLocation を記録しない） */
    const val TRACK_MAX_FIX_AGE_SEC = 30.0
}

/**
 * 送信用ペイロードを生成する（tx_app.py の build_payload と同じ形式）。
 *
 *   SEQ=000001,TS=2026-10-15T00:11:00.123,MSG=<message>\n
 *
 * 受信側では SEQ の連番抜けからパケットロスを、TS から遅延の目安を確認できる。
 */
fun buildPayload(seq: Int, message: String, timeMillis: Long, withTimestamp: Boolean = true): ByteArray {
    val fields = ArrayList<String>(3)
    fields.add(String.format(Locale.US, "SEQ=%06d", seq))
    if (withTimestamp) {
        // datetime.now().isoformat(timespec="milliseconds") 相当（ローカル時刻・タイムゾーンなし）
        val ts = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US).format(Date(timeMillis))
        fields.add("TS=$ts")
    }
    fields.add("MSG=$message")
    return (fields.joinToString(",") + "\n").toByteArray(Charsets.UTF_8)
}

/** 固定送信モード用の先頭 3byte（宛先アドレス H, L, チャンネル）を生成する。 */
fun buildFixedHeader(address: Int, channel: Int): ByteArray {
    require(address in 0..0xFFFF) { "address は 0～65535 の範囲で指定してください: $address" }
    require(channel in 0..0xFF) { "channel は 0～255 の範囲で指定してください: $channel" }
    return byteArrayOf((address shr 8).toByte(), (address and 0xFF).toByte(), channel.toByte())
}

/**
 * 周期送信のスケジュール（tx_app.py の run_periodic と同じく、絶対時刻基準でドリフトを溜めない）。
 *
 * 送信のたびに [advance] を呼ぶと、次の送信までの待ち時間 [ms] を返す。
 * 処理が間隔より遅れた場合は基準を現在時刻に取り直し、遅れ分をまとめて送り直さない。
 */
class PeriodicSchedule(
    intervalMs: Long,
    private val clock: Clock = monotonicClock,
) {
    var intervalMs: Long = intervalMs
        private set
    private var next = clock()

    /** 送信 1 回分を消化し、次回までの待ち時間 [ms] を返す。 */
    fun advance(): Long {
        next += intervalMs
        val wait = next - clock()
        if (wait <= 0) {
            next = clock()
            return 0
        }
        return wait
    }

    /** 次回までの残り時間 [ms]（負にならない） */
    fun remaining(): Long = maxOf(0L, next - clock())

    /** 間隔を変更し、今すぐ送信する状態にする */
    fun reset(newIntervalMs: Long = intervalMs) {
        intervalMs = newIntervalMs
        next = clock()
    }
}

/** 測位中に得られた位置の候補（[fix] の型は呼び出し側が決める。Android 非依存にするため） */
class FixCandidate<T>(val fix: T, val ageSec: Double, val accuracyM: Float?)

/** [FixBurstScheduler.onTick] の結果 */
sealed class FixDecision<out T> {
    /** 測位していない（次の測位時刻待ち） */
    object Idle : FixDecision<Nothing>()

    /** 測位を開始せよ（位置の取得を開始する） */
    object Start : FixDecision<Nothing>()

    /** 測位中（まだ十分な位置が無い） */
    object Measuring : FixDecision<Nothing>()

    /** 測位を終えよ（位置の取得を止める）。[fix] = 記録する位置、取れなければ null */
    class Done<T>(val fix: T?) : FixDecision<T>()
}

/**
 * 送信モードの間欠測位スケジュール。
 *
 * [periodMs] ごとに測位を開始し、[goodAccuracyM] 以内の位置が得られた時点、
 * または [timeoutMs] 経過した時点で終了して 1 点だけ選ぶ（それまでの最良精度の位置）。
 * 起動直後は即座に 1 回目の測位を始める。周期は測位の開始時刻基準。
 *
 * 1 秒程度の周期で [onTick] を呼び、結果に従って位置の取得を開始・停止する。
 */
class FixBurstScheduler<T>(
    private val periodMs: Long = TxConfig.TRACK_INTERVAL_MS,
    private val timeoutMs: Long = TxConfig.FIX_TIMEOUT_MS,
    private val goodAccuracyM: Float = TxConfig.FIX_GOOD_ACCURACY_M,
    private val maxAgeSec: Double = TxConfig.TRACK_MAX_FIX_AGE_SEC,
    private val clock: Clock = monotonicClock,
) {
    private var burstStart: Long? = null
    private var nextStart = clock()
    private var best: T? = null
    private var bestAcc = Float.MAX_VALUE

    val isMeasuring: Boolean get() = burstStart != null

    /** 次の測位開始までの残り時間 [ms]（測位中は 0） */
    fun msUntilNext(): Long = if (isMeasuring) 0L else maxOf(0L, nextStart - clock())

    /** [candidate] = 現在の最新位置（無ければ null） */
    fun onTick(candidate: FixCandidate<T>?): FixDecision<T> {
        val now = clock()
        val start = burstStart
        if (start == null) {
            if (now < nextStart) return FixDecision.Idle
            burstStart = now
            nextStart = now + periodMs
            best = null
            bestAcc = Float.MAX_VALUE
            return FixDecision.Start
        }
        if (candidate != null && candidate.ageSec <= maxAgeSec) {
            // 精度不明の位置は最も悪いものとして扱う（他に候補が無ければ採用）
            val acc = candidate.accuracyM ?: Float.MAX_VALUE
            if (best == null || acc < bestAcc) {
                best = candidate.fix
                bestAcc = acc
            }
            if (acc <= goodAccuracyM) return finish()
        }
        if (now - start >= timeoutMs) return finish()
        return FixDecision.Measuring
    }

    private fun finish(): FixDecision.Done<T> {
        val result = FixDecision.Done(best)
        burstStart = null
        best = null
        return result
    }
}
