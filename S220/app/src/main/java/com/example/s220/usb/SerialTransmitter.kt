package com.example.s220.usb

import android.util.Log
import com.example.s220.core.PeriodicSchedule
import com.example.s220.core.TxConfig
import com.example.s220.core.buildFixedHeader
import com.example.s220.core.buildPayload
import com.hoho.android.usbserial.driver.UsbSerialPort

/**
 * tx_app.py の LoRaTransmitter + run_periodic を移植したもの。
 *
 * 別スレッドで一定周期ごとにペイロードを USB シリアルへ書き込む。
 * ポートの開閉は呼び出し側（LoggerService）が行い、ここでは [portProvider] で現在のポートを借りるだけ。
 * ポートが無い（USB 未接続）周期は送信せず、SEQ も進めない。
 *
 * - 書き込み成功で onSent(seq, payload, timeMillis)（payload は固定送信ヘッダを含まない）
 * - 書き込み失敗で onError(port, e)（このスレッド上で呼ばれる）
 */
class SerialTransmitter(
    intervalSec: Int,
    private val portProvider: () -> UsbSerialPort?,
    private val onSent: (seq: Int, payload: ByteArray, timeMillis: Long) -> Unit,
    private val onError: (UsbSerialPort, Exception) -> Unit,
    private val message: String = TxConfig.MESSAGE,
    fixedTarget: Pair<Int, Int>? = if (TxConfig.FIXED_MODE) TxConfig.ADDRESS to TxConfig.CHANNEL else null,
) {
    private val header = fixedTarget?.let { buildFixedHeader(it.first, it.second) } ?: ByteArray(0)
    private val lock = Object()
    private val schedule = PeriodicSchedule(intervalSec * 1000L)

    @Volatile
    private var running = false
    private var thread: Thread? = null

    /** 送信に成功した件数（= 最後に送った SEQ） */
    @Volatile
    var sentCount = 0
        private set

    fun start() {
        if (running) return
        running = true
        thread = Thread(::run, "SerialTransmitter").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        synchronized(lock) { lock.notifyAll() }
        val t = thread ?: return
        if (t !== Thread.currentThread()) {
            try {
                t.join(TxConfig.WRITE_TIMEOUT_MS + 500L)
            } catch (_: InterruptedException) {
            }
        }
        thread = null
    }

    /** 送信間隔を変更する（すぐに 1 回送信し、そこから新しい間隔で数え直す） */
    fun setInterval(sec: Int) {
        synchronized(lock) {
            schedule.reset(sec * 1000L)
            lock.notifyAll()
        }
    }

    private fun run() {
        while (running) {
            sendOnce()
            synchronized(lock) {
                // 周期のずれ（ドリフト）が蓄積しないよう、絶対時刻基準で待つ
                var wait = schedule.advance()
                while (running && wait > 0) {
                    try {
                        lock.wait(wait)
                    } catch (_: InterruptedException) {
                        return
                    }
                    wait = schedule.remaining()
                }
            }
        }
    }

    private fun sendOnce() {
        val p = portProvider() ?: return
        val seq = sentCount + 1
        val now = System.currentTimeMillis()
        val payload = buildPayload(seq, message, now)
        try {
            p.write(header + payload, TxConfig.WRITE_TIMEOUT_MS)
        } catch (e: Exception) { // USB 抜け・書き込みタイムアウトなど
            if (running) {
                Log.w(TAG, "serial write error", e)
                onError(p, e)
            }
            return
        }
        sentCount = seq
        onSent(seq, payload, now)
    }

    companion object {
        private const val TAG = "SerialTransmitter"
    }
}
