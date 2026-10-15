package com.example.s220.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.example.s220.core.RssiScale
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * 受信 RSSI に応じた音階のビープ音を鳴らす。
 *
 * 専用スレッドで再生し、再生中に次の要求が来た場合は最新の 1 件だけを残す
 * （受信が詰まっても音が溜まって遅れないようにする）。
 * 音量はメディア音量（端末の音量キー）に従う。
 */
class RssiTonePlayer {
    private val queue = LinkedBlockingDeque<Int>(1)

    @Volatile
    private var running = true

    @Volatile
    var enabled = true

    /** 音階ごとの PCM をキャッシュ */
    private val cache = HashMap<Int, ShortArray>()

    private val worker = Thread(::loop, "RssiTonePlayer").apply {
        isDaemon = true
        start()
    }

    fun play(rssiDbm: Int) {
        if (!enabled || !running) return
        synchronized(queue) {
            queue.clear()
            queue.offer(rssiDbm)
        }
    }

    fun release() {
        running = false
        worker.interrupt()
    }

    private fun loop() {
        while (running) {
            val rssi = try {
                queue.poll(1, TimeUnit.SECONDS) ?: continue
            } catch (_: InterruptedException) {
                break
            }
            try {
                playBlocking(RssiScale.noteIndex(rssi), RssiScale.frequencyHz(rssi))
            } catch (_: InterruptedException) {
                break
            } catch (e: Exception) {
                Log.w(TAG, "tone playback failed", e)
            }
        }
    }

    private fun playBlocking(noteIdx: Int, freqHz: Double) {
        val pcm = cache.getOrPut(noteIdx) { synth(freqHz) }
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()
        try {
            track.write(pcm, 0, pcm.size)
            track.play()
            Thread.sleep(TONE_MS + 30L)
            track.stop()
        } finally {
            track.release()
        }
    }

    /** 正弦波 + 立ち上がり/立ち下がりエンベロープ（クリックノイズ防止） */
    private fun synth(freqHz: Double): ShortArray {
        val n = SAMPLE_RATE * TONE_MS / 1000
        val fade = SAMPLE_RATE * FADE_MS / 1000
        return ShortArray(n) { i ->
            val env = min(1.0, min(i, n - 1 - i).toDouble() / fade)
            (sin(2 * PI * freqHz * i / SAMPLE_RATE) * env * AMPLITUDE * Short.MAX_VALUE).toInt().toShort()
        }
    }

    companion object {
        private const val TAG = "RssiTonePlayer"
        private const val SAMPLE_RATE = 44100
        private const val TONE_MS = 150
        private const val FADE_MS = 8
        private const val AMPLITUDE = 0.7
    }
}
