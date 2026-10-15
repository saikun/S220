package com.example.s220.log

import com.example.s220.core.RssiScale
import com.example.s220.model.Fmt
import com.example.s220.model.GeoFix
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * GPX 1.1 ファイルを逐次書き出す。
 *
 * 1 件追記するたびに末尾の閉じタグを書き直すため、アプリが途中で落ちても
 * ファイルは常に「正しい GPX」として読み込める。
 *
 * - [Mode.WAYPOINTS] … 受信 1 件 = <wpt> 1 点（座標・RSSI・Body）。
 *   Google マイマップ等に読み込むと受信地点がピンで並び、名前に RSSI が表示される。
 * - [Mode.TRACK] … GPS の移動軌跡 <trk>。受信できなかった区間（圏外）の確認用。
 */
class GpxWriter(val file: File, private val mode: Mode, title: String) {
    enum class Mode { WAYPOINTS, TRACK }

    private val raf = RandomAccessFile(file, "rw")
    private var footerPos: Long
    private val footer: ByteArray
    private val isoUtc = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    var count = 0
        private set

    init {
        val header = buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            append("<gpx version=\"1.1\" creator=\"S220 RX Logger\"\n")
            append("     xmlns=\"http://www.topografix.com/GPX/1/1\"\n")
            append("     xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n")
            append("     xmlns:s220=\"$NS\"\n")
            append("     xsi:schemaLocation=\"http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd\">\n")
            append("  <metadata>\n")
            append("    <name>${xml(title)}</name>\n")
            append("    <desc>E220-900T22L RSSI log. wpt name = RSSI [dBm]</desc>\n")
            append("    <time>${isoUtc.format(Date())}</time>\n")
            append("  </metadata>\n")
            if (mode == Mode.TRACK) {
                append("  <trk>\n    <name>${xml(title)}</name>\n    <trkseg>\n")
            }
        }
        footer = (if (mode == Mode.TRACK) "    </trkseg>\n  </trk>\n</gpx>\n" else "</gpx>\n")
            .toByteArray(Charsets.UTF_8)
        raf.setLength(0)
        raf.write(header.toByteArray(Charsets.UTF_8))
        footerPos = raf.filePointer
        raf.write(footer)
    }

    // 間引き用フィルタリング状態
    private var lastLat: Double? = null
    private var lastLon: Double? = null
    private var lastTimeMillis: Long = 0L

    /** 受信地点を 1 点追加する（WAYPOINTS モード） */
    @Synchronized
    fun addWaypoint(timeMillis: Long, fix: GeoFix, rssiDbm: Int?, body: String, seq: Int?) {
        // 間引き判定: 初回、または 5m 以上移動、または 10秒以上経過
        val prevLat = lastLat
        val prevLon = lastLon
        val dist = if (prevLat != null && prevLon != null) distanceM(prevLat, prevLon, fix.lat, fix.lon) else Double.MAX_VALUE
        val timeDiff = timeMillis - lastTimeMillis

        val shouldRecord = prevLat == null || dist >= MIN_DISTANCE_M || timeDiff >= MIN_INTERVAL_MS
        if (!shouldRecord) return

        lastLat = fix.lat
        lastLon = fix.lon
        lastTimeMillis = timeMillis

        val time = isoUtc.format(Date(timeMillis))
        val name = rssiDbm?.let { "$it dBm" } ?: "RSSI N/A"
        val sb = StringBuilder(512)
        sb.append("  <wpt lat=\"").append(Fmt.latLon(fix.lat)).append("\" lon=\"").append(Fmt.latLon(fix.lon)).append("\">\n")
        fix.altitude?.let { sb.append("    <ele>").append(String.format(Locale.US, "%.1f", it)).append("</ele>\n") }
        sb.append("    <time>").append(time).append("</time>\n")
        sb.append("    <name>").append(xml(name)).append("</name>\n")
        sb.append("    <desc>").append(xml(buildString {
            append("RSSI=").append(rssiDbm?.toString() ?: "N/A").append("dBm")
            seq?.let { append(" SEQ=").append(it) }
            append(" | ").append(Fmt.sanitize(body))
        })).append("</desc>\n")
        sb.append("    <extensions>\n")
        rssiDbm?.let {
            sb.append("      <s220:rssi>").append(it).append("</s220:rssi>\n")
            sb.append("      <s220:level>").append(RssiScale.noteIndex(it)).append("</s220:level>\n")
        }
        seq?.let { sb.append("      <s220:seq>").append(it).append("</s220:seq>\n") }
        sb.append("      <s220:body>").append(xml(Fmt.sanitize(body))).append("</s220:body>\n")
        fix.accuracyM?.let { sb.append("      <s220:accuracy>").append(String.format(Locale.US, "%.1f", it)).append("</s220:accuracy>\n") }
        sb.append("      <s220:fixAge>").append(String.format(Locale.US, "%.1f", fix.ageSec)).append("</s220:fixAge>\n")
        sb.append("    </extensions>\n")
        sb.append("  </wpt>\n")
        append(sb.toString())
    }

    /** 軌跡に 1 点追加する（TRACK モード） */
    @Synchronized
    fun addTrackPoint(fix: GeoFix) {
        // 間引き判定: 初回、または 5m 以上移動、または 10秒以上経過
        val prevLat = lastLat
        val prevLon = lastLon
        val dist = if (prevLat != null && prevLon != null) distanceM(prevLat, prevLon, fix.lat, fix.lon) else Double.MAX_VALUE
        val timeDiff = fix.timeMillis - lastTimeMillis

        val shouldRecord = prevLat == null || dist >= MIN_DISTANCE_M || timeDiff >= MIN_INTERVAL_MS
        if (!shouldRecord) return

        lastLat = fix.lat
        lastLon = fix.lon
        lastTimeMillis = fix.timeMillis

        val sb = StringBuilder(160)
        sb.append("      <trkpt lat=\"").append(Fmt.latLon(fix.lat)).append("\" lon=\"").append(Fmt.latLon(fix.lon)).append("\">")
        fix.altitude?.let { sb.append("<ele>").append(String.format(Locale.US, "%.1f", it)).append("</ele>") }
        sb.append("<time>").append(isoUtc.format(Date(fix.timeMillis))).append("</time>")
        sb.append("</trkpt>\n")
        append(sb.toString())
    }

    private fun append(fragment: String) {
        try {
            raf.seek(footerPos)
            raf.write(fragment.toByteArray(Charsets.UTF_8))
            footerPos = raf.filePointer
            raf.write(footer)
            raf.setLength(raf.filePointer)
            count++
        } catch (_: Exception) {
            // 書き込み失敗（SD カード抜け等）はログ取得を止めない
        }
    }

    @Synchronized
    fun close() {
        try {
            raf.fd.sync()
            raf.close()
        } catch (_: Exception) {
        }
    }

    companion object {
        const val NS = "https://example.com/s220/gpx/1"

        /** 間引きフィルター: 最小移動距離 [m] */
        const val MIN_DISTANCE_M = 5.0

        /** 間引きフィルター: 最大時間間隔 [ms] (10秒) */
        const val MIN_INTERVAL_MS = 10_000L

        /** 2点間の地表距離 [m] (Haversine Formula) */
        fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = 6371000.0 // 地球半径 [m]
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = Math.sin(dLat / 2).let { it * it } +
                    Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                    Math.sin(dLon / 2).let { it * it }
            val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
            return r * c
        }

        /** XML エスケープ + XML 1.0 で使えない文字の除去 */
        fun xml(s: String): String {
            val sb = StringBuilder(s.length + 16)
            for (c in s) {
                when {
                    c == '&' -> sb.append("&amp;")
                    c == '<' -> sb.append("&lt;")
                    c == '>' -> sb.append("&gt;")
                    c == '"' -> sb.append("&quot;")
                    c == '\'' -> sb.append("&apos;")
                    c == '\t' || c == '\n' || c == '\r' -> sb.append(c)
                    c < ' ' || c == '\uFFFE' || c == '\uFFFF' -> sb.append('?')
                    else -> sb.append(c)
                }
            }
            return sb.toString()
        }
    }
}
