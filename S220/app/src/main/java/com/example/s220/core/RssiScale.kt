package com.example.s220.core

import kotlin.math.floor
import kotlin.math.pow

/**
 * RSSI [dBm] を音階（ハ長調 2 オクターブ: ド4〜ド6）へ割り当てる。
 *
 *   RSSI <= -111 dBm : ド4 (261.6Hz)  … 弱い
 *   -115 dBm を基準に 5 dB 強くなるごとに 1 音上がる（-110〜-106: レ4, -105〜-101: ミ4, …）
 *   RSSI >=  -45 dBm : ド6 (1046.5Hz) … 強い
 */
object RssiScale {
    const val MIN_DBM = -115
    const val STEP_DB = 5

    private val SEMITONES = intArrayOf(0, 2, 4, 5, 7, 9, 11, 12, 14, 16, 17, 19, 21, 23, 24)
    private val NAMES = arrayOf("ド", "レ", "ミ", "ファ", "ソ", "ラ", "シ")
    private const val C4_HZ = 261.6256

    val levels: Int get() = SEMITONES.size
    val MAX_DBM: Int get() = MIN_DBM + STEP_DB * (SEMITONES.size - 1)

    fun noteIndex(rssiDbm: Int): Int {
        val idx = floor((rssiDbm - MIN_DBM).toDouble() / STEP_DB).toInt()
        return idx.coerceIn(0, SEMITONES.size - 1)
    }

    fun frequencyHz(rssiDbm: Int): Double = C4_HZ * 2.0.pow(SEMITONES[noteIndex(rssiDbm)] / 12.0)

    /** 例: "ミ5" */
    fun noteName(rssiDbm: Int): String {
        val i = noteIndex(rssiDbm)
        return NAMES[i % 7] + (4 + i / 7)
    }
}
