package com.example.s220

import android.os.Handler
import android.os.Looper
import com.example.s220.model.LogEntry

/**
 * バックグラウンド (RxService) と画面 (MainActivity) の橋渡し。
 *
 * rx_app.py の ConsolePresenter に相当する通知先。
 * どのスレッドから post しても、リスナーへの通知と内部状態の更新はメインスレッドで行う。
 * Activity が破棄されていても直近 [MAX_ENTRIES] 行は保持しているので、再表示時に復元できる。
 */
object RxHub {
    const val MAX_ENTRIES = 2000

    /** 画面上部に出すステータス */
    data class Status(
        val running: Boolean = false,
        val usbConnected: Boolean = false,
        val usbText: String = "USB: 未接続",
        val gpsOk: Boolean = false,
        val gpsText: String = "GPS: ---",
        val total: Int = 0,
        val lost: Int = 0,
        val rssiLast: Int? = null,
        val rssiMin: Int? = null,
        val rssiAvg: Double? = null,
        val rssiMax: Int? = null,
        val gpxPoints: Int = 0,
        val logDir: String? = null,
        val soundOn: Boolean = true,
    )

    interface Listener {
        fun onEntry(entry: LogEntry, removedFromHead: Int)
        fun onStatus(status: Status)
        fun onCleared()
    }

    private val main = Handler(Looper.getMainLooper())
    private val entries = ArrayDeque<LogEntry>()
    private val listeners = LinkedHashSet<Listener>()

    @Volatile
    var status = Status()
        private set

    /** メインスレッドからのみ参照すること */
    fun snapshot(): List<LogEntry> = entries.toList()

    fun addListener(l: Listener) {
        listeners.add(l)
    }

    fun removeListener(l: Listener) {
        listeners.remove(l)
    }

    fun postEntry(e: LogEntry) = runOnMain {
        entries.addLast(e)
        var removed = 0
        while (entries.size > MAX_ENTRIES) {
            entries.removeFirst()
            removed++
        }
        listeners.toList().forEach { it.onEntry(e, removed) }
    }

    fun updateStatus(transform: (Status) -> Status) = runOnMain {
        status = transform(status)
        val s = status
        listeners.toList().forEach { it.onStatus(s) }
    }

    fun clear() = runOnMain {
        entries.clear()
        listeners.toList().forEach { it.onCleared() }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }
}
