package com.example.s220.usb

import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialPort
import java.io.IOException

/**
 * rx_app.py の [I/O層] SerialReceiver を移植したもの。
 *
 * 別スレッドで USB シリアルポートを監視し、受信バイト列をコールバックへ通知する。
 * 解析・表示は一切行わない。
 *
 * - データが届いたら onBytes(buf, len)
 * - readTimeoutMs の間データが無ければ onIdle()（パケット境界・改行なしフレームの確定に使う）
 * - USB 抜けなどで読めなくなったら onError(e)（このスレッド上で呼ばれる）
 */
class SerialReceiver(
    private val port: UsbSerialPort,
    private val onBytes: (ByteArray, Int) -> Unit,
    private val onIdle: () -> Unit,
    private val onError: (Exception) -> Unit,
    private val readTimeoutMs: Int = 50,
) {
    @Volatile
    private var running = false
    private var thread: Thread? = null

    val isRunning: Boolean get() = thread?.isAlive == true

    fun start() {
        if (running) return
        running = true
        thread = Thread(::run, "SerialReceiver").apply {
            isDaemon = true
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    /** 受信ループを止める（ポートのクローズは呼び出し側で行う）。 */
    fun stop() {
        running = false
        val t = thread ?: return
        if (t !== Thread.currentThread()) {
            try {
                t.join(1000)
            } catch (_: InterruptedException) {
            }
        }
        thread = null
    }

    private fun run() {
        // FTDI は 64byte パケットごとに 2byte のステータスが付くため、パケット長の倍数で確保する
        val bufSize = maxOf(64, port.readEndpoint?.maxPacketSize ?: 64) * 16
        val buf = ByteArray(bufSize)
        try {
            while (running) {
                // 1byte 以上届くまで最大 readTimeoutMs 待ち、届いたら溜まっている分をまとめて読む
                val n = port.read(buf, readTimeoutMs)
                if (!running) break
                if (n > 0) {
                    onBytes(buf, n)
                } else {
                    onIdle()
                }
            }
        } catch (e: IOException) { // USB 抜けなど
            if (running) {
                Log.w(TAG, "serial read error", e)
                running = false
                onError(e)
            }
        } catch (e: Exception) {
            if (running) {
                Log.e(TAG, "unexpected error", e)
                running = false
                onError(e)
            }
        }
    }

    companion object {
        private const val TAG = "SerialReceiver"
    }
}
