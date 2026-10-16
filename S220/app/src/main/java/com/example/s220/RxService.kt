package com.example.s220

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.s220.audio.RssiTonePlayer
import com.example.s220.core.MessageProcessor
import com.example.s220.core.RssiScale
import com.example.s220.core.RxConfig
import com.example.s220.core.RxMessage
import com.example.s220.location.LocationTracker
import com.example.s220.log.GpxWriter
import com.example.s220.log.TextLogWriter
import com.example.s220.model.EntryKind
import com.example.s220.model.Fmt
import com.example.s220.model.GeoFix
import com.example.s220.model.LogEntry
import com.example.s220.usb.SerialReceiver
import com.hoho.android.usbserial.driver.FtdiSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * バックグラウンドで動き続ける受信ロガー（フォアグラウンドサービス）。
 *
 *   USB(FT232RL) --SerialReceiver--> MessageProcessor --onRxMessage--> ・CSV ログ
 *                                                                     ・GPX（受信地点 + RSSI）
 *   LocationTracker --------------------------------------------->    ・GPX（移動軌跡）
 *                                                                     ・RSSI 音階ビープ
 *                                                                     ・RxHub → 画面
 *
 * 画面を消しても・他のアプリを使っていても受信/記録を続ける。
 */
class RxService : Service() {

    companion object {
        private const val TAG = "RxService"
        const val ACTION_START = "com.example.s220.action.START"
        const val ACTION_STOP = "com.example.s220.action.STOP"
        const val ACTION_CONNECT = "com.example.s220.action.CONNECT"
        const val ACTION_SET_SOUND = "com.example.s220.action.SET_SOUND"
        const val EXTRA_LOG_DIR = "log_dir"
        const val EXTRA_SOUND = "sound"
        private const val ACTION_USB_PERMISSION = "com.example.s220.USB_PERMISSION"
        private const val CHANNEL_ID = "rx_logger"
        private const val NOTIF_ID = 1
        private const val TICK_MS = 1000L
        private const val WAKE_LOCK_TIMEOUT_MS = 24 * 60 * 60 * 1000L
        /** この間隔で WakeLock を取り直し、タイムアウトを延長する（24 時間を超える連続記録への対応） */
        private const val WAKE_LOCK_RENEW_MS = 60 * 60 * 1000L

        fun start(ctx: Context, logDir: File, soundOn: Boolean) {
            val i = Intent(ctx, RxService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_LOG_DIR, logDir.absolutePath)
                .putExtra(EXTRA_SOUND, soundOn)
            ContextCompat.startForegroundService(ctx, i)
        }

        fun send(ctx: Context, action: String, configure: Intent.() -> Unit = {}) {
            ctx.startService(Intent(ctx, RxService::class.java).setAction(action).apply(configure))
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var usbManager: UsbManager

    private var sessionActive = false
    private var receiversRegistered = false
    private var permissionPending = false

    // --- USB ---
    private var port: UsbSerialPort? = null
    private var serial: SerialReceiver? = null
    private var deviceId: Int? = null
    private var usbName: String = ""

    // --- 処理・出力 ---
    @Volatile private var processor: MessageProcessor? = null
    @Volatile private var textLog: TextLogWriter? = null
    @Volatile private var rssiGpx: GpxWriter? = null
    @Volatile private var trackGpx: GpxWriter? = null
    // 受信スレッドからも参照するため @Volatile
    @Volatile private var location: LocationTracker? = null
    @Volatile private var tone: RssiTonePlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var soundOn = true
    private var lastNotifyAt = 0L
    private var wakeLockAcquiredAt = 0L
    private val entryId = AtomicLong(0)

    // =========================================================================
    // Service ライフサイクル
    // =========================================================================
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                goForeground()
                soundOn = intent.getBooleanExtra(EXTRA_SOUND, true)
                if (!sessionActive) {
                    val dir = intent.getStringExtra(EXTRA_LOG_DIR)?.let(::File)
                    if (dir == null) {
                        stopSelf()
                    } else {
                        startSession(dir)
                    }
                } else {
                    connectUsb()
                }
            }
            ACTION_CONNECT -> if (sessionActive) connectUsb() else stopSelf()
            ACTION_SET_SOUND -> {
                soundOn = intent.getBooleanExtra(EXTRA_SOUND, true)
                tone?.enabled = soundOn
                RxHub.updateStatus { it.copy(soundOn = soundOn) }
                if (!sessionActive) stopSelf()
            }
            ACTION_STOP -> {
                stopSession()
                stopSelf()
            }
            else -> if (!sessionActive) stopSelf() // システムによる再起動等
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopSession()
        super.onDestroy()
    }

    // =========================================================================
    // セッション（ログ 1 回分）の開始・終了
    // =========================================================================
    private fun startSession(dir: File) {
        sessionActive = true
        dir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val title = "S220 RX $stamp"
        try {
            textLog = TextLogWriter(File(dir, "rx_$stamp.csv"))
            rssiGpx = GpxWriter(File(dir, "rx_${stamp}_rssi.gpx"), GpxWriter.Mode.WAYPOINTS, "$title RSSI")
            trackGpx = GpxWriter(File(dir, "rx_${stamp}_track.gpx"), GpxWriter.Mode.TRACK, "$title track")
        } catch (e: Exception) {
            Log.e(TAG, "cannot create log files", e)
            addSystem("ログファイルを作成できません: ${e.message}", EntryKind.WARN)
        }

        processor = MessageProcessor(onMessage = ::onRxMessage, rssiEnabled = true)
        tone = RssiTonePlayer().also { it.enabled = soundOn }

        RxHub.clear()
        RxHub.updateStatus {
            RxHub.Status(running = true, logDir = dir.absolutePath, soundOn = soundOn)
        }
        addSystem("ログ開始: ${dir.absolutePath}/rx_$stamp.*")

        // 位置情報
        if (hasLocationPermission()) {
            location = LocationTracker(this, ::onGpsLocation).also {
                if (!it.start()) addSystem("位置情報を開始できません", EntryKind.WARN)
                else if (!it.isGpsEnabled) addSystem("端末の位置情報(GPS)がOFFです", EntryKind.WARN)
            }
        } else {
            addSystem("位置情報の権限がありません（位置なしで記録します）", EntryKind.WARN)
        }

        // CPU がスリープして受信を取りこぼさないようにする
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "S220:rx").apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }
        wakeLockAcquiredAt = SystemClock.elapsedRealtime()

        registerUsbReceivers()
        main.post(tick)
        connectUsb()
    }

    private fun stopSession() {
        if (!sessionActive) return
        sessionActive = false
        main.removeCallbacks(tick)
        closePort(null)
        addSystem("ログ終了")
        unregisterUsbReceivers()
        location?.stop(); location = null
        tone?.release(); tone = null
        textLog?.close(); textLog = null
        rssiGpx?.close(); rssiGpx = null
        trackGpx?.close(); trackGpx = null
        processor = null
        wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null
        RxHub.updateStatus { it.copy(running = false, usbConnected = false, usbText = "USB: 停止") }
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    // =========================================================================
    // USB
    // =========================================================================
    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    permissionPending = false
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        connectUsb()
                    } else {
                        addSystem("USB の使用が許可されませんでした", EntryKind.WARN)
                        setUsbStatus(false, "USB: 権限なし（メニュー→USB再接続）")
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> main.postDelayed({ connectUsb() }, 500)
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    @Suppress("DEPRECATION")
                    val dev = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    if (dev == null || dev.deviceId == deviceId) closePort("USB 切断")
                }
            }
        }
    }

    private fun registerUsbReceivers() {
        if (receiversRegistered) return
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(usbReceiver, filter)
        }
        receiversRegistered = true
    }

    private fun unregisterUsbReceivers() {
        if (!receiversRegistered) return
        try {
            unregisterReceiver(usbReceiver)
        } catch (_: Exception) {
        }
        receiversRegistered = false
    }

    /** FT232RL を探して接続する（権限が無ければ要求する） */
    private fun connectUsb() {
        if (!sessionActive || port != null || permissionPending) return
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        if (drivers.isEmpty()) {
            setUsbStatus(false, "USB: 未接続（FT232RL を接続してください）")
            return
        }
        val driver = drivers.firstOrNull { it is FtdiSerialDriver } ?: drivers[0]
        val device = driver.device
        if (!usbManager.hasPermission(device)) {
            permissionPending = true
            setUsbStatus(false, "USB: 使用許可を待っています…")
            val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            // Android 14: 可変 PendingIntent は明示的 Intent（setPackage）でなければならない
            val pi = PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION).setPackage(packageName), flags)
            usbManager.requestPermission(device, pi)
            return
        }
        openPort(driver)
    }

    private fun openPort(driver: UsbSerialDriver) {
        val conn = usbManager.openDevice(driver.device)
        if (conn == null) {
            setUsbStatus(false, "USB: オープン失敗")
            addSystem("USB デバイスを開けません", EntryKind.WARN)
            return
        }
        val p = driver.ports[0]
        try {
            p.open(conn)
            p.setParameters(RxConfig.DEFAULT_BAUDRATE, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            try {
                p.purgeHwBuffers(true, false) // reset_input_buffer 相当
            } catch (_: Exception) {
            }
        } catch (e: Exception) {
            try {
                p.close()
            } catch (_: Exception) {
            }
            setUsbStatus(false, "USB: オープン失敗")
            addSystem("USB オープン失敗: ${e.message}", EntryKind.WARN)
            return
        }
        port = p
        deviceId = driver.device.deviceId
        usbName = driver.device.productName ?: driver.javaClass.simpleName.removeSuffix("Driver")
        val proc = processor ?: return
        serial = SerialReceiver(
            port = p,
            onBytes = { buf, n -> proc.handleBytes(buf, n) },
            onIdle = { proc.handleIdle() },
            onError = { e -> main.post { closePort("USB 切断/エラー: ${e.message ?: e.javaClass.simpleName}") } },
        ).also { it.start() }
        setUsbStatus(true, "USB: $usbName ${RxConfig.DEFAULT_BAUDRATE}bps")
        addSystem("USB 接続: $usbName (${RxConfig.DEFAULT_BAUDRATE}bps 8N1)")
    }

    private fun closePort(reason: String?) {
        val s = serial
        val p = port
        if (s == null && p == null) return
        serial = null
        port = null
        deviceId = null
        s?.stop()
        try {
            p?.close()
        } catch (_: Exception) {
        }
        processor?.handleIdle(force = true) // 残りのバッファがあれば確定させる
        if (reason != null) addSystem(reason, EntryKind.WARN)
        setUsbStatus(false, "USB: 未接続")
    }

    // =========================================================================
    // 受信処理（SerialReceiver スレッド上で呼ばれる）
    // =========================================================================
    private fun onRxMessage(msg: RxMessage) {
        val fix = location?.current()
        if (msg.lostBefore > 0) {
            emit(LogEntry(entryId.incrementAndGet(), EntryKind.WARN, msg.receivedAtMillis,
                "欠落の可能性: ${msg.lostBefore} 件 (SEQ が飛びました)", fix = fix))
        }
        val e = LogEntry(
            id = entryId.incrementAndGet(),
            kind = EntryKind.RX,
            timeMillis = msg.receivedAtMillis,
            body = Fmt.sanitize(msg.text),
            rssiDbm = msg.rssiDbm,
            fix = fix,
            seq = msg.seq,
            terminated = msg.terminated,
        )
        emit(e)
        if (fix != null) rssiGpx?.addWaypoint(e.timeMillis, fix, e.rssiDbm, e.body, e.seq)
        msg.rssiDbm?.let { tone?.play(it) }
        publishStats()
        maybeUpdateNotification()
    }

    private fun emit(e: LogEntry) {
        textLog?.write(e)
        RxHub.postEntry(e)
    }

    private fun addSystem(text: String, kind: EntryKind = EntryKind.SYSTEM) {
        emit(LogEntry(entryId.incrementAndGet(), kind, System.currentTimeMillis(), text, fix = location?.current()))
    }

    /** GPS 測位ごとに軌跡 GPX へ追記（LocationTracker スレッド） */
    private fun onGpsLocation(loc: android.location.Location) {
        trackGpx?.addTrackPoint(GeoFix.from(loc))
    }

    private fun publishStats() {
        val st = processor?.stats ?: return
        RxHub.updateStatus {
            it.copy(
                total = st.totalFrames,
                lost = st.lost,
                rssiLast = st.rssiLast,
                rssiMin = st.rssiMin,
                rssiAvg = st.rssiAvg,
                rssiMax = st.rssiMax,
                gpxPoints = rssiGpx?.count ?: 0,
            )
        }
    }

    private fun setUsbStatus(connected: Boolean, text: String) {
        RxHub.updateStatus { it.copy(usbConnected = connected, usbText = text) }
        updateNotification()
    }

    /** 1 秒ごとに GPS 状態を更新 */
    private val tick = object : Runnable {
        override fun run() {
            if (!sessionActive) return
            val fix = location?.current()
            val (ok, text) = when {
                location == null -> false to "GPS: 権限なし"
                fix == null -> false to "GPS: 測位中…"
                else -> {
                    val acc = fix.accuracyM?.let { "±${it.toInt()}m" } ?: ""
                    val src = if (fix.provider == "gps") "GPS" else "NET"
                    !fix.isStale to "$src: $acc ${fix.ageSec.toInt()}s前"
                }
            }
            RxHub.updateStatus { it.copy(gpsOk = ok, gpsText = text, gpxPoints = rssiGpx?.count ?: 0) }
            renewWakeLockIfNeeded()
            main.postDelayed(this, TICK_MS)
        }
    }

    /** 参照カウントなしの WakeLock は acquire し直すとタイムアウトが延長される */
    private fun renewWakeLockIfNeeded() {
        val wl = wakeLock ?: return
        val now = SystemClock.elapsedRealtime()
        if (now - wakeLockAcquiredAt < WAKE_LOCK_RENEW_MS) return
        wl.acquire(WAKE_LOCK_TIMEOUT_MS)
        wakeLockAcquiredAt = now
    }

    // =========================================================================
    // 通知
    // =========================================================================
    private fun goForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "受信ログ記録", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "LoRa 受信ログを記録中であることを表示します"
                    setShowBadge(false)
                }
            )
        }
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            // 位置情報をバックグラウンドで取得するため location タイプで開始する
            val type = if (hasLocationPermission()) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
            startForeground(NOTIF_ID, n, type)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun buildNotification(): Notification {
        val s = RxHub.status
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, RxService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val rssi = s.rssiLast?.let { "  最新 $it dBm (${RssiScale.noteName(it)})" } ?: ""
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_rx)
            .setContentTitle("S220 受信ログ記録中")
            .setContentText("受信 ${s.total} 件$rssi")
            .setSubText(s.usbText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(open)
            .addAction(0, "停止", stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun maybeUpdateNotification() {
        val now = System.currentTimeMillis()
        if (now - lastNotifyAt < 2000) return
        lastNotifyAt = now
        // RxHub.status はメインスレッドで更新されるため、反映後に通知を作る
        main.post { updateNotification() }
    }

    private fun updateNotification() {
        if (!sessionActive) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            nm.notify(NOTIF_ID, buildNotification())
        } catch (_: SecurityException) {
        }
    }

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
}
