package com.example.s220.model

import android.location.Location
import android.os.SystemClock

/** ログ 1 行分の種別 */
enum class EntryKind { RX, TX, WARN, SYSTEM }

/** 受信時点での位置のスナップショット */
data class GeoFix(
    val lat: Double,
    val lon: Double,
    val altitude: Double?,
    val accuracyM: Float?,
    val provider: String?,
    /** 位置の取得時刻（epoch ms） */
    val timeMillis: Long,
    /** スナップショット作成時点での位置の古さ [秒] */
    val ageSec: Double,
) {
    val isStale: Boolean get() = ageSec > STALE_SEC

    companion object {
        /** これより古い位置は「古い」とみなして画面上で薄く表示する */
        const val STALE_SEC = 10.0

        fun from(loc: Location): GeoFix {
            val ageNs = SystemClock.elapsedRealtimeNanos() - loc.elapsedRealtimeNanos
            return GeoFix(
                lat = loc.latitude,
                lon = loc.longitude,
                altitude = if (loc.hasAltitude()) loc.altitude else null,
                accuracyM = if (loc.hasAccuracy()) loc.accuracy else null,
                provider = loc.provider,
                timeMillis = loc.time,
                ageSec = ageNs.coerceAtLeast(0) / 1e9,
            )
        }
    }
}

/**
 * 画面・テキストログに出す 1 行。
 * 画面構成: ローカル時間｜受信（送信）したBody｜RSSI｜現在の位置情報
 */
data class LogEntry(
    val id: Long,
    val kind: EntryKind,
    val timeMillis: Long,
    val body: String,
    val rssiDbm: Int? = null,
    val fix: GeoFix? = null,
    val seq: Int? = null,
    /** 改行で終端されたか（false: タイムアウトで確定） */
    val terminated: Boolean = true,
)

/** 表示・保存用の文字列整形 */
object Fmt {
    /** 制御文字を可視化する（ログが崩れないように） */
    fun sanitize(s: String): String {
        if (s.none { it < ' ' || it == '\u007f' }) return s
        val sb = StringBuilder(s.length + 8)
        for (c in s) {
            if (c < ' ' || c == '\u007f') sb.append(String.format("\\x%02X", c.code)) else sb.append(c)
        }
        return sb.toString()
    }

    fun latLon(v: Double): String = String.format(java.util.Locale.US, "%.6f", v)
}
