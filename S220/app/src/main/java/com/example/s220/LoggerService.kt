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
import com.example.s220.core.AppMode
import com.example.s220.core.FixBurstScheduler
import com.example.s220.core.FixCandidate
import com.example.s220.core.FixDecision
import com.example.s220.core.MessageProcessor
import com.example.s220.core.RssiScale
import com.example.s220.core.RxConfig
import com.example.s220.core.RxMessage
import com.example.s220.core.TxConfig
import com.example.s220.location.LocationTracker
import com.example.s220.log.GpxWriter
import com.example.s220.log.TextLogWriter
import com.example.s220.model.EntryKind
import com.example.s220.model.Fmt
import com.example.s220.model.GeoFix
import com.example.s220.model.LogEntry
import com.example.s220.usb.SerialReceiver
import com.example.s220.usb.SerialTransmitter
import com.hoho.android.usbserial.driver.FtdiSerialDriver
import com.hoho.android.usbserial.driver.SerialTimeoutException
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * バックグラウンドで動き続ける受信/送信ロガー（フォアグラウンドサービス）。
 *
 * [AppMode.RX] 受信モード:
 *   USB(FT232RL) --SerialReceiver--> MessageProcessor --onRxMessage--> ・CSV ログ
 *                                                                     ・GPX（受信地点 + RSSI）
 *   LocationTracker --------------------------------------------->    ・GPX（移動軌跡）
 *                                                                     ・RSSI 音階ビープ
 *                                                                     ・LoggerHub → 画面
 *
 * [AppMode.TX] 送信モード（送信側は基本的に移動しないため、位置の記録は最小限）:
 *   SerialTransmitter --周期書き込み--> USB(FT232RL)
 *                     --onTxSent-----> ・CSV ログ / LoggerHub → 画面
 *   LocationTracker --(起動時 + 5 分ごとに短時間だけ測位し 1 点)--> ・GPX（軌跡）
 *   （測位の合間は位置の取得を止めて電池消費を抑える）
 *
 * 画面を消しても・他のアプリを使っていても受信(送信)/記録を続ける。
 */
class LoggerService : Service() {

    companion object {
        private const val TAG = "LoggerService"
        const val ACTION_START = "com.example.s220.action.START"
        const val ACTION_STOP = "com.example.s220.action.STOP"
        const val ACTION_CONNECT = "com.example.s220.action.CONNECT"
        const val ACTION_SET_SOUND = "com.example.s220.action.SET_SOUND"
        const val ACTION_SET_INTERVAL = "com.example.s220.action.SET_INTERVAL"
        const val EXTRA_LOG_DIR = "log_dir"
        const val EXTRA_SOUND = "sound"
        const val EXTRA_MODE = "mode"
        const val EXTRA_INTERVAL_SEC = "interval_sec"
        private const val ACTION_USB_PERMISSION = "com.example.s220.USB_PERMISSION"
        private const val CHANNEL_ID = "rx_logger"
        private const val NOTIF_ID = 1
        private const val TICK_MS = 1000L
        private const val WAKE_LOCK_TIMEOUT_MS = 24 * 60 * 60 * 1000L
        /** この間隔で WakeLock を取り直し、タイムアウトを延長する（24 時間を超える連続記録への対応） */
        private const val WAKE_LOCK_RENEW_MS = 60 * 60 * 1000L

        fun start(ctx: Context, logDir: File, mode: AppMode, intervalSec: Int, soundOn: Boolean) {
            val i = Intent(ctx, LoggerService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_LOG_DIR, logDir.absolutePath)
                .putExtra(EXTRA_MODE, mode.name)
                .putExtra(EXTRA_INTERVAL_SEC, intervalSec)
                .putExtra(EXTRA_SOUND, soundOn)
            ContextCompat.startForegroundService(ctx, i)
        }

        fun send(ctx: Context, action: String, configure: Intent.() -> Unit = {}) {
            ctx.startService(Intent(ctx, LoggerService::class.java).setAction(action).apply(configure))
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var usbManager: UsbManager

    private var sessionActive = false
    private var receiversRegistered = false
    private var permissionPending = false
    @Volatile private var mode = AppMode.RX

    // --- USB ---
    // 送信スレッドからも参照するため @Volatile
    @Volatile private var port: UsbSerialPort? = null
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
    // --- 送信モード ---
    private var transmitter: SerialTransmitter? = null
    private var txIntervalSec = TxConfig.DEFAULT_INTERVAL_SEC
    private val txFailed = AtomicInteger(0)
    /** 間欠測位のスケジュール（TX のみ。メインスレッドからのみ参照） */
    private var fixScheduler: FixBurstScheduler<GeoFix>? = null
    /** 直近の測位で位置を記録できたか（GPS ステータスの色に使う） */
    private var lastTxFixOk = false
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
                        mode = intent.getStringExtra(EXTRA_MODE)
                            ?.let { runCatching { AppMode.valueOf(it) }.getOrNull() } ?: AppMode.RX
                        txIntervalSec = intent.getIntExtra(EXTRA_INTERVAL_SEC, TxConfig.DEFAULT_INTERVAL_SEC)
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
                LoggerHub.updateStatus { it.copy(soundOn = soundOn) }
                if (!sessionActive) stopSelf()
            }
            ACTION_SET_INTERVAL -> {
                if (!sessionActive) {
                    stopSelf()
                } else if (mode == AppMode.TX) {
                    setTxInterval(intent.getIntExtra(EXTRA_INTERVAL_SEC, txIntervalSec))
                }
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
        val tx = mode == AppMode.TX
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val prefix = if (tx) "tx" else "rx"
        val title = "S220 ${prefix.uppercase(Locale.US)} $stamp"
        try {
            textLog = TextLogWriter(File(dir, "${prefix}_$stamp.csv"))
            // 送信モードは位置が固定のため、送信地点ごとの GPX（_rssi.gpx）は作らない
            if (!tx) rssiGpx = GpxWriter(File(dir, "${prefix}_${stamp}_rssi.gpx"), GpxWriter.Mode.WAYPOINTS, "$title RSSI")
            trackGpx = GpxWriter(File(dir, "${prefix}_${stamp}_track.gpx"), GpxWriter.Mode.TRACK, "$title track")
        } catch (e: Exception) {
            Log.e(TAG, "cannot create log files", e)
            addSystem("ログファイルを作成できません: ${e.message}", EntryKind.WARN)
        }

        txFailed.set(0)
        fixScheduler = null
        lastTxFixOk = false
        if (tx) {
            transmitter = SerialTransmitter(
                intervalSec = txIntervalSec,
                portProvider = { port },
                onSent = ::onTxSent,
                onError = ::onTxError,
            )
        } else {
            processor = MessageProcessor(onMessage = ::onRxMessage, rssiEnabled = true)
            tone = RssiTonePlayer().also { it.enabled = soundOn }
        }

        LoggerHub.clear()
        LoggerHub.updateStatus {
            LoggerHub.Status(
                running = true, mode = mode, logDir = dir.absolutePath, soundOn = soundOn,
                txIntervalSec = if (tx) txIntervalSec else 0,
            )
        }
        addSystem("ログ開始: ${dir.absolutePath}/${prefix}_$stamp.*")
        if (tx) {
            val target = if (TxConfig.FIXED_MODE) {
                String.format(
                    Locale.US, "固定送信 宛先アドレス=%d (0x%04X), チャンネル=%d",
                    TxConfig.ADDRESS, TxConfig.ADDRESS, TxConfig.CHANNEL,
                )
            } else {
                "トランスペアレント送信"
            }
            addSystem("送信モード: $target, 間隔 ${txIntervalSec}秒, MSG=${TxConfig.MESSAGE}")
        }

        // 位置情報（TX は常時取得せず、tick から 5 分ごとに短時間だけ測位する）
        if (hasLocationPermission()) {
            location = LocationTracker(this, ::onGpsLocation).also {
                if (tx) {
                    fixScheduler = FixBurstScheduler()
                    if (!it.isGpsEnabled) addSystem("端末の位置情報(GPS)がOFFです", EntryKind.WARN)
                } else if (!it.start()) {
                    addSystem("位置情報を開始できません", EntryKind.WARN)
                } else if (!it.isGpsEnabled) {
                    addSystem("端末の位置情報(GPS)がOFFです", EntryKind.WARN)
                }
            }
        } else {
            addSystem("位置情報の権限がありません（位置なしで記録します）", EntryKind.WARN)
        }

        // CPU がスリープして受信を取りこぼさないようにする
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "S220:logger").apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }
        wakeLockAcquiredAt = SystemClock.elapsedRealtime()

        registerUsbReceivers()
        main.post(tick)
        transmitter?.start()
        connectUsb()
    }

    private fun stopSession() {
        if (!sessionActive) return
        sessionActive = false
        main.removeCallbacks(tick)
        transmitter?.stop(); transmitter = null
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
        LoggerHub.updateStatus { it.copy(running = false, usbConnected = false, usbText = "USB: 停止") }
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
                // RX: reset_input_buffer 相当 / TX: reset_input_buffer + reset_output_buffer 相当
                p.purgeHwBuffers(true, mode == AppMode.TX)
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
        setUsbStatus(true, "USB: $usbName ${RxConfig.DEFAULT_BAUDRATE}bps")
        addSystem("USB 接続: $usbName (${RxConfig.DEFAULT_BAUDRATE}bps 8N1)")
        // TX: 送信スレッドは port を参照して書き込む。接続したらすぐに 1 回送信し、そこから周期を数える
        transmitter?.setInterval(txIntervalSec)
        val proc = processor ?: return
        serial = SerialReceiver(
            port = p,
            onBytes = { buf, n -> proc.handleBytes(buf, n) },
            onIdle = { proc.handleIdle() },
            onError = { e -> main.post { closePort("USB 切断/エラー: ${e.message ?: e.javaClass.simpleName}") } },
        ).also { it.start() }
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

    // =========================================================================
    // 送信処理（SerialTransmitter スレッド上で呼ばれる）
    // =========================================================================
    private fun onTxSent(seq: Int, payload: ByteArray, timeMillis: Long) {
        val text = String(payload, Charsets.UTF_8).trimEnd('\r', '\n')
        // 送信側は位置が固定のため、送信 1 件ごとには位置を付けない（位置は軌跡 GPX に 5 分ごと）
        emit(LogEntry(entryId.incrementAndGet(), EntryKind.TX, timeMillis, Fmt.sanitize(text), seq = seq))
        LoggerHub.updateStatus { it.copy(total = seq) }
        maybeUpdateNotification()
    }

    private fun onTxError(p: UsbSerialPort, e: Exception) {
        val failed = txFailed.incrementAndGet()
        val reason = e.message ?: e.javaClass.simpleName
        main.post {
            LoggerHub.updateStatus { it.copy(txFailed = failed) }
            if (e is SerialTimeoutException) {
                // tx_app.py と同じく配線・電源の異常を疑う。ポートは開いたまま次の周期で再試行する
                addSystem("書き込みタイムアウト。配線・電源を確認してください: $reason", EntryKind.WARN)
            } else if (port === p) {
                closePort("USB 切断/書き込みエラー: $reason")
            }
        }
    }

    private fun setTxInterval(sec: Int) {
        if (sec !in TxConfig.INTERVALS_SEC || sec == txIntervalSec) return
        txIntervalSec = sec
        transmitter?.setInterval(sec)
        LoggerHub.updateStatus { it.copy(txIntervalSec = sec) }
        addSystem("送信間隔を変更: ${sec}秒")
        updateNotification()
    }

    private fun emit(e: LogEntry) {
        textLog?.write(e)
        LoggerHub.postEntry(e)
    }

    /** 送信モードでは位置を付けない（位置は軌跡 GPX と「位置を記録」行だけに残す） */
    private fun addSystem(
        text: String,
        kind: EntryKind = EntryKind.SYSTEM,
        fix: GeoFix? = if (mode == AppMode.TX) null else location?.current(),
    ) {
        emit(LogEntry(entryId.incrementAndGet(), kind, System.currentTimeMillis(), text, fix = fix))
    }

    /** GPS 測位ごとに軌跡 GPX へ追記（LocationTracker スレッド）。送信モードは [tickTxLocation] で記録する */
    private fun onGpsLocation(loc: android.location.Location) {
        if (mode == AppMode.RX) trackGpx?.addTrackPoint(GeoFix.from(loc))
    }

    /**
     * 送信モードの間欠測位（tick からメインスレッドで呼ぶ）。
     * 起動直後と以降 5 分ごとに位置の取得を開始し、1 点得たら（または 60 秒で打ち切って）取得を止める。
     * 戻り値: GPS ステータス表示 (緑にするか, 文言)
     */
    private fun tickTxLocation(): Pair<Boolean, String> {
        val tracker = location ?: return false to "GPS: 権限なし"
        val scheduler = fixScheduler ?: return false to "GPS: ---"
        val current = tracker.current()
        when (val d = scheduler.onTick(current?.let { FixCandidate(it, it.ageSec, it.accuracyM) })) {
            FixDecision.Start -> if (!tracker.start()) addSystem("位置情報を開始できません", EntryKind.WARN)
            is FixDecision.Done -> {
                tracker.stop()
                val fix = d.fix
                lastTxFixOk = fix != null
                if (fix != null) {
                    trackGpx?.addTrackPoint(fix)
                    val acc = fix.accuracyM?.let { " ±${it.toInt()}m" } ?: ""
                    val src = if (fix.provider == "gps") "GPS" else "NET"
                    addSystem("位置を記録 ($src$acc)", fix = fix)
                } else {
                    addSystem("位置を取得できませんでした（${TxConfig.FIX_TIMEOUT_MS / 1000}秒で打ち切り）", EntryKind.WARN)
                }
            }
            FixDecision.Idle, FixDecision.Measuring -> {}
        }
        if (scheduler.isMeasuring) return false to "GPS: 測位中…"
        val left = (scheduler.msUntilNext() + 999) / 1000
        return lastTxFixOk to String.format(Locale.US, "GPS: 停止 次回%d:%02d", left / 60, left % 60)
    }

    /** 画面の「GPX: n点」に出す点数（RX: 受信地点 / TX: 軌跡） */
    private fun gpxPoints(): Int = (if (mode == AppMode.TX) trackGpx?.count else rssiGpx?.count) ?: 0

    private fun publishStats() {
        val st = processor?.stats ?: return
        LoggerHub.updateStatus {
            it.copy(
                total = st.totalFrames,
                lost = st.lost,
                rssiLast = st.rssiLast,
                rssiMin = st.rssiMin,
                rssiAvg = st.rssiAvg,
                rssiMax = st.rssiMax,
                gpxPoints = gpxPoints(),
            )
        }
    }

    private fun setUsbStatus(connected: Boolean, text: String) {
        LoggerHub.updateStatus { it.copy(usbConnected = connected, usbText = text) }
        updateNotification()
    }

    /** 1 秒ごとに GPS 状態を更新（送信モードは間欠測位の制御も） */
    private val tick = object : Runnable {
        override fun run() {
            if (!sessionActive) return
            val (ok, text) = if (mode == AppMode.TX) tickTxLocation() else rxGpsStatus()
            LoggerHub.updateStatus { it.copy(gpsOk = ok, gpsText = text, gpxPoints = gpxPoints()) }
            renewWakeLockIfNeeded()
            main.postDelayed(this, TICK_MS)
        }
    }

    /** 受信モードの GPS ステータス表示 (緑にするか, 文言) */
    private fun rxGpsStatus(): Pair<Boolean, String> {
        val fix = location?.current()
        return when {
            location == null -> false to "GPS: 権限なし"
            fix == null -> false to "GPS: 測位中…"
            else -> {
                val acc = fix.accuracyM?.let { "±${it.toInt()}m" } ?: ""
                val src = if (fix.provider == "gps") "GPS" else "NET"
                !fix.isStale to "$src: $acc ${fix.ageSec.toInt()}s前"
            }
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
        if (Build.VERSION.SDK_INT >= 26) {
            // 既存のチャンネルに対しては名前・説明だけが更新される（旧名「受信ログ記録」からの移行）
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "ログ記録", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "LoRa 受信・送信ログを記録中であることを表示します"
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
        val s = LoggerHub.status
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, LoggerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val (title, text) = if (mode == AppMode.TX) {
            val failed = if (s.txFailed > 0) "  失敗 ${s.txFailed} 件" else ""
            "S220 送信中" to "送信 ${s.total} 件（${txIntervalSec}秒間隔）$failed"
        } else {
            val rssi = s.rssiLast?.let { "  最新 $it dBm (${RssiScale.noteName(it)})" } ?: ""
            "S220 受信ログ記録中" to "受信 ${s.total} 件$rssi"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_rx)
            .setContentTitle(title)
            .setContentText(text)
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
        // LoggerHub.status はメインスレッドで更新されるため、反映後に通知を作る
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
