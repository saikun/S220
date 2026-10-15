package com.example.s220.log

import com.example.s220.model.EntryKind
import com.example.s220.model.Fmt
import com.example.s220.model.LogEntry
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 画面に表示している内容そのものを CSV で保存する。
 *
 * 列: ローカル時刻, 種別, Body, RSSI[dBm], 緯度, 経度, 精度[m], 位置の古さ[s], SEQ
 * - UTF-8 (BOM 付き) … Excel でダブルクリックしても文字化けしない
 * - 1 行ごとに flush … アプリが強制終了されても直前まで残る
 */
class TextLogWriter(val file: File) {
    private val out: BufferedWriter
    private val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    init {
        val isNew = !file.exists() || file.length() == 0L
        out = BufferedWriter(OutputStreamWriter(FileOutputStream(file, true), Charsets.UTF_8))
        if (isNew) {
            out.write("\uFEFF")
            out.write("local_time,kind,body,rssi_dbm,lat,lon,accuracy_m,fix_age_s,seq\r\n")
            out.flush()
        }
    }

    @Synchronized
    fun write(e: LogEntry) {
        val fix = e.fix
        val cols = listOf(
            timeFmt.format(Date(e.timeMillis)),
            when (e.kind) {
                EntryKind.RX -> if (e.terminated) "RX" else "RX(timeout)"
                EntryKind.WARN -> "WARN"
                EntryKind.SYSTEM -> "SYS"
            },
            Fmt.sanitize(e.body),
            e.rssiDbm?.toString() ?: "",
            fix?.let { Fmt.latLon(it.lat) } ?: "",
            fix?.let { Fmt.latLon(it.lon) } ?: "",
            fix?.accuracyM?.let { String.format(Locale.US, "%.1f", it) } ?: "",
            fix?.let { String.format(Locale.US, "%.1f", it.ageSec) } ?: "",
            e.seq?.toString() ?: "",
        )
        out.write(cols.joinToString(",") { csv(it) })
        out.write("\r\n")
        out.flush()
    }

    @Synchronized
    fun close() {
        try {
            out.close()
        } catch (_: Exception) {
        }
    }

    private fun csv(s: String): String =
        if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
}
