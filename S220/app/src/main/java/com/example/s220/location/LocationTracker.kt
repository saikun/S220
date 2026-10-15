package com.example.s220.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import com.example.s220.model.GeoFix

/**
 * 位置情報の取得（Google Play Services 非依存の LocationManager 版）。
 *
 * GPS を 1 秒周期で取得し、GPS が取れない（屋内等）ときはネットワーク位置で補う。
 * コールバックは専用スレッド上で呼ばれる。
 */
class LocationTracker(
    context: Context,
    private val onLocation: (Location) -> Unit,
) {
    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var thread: HandlerThread? = null

    @Volatile
    private var lastGps: Location? = null

    @Volatile
    private var lastNetwork: Location? = null

    private val gpsListener = Listener { lastGps = it; onLocation(it) }
    private val netListener = Listener { lastNetwork = it }

    /** 権限が無い場合は false を返す（呼び出し側で事前に権限確認すること） */
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (thread != null) return true
        val t = HandlerThread("LocationTracker").also { it.start() }
        thread = t
        val looper = t.looper
        return try {
            var any = false
            if (lm.allProviders.contains(LocationManager.GPS_PROVIDER)) {
                lastGps = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, GPS_INTERVAL_MS, 0f, gpsListener, looper)
                any = true
            }
            if (lm.allProviders.contains(LocationManager.NETWORK_PROVIDER)) {
                lastNetwork = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, NET_INTERVAL_MS, 0f, netListener, looper)
                any = true
            }
            any
        } catch (e: SecurityException) {
            Log.w(TAG, "location permission missing", e)
            stop()
            false
        }
    }

    fun stop() {
        try {
            lm.removeUpdates(gpsListener)
            lm.removeUpdates(netListener)
        } catch (_: Exception) {
        }
        thread?.quitSafely()
        thread = null
    }

    val isGpsEnabled: Boolean
        get() = try {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
        } catch (_: Exception) {
            false
        }

    /** 現在の最良位置（GPS が新しければ GPS、そうでなければ新しい方） */
    fun current(): GeoFix? {
        val gps = lastGps
        val net = lastNetwork
        val best = when {
            gps != null && ageSec(gps) <= GPS_PREFERRED_SEC -> gps
            gps == null -> net
            net == null -> gps
            else -> if (gps.elapsedRealtimeNanos >= net.elapsedRealtimeNanos) gps else net
        } ?: return null
        return GeoFix.from(best)
    }

    private fun ageSec(l: Location) =
        (SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos) / 1e9

    /** API 24〜29 では onStatusChanged 等の既定実装が無いため明示的に実装する */
    private class Listener(private val cb: (Location) -> Unit) : LocationListener {
        override fun onLocationChanged(location: Location) = cb(location)

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
        }

        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    companion object {
        private const val TAG = "LocationTracker"
        private const val GPS_INTERVAL_MS = 1000L
        private const val NET_INTERVAL_MS = 5000L
        private const val GPS_PREFERRED_SEC = 10.0
    }
}
