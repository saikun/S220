package com.example.s220

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.s220.core.RssiScale
import com.example.s220.databinding.ItemLogRowBinding
import com.example.s220.model.EntryKind
import com.example.s220.model.Fmt
import com.example.s220.model.LogEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ログ一覧を表示する RecyclerView.Adapter。
 *
 * 画面構成:
 *   ローカル時間 ｜ 受信したBody ｜ RSSI ｜ 現在の位置情報
 */
class LogAdapter : RecyclerView.Adapter<LogAdapter.Holder>() {

    private val items = ArrayList<LogEntry>()
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun setItems(newItems: List<LogEntry>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun add(entry: LogEntry, removedFromHead: Int) {
        if (removedFromHead > 0) {
            val count = minOf(removedFromHead, items.size)
            for (i in 0 until count) items.removeAt(0)
            notifyItemRangeRemoved(0, count)
        }
        val pos = items.size
        items.add(entry)
        notifyItemInserted(pos)
    }

    fun clear() {
        val size = items.size
        items.clear()
        notifyItemRangeRemoved(0, size)
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemLogRowBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding, timeFmt)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(items[position], position)
    }

    class Holder(
        private val b: ItemLogRowBinding,
        private val timeFmt: SimpleDateFormat,
    ) : RecyclerView.ViewHolder(b.root) {

        fun bind(e: LogEntry, pos: Int) {
            val ctx = b.root.context

            // 縞模様（読みやすさ向上）
            val alt = pos % 2 == 1
            b.rowRoot.setBackgroundColor(
                ContextCompat.getColor(ctx, if (alt) R.color.row_alt else R.color.bg)
            )

            // 1. ローカル時間
            b.tvTime.text = timeFmt.format(Date(e.timeMillis))

            // 2. 受信したBody & 種別ごとの色分け
            when (e.kind) {
                EntryKind.RX -> {
                    b.tvBody.text = if (e.terminated) e.body else "${e.body} (timeout)"
                    b.tvBody.setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
                }
                EntryKind.TX -> {
                    b.tvBody.text = e.body
                    b.tvBody.setTextColor(ContextCompat.getColor(ctx, R.color.accent))
                }
                EntryKind.WARN -> {
                    b.tvBody.text = "[WARN] ${e.body}"
                    b.tvBody.setTextColor(ContextCompat.getColor(ctx, R.color.warn))
                }
                EntryKind.SYSTEM -> {
                    b.tvBody.text = "[SYS] ${e.body}"
                    b.tvBody.setTextColor(ContextCompat.getColor(ctx, R.color.sys))
                }
            }

            // 3. RSSI
            if (e.rssiDbm != null) {
                b.tvRssi.text = "${e.rssiDbm}dBm"
                b.tvNote.text = RssiScale.noteName(e.rssiDbm)
                b.tvRssi.setTextColor(ContextCompat.getColor(ctx, R.color.accent))
            } else {
                b.tvRssi.text = "---"
                b.tvNote.text = ""
                b.tvRssi.setTextColor(ContextCompat.getColor(ctx, R.color.text_dim))
            }

            // 4. 現在の位置情報
            val fix = e.fix
            if (fix != null) {
                b.tvLocation.text = "${Fmt.latLon(fix.lat)}\n${Fmt.latLon(fix.lon)}"
                b.tvLocation.setTextColor(
                    ContextCompat.getColor(ctx, if (fix.isStale) R.color.text_dim else R.color.text_secondary)
                )
            } else {
                b.tvLocation.text = "---\n---"
                b.tvLocation.setTextColor(ContextCompat.getColor(ctx, R.color.text_dim))
            }
        }
    }
}
