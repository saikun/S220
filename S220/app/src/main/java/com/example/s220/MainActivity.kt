package com.example.s220

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.s220.databinding.ActivityMainBinding
import com.example.s220.model.LogEntry
import java.io.File
import java.util.Locale

class MainActivity : AppCompatActivity(), RxHub.Listener {

    private lateinit var b: ActivityMainBinding
    private lateinit var adapter: LogAdapter
    private lateinit var layoutManager: LinearLayoutManager

    /** 最新行を自動追従するかどうか */
    private var followLatest = true

    /** 保存先選択ダイアログの表示中フラグ（多重表示防止） */
    private var showingStorageDialog = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        checkAndStartLogging()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        setupRecycler()
        setupButtons()

        // 権限確認 & 保存先選択 & サービス開始
        requestPermissionsIfNeeded()
    }

    override fun onStart() {
        super.onStart()
        RxHub.addListener(this)
        restoreState()
    }

    override fun onStop() {
        super.onStop()
        RxHub.removeListener(this)
    }

    // =========================================================================
    // UI セットアップ
    // =========================================================================
    private fun setupRecycler() {
        adapter = LogAdapter()
        layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true // 下から積み上げる
        }
        b.recyclerLog.layoutManager = layoutManager
        b.recyclerLog.adapter = adapter
        b.recyclerLog.itemAnimator = null // 高頻度更新時のチラつき防止

        // スクロール検知: 一番下にいれば追従、手動で上にスクロールしたら追従一時停止
        b.recyclerLog.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy < 0) {
                    // 上へスクロールした
                    setFollowing(false)
                } else if (!recyclerView.canScrollVertically(1)) {
                    // 一番下まで到達した
                    setFollowing(true)
                }
            }
        })

        b.btnFollowLatest.setOnClickListener {
            setFollowing(true)
            scrollToBottom()
        }
    }

    private fun setupButtons() {
        b.btnMenu.setOnClickListener { v -> showMenu(v) }
    }

    private fun setFollowing(following: Boolean) {
        followLatest = following
        b.btnFollowLatest.visibility = if (following) View.GONE else View.VISIBLE
    }

    private fun scrollToBottom() {
        val count = adapter.itemCount
        if (count > 0) {
            b.recyclerLog.scrollToPosition(count - 1)
        }
    }

    private fun showMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menuInflater.inflate(R.menu.main_menu, popup.menu)
        val st = RxHub.status

        popup.menu.findItem(R.id.menu_start)?.isVisible = !st.running
        popup.menu.findItem(R.id.menu_sound)?.isChecked = st.soundOn
        val isKeepScreen = (window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0
        popup.menu.findItem(R.id.menu_keep_screen)?.isChecked = isKeepScreen

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.menu_start -> {
                    checkAndStartLogging()
                    true
                }
                R.id.menu_sound -> {
                    val next = !st.soundOn
                    RxService.send(this, RxService.ACTION_SET_SOUND) {
                        putExtra(RxService.EXTRA_SOUND, next)
                    }
                    true
                }
                R.id.menu_keep_screen -> {
                    if (isKeepScreen) {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        Toast.makeText(this, "画面の常時ON: 解除", Toast.LENGTH_SHORT).show()
                    } else {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        Toast.makeText(this, "画面の常時ON: 設定", Toast.LENGTH_SHORT).show()
                    }
                    true
                }
                R.id.menu_reconnect -> {
                    RxService.send(this, RxService.ACTION_CONNECT)
                    Toast.makeText(this, "USB 再接続を要求しました", Toast.LENGTH_SHORT).show()
                    true
                }
                R.id.menu_files -> {
                    showStorageInfo()
                    true
                }
                R.id.menu_clear -> {
                    RxHub.clear()
                    true
                }
                R.id.menu_stop -> {
                    RxService.send(this, RxService.ACTION_STOP)
                    finish()
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    // =========================================================================
    // 保存先選択 & 権限
    // =========================================================================
    private fun requestPermissionsIfNeeded() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= 33) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        } else {
            checkAndStartLogging()
        }
    }

    private fun checkAndStartLogging() {
        if (RxHub.status.running) {
            // 既にバックグラウンドで稼働中
            return
        }
        showStoragePickerDialog()
    }

    /**
     * 要件:
     * 「起動したらログの保存先を選ばせてください。基本的に端末かSDカードです」
     */
    private fun showStoragePickerDialog() {
        if (showingStorageDialog || isFinishing) return
        showingStorageDialog = true

        val dirs = ContextCompat.getExternalFilesDirs(this, null)
        // 共有ストレージが使えない場合はアプリ専用領域にフォールバック（選択肢が 0 にならないように）
        val internalDir = dirs.getOrNull(0) ?: filesDir
        val sdcardDir = dirs.getOrNull(1)

        val options = mutableListOf<String>()
        val targetDirs = mutableListOf<File?>() // null = 選択不可（SDカード未挿入）

        options.add(getString(R.string.storage_internal) + "\n(" + internalDir.absolutePath + ")")
        targetDirs.add(internalDir)

        if (sdcardDir != null && Environment.isExternalStorageRemovable(sdcardDir)) {
            options.add(getString(R.string.storage_sd) + "\n(" + sdcardDir.absolutePath + ")")
            targetDirs.add(sdcardDir)
        } else {
            // SDカード未挿入（選んでも端末内部へ黙って保存しないよう、選択不可として扱う）
            options.add(getString(R.string.storage_sd_missing))
            targetDirs.add(null)
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.storage_title)
            .setCancelable(false)
            .setItems(options.toTypedArray()) { _, which ->
                showingStorageDialog = false
                val selected = targetDirs[which]
                if (selected == null) {
                    Toast.makeText(this, R.string.storage_sd_missing_toast, Toast.LENGTH_LONG).show()
                    showStoragePickerDialog()
                    return@setItems
                }
                val logDir = File(selected, "s220_logs")
                RxService.start(this, logDir, soundOn = true)
            }
            .setNegativeButton(R.string.exit) { _, _ ->
                showingStorageDialog = false
                finish()
            }
            .show()
    }

    private fun showStorageInfo() {
        val logDir = RxHub.status.logDir ?: "未設定"
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_files)
            .setMessage("ログ保存先フォルダ:\n$logDir\n\nPCとUSB接続してMTPまたはファイル管理アプリで取り出せます。")
            .setPositiveButton("OK", null)
            .show()
    }

    // =========================================================================
    // RxHub リスナー
    // =========================================================================
    private fun restoreState() {
        val snapshot = RxHub.snapshot()
        adapter.setItems(snapshot)
        updateEmptyView()
        onStatus(RxHub.status)
        if (followLatest) scrollToBottom()
    }

    override fun onEntry(entry: LogEntry, removedFromHead: Int) {
        adapter.add(entry, removedFromHead)
        updateEmptyView()
        if (followLatest) {
            scrollToBottom()
        }
    }

    override fun onStatus(status: RxHub.Status) {
        // USB ステータス
        b.tvUsbStatus.text = status.usbText
        b.tvUsbStatus.setTextColor(
            ContextCompat.getColor(
                this,
                if (status.usbConnected) R.color.ok else R.color.warn
            )
        )

        // GPS ステータス
        b.tvGpsStatus.text = status.gpsText
        b.tvGpsStatus.setTextColor(
            ContextCompat.getColor(
                this,
                if (status.gpsOk) R.color.ok else R.color.text_secondary
            )
        )

        // 統計行
        b.tvStatRx.text = "RX: ${status.total}"
        b.tvStatLost.text = "欠落: ${status.lost}"
        b.tvStatRssi.text = when {
            status.rssiLast != null -> {
                val avg = status.rssiAvg?.let { String.format(Locale.US, "%.0f", it) } ?: "--"
                "RSSI: ${status.rssiLast} (均$avg)"
            }
            else -> "RSSI: ---"
        }
        b.tvStatGpx.text = "GPX: ${status.gpxPoints}点"
    }

    override fun onCleared() {
        adapter.clear()
        updateEmptyView()
    }

    private fun updateEmptyView() {
        b.tvEmpty.visibility = if (adapter.itemCount == 0) View.VISIBLE else View.GONE
    }
}