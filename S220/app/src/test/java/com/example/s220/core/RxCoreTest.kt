package com.example.s220.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RxCoreTest {

    @Test
    fun testLineFramer() {
        var simulatedTime = 1000L
        val framer = LineFramer(idleFlushMs = 500L, clock = { simulatedTime })

        // 1. 通常の改行でフレーム化
        val f1 = framer.feed("LINE1\r\nLINE2\n".toByteArray(Charsets.UTF_8))
        assertEquals(2, f1.size)
        assertEquals("LINE1", String(f1[0], Charsets.UTF_8))
        assertEquals("LINE2", String(f1[1], Charsets.UTF_8))

        // 2. 途中で切れたデータ
        val f2 = framer.feed("PARTIAL".toByteArray(Charsets.UTF_8))
        assertEquals(0, f2.size)

        // タイムアウト前
        simulatedTime += 200L
        assertNull(framer.flushIfIdle())

        // タイムアウト後
        simulatedTime += 400L // 合計 600ms 経過
        val f3 = framer.flushIfIdle()
        assertNotNull(f3)
        assertEquals("PARTIAL", String(f3!!, Charsets.UTF_8))
    }

    @Test
    fun testSplitRssi() {
        // 1. 通常のパケット: ペイロード "HELLO" + RSSI バイト 0xB5 (181) -> 181 - 256 = -75 dBm
        val rssiByte = (256 - 75).toByte() // -75dBm
        val packet1 = "HELLO".toByteArray(Charsets.UTF_8) + byteArrayOf(rssiByte)
        val (payload1, rssi1) = splitRssi(packet1)
        assertEquals("HELLO", String(payload1, Charsets.UTF_8))
        assertEquals(-75, rssi1)

        // 2. 送信側が改行で終わっており、RSSIバイトが付いていない場合 (10 - 256 = -246dBm への誤認防止)
        val packetNewline = "SEQ=1,MSG=hello\n".toByteArray(Charsets.UTF_8)
        val (payloadNl, rssiNl) = splitRssi(packetNewline)
        assertEquals("SEQ=1,MSG=hello\n", String(payloadNl, Charsets.UTF_8))
        assertNull(rssiNl)

        // 3. 送信側が改行で終わり、かつモジュールが RSSI バイトを末尾に付加した場合
        val packetWithNlAndRssi = "SEQ=1,MSG=hello\n".toByteArray(Charsets.UTF_8) + byteArrayOf(rssiByte)
        val (payload2, rssi2) = splitRssi(packetWithNlAndRssi)
        assertEquals("SEQ=1,MSG=hello\n", String(payload2, Charsets.UTF_8))
        assertEquals(-75, rssi2)

        // 4. CRLF の場合
        val packetWithCrLfAndRssi = "SEQ=1,MSG=hello\r\n".toByteArray(Charsets.UTF_8) + byteArrayOf(rssiByte)
        val (payload3, rssi3) = splitRssi(packetWithCrLfAndRssi)
        assertEquals("SEQ=1,MSG=hello\r\n", String(payload3, Charsets.UTF_8))
        assertEquals(-75, rssi3)
    }

    @Test
    fun testParseMessage() {
        val line = "SEQ=000123,TS=2026-10-15T15:00:00,MSG=Test,Data,With,Commas".toByteArray(Charsets.UTF_8)
        val map = parseMessage(line)

        assertEquals("000123", map["SEQ"])
        assertEquals("2026-10-15T15:00:00", map["TS"])
        assertEquals("Test,Data,With,Commas", map["MSG"])
    }

    @Test
    fun testRxStatistics() {
        val stats = RxStatistics()
        assertEquals(0, stats.update(1, 10))
        assertEquals(0, stats.update(2, 10))
        // 3 と 4 が抜けて 5 が届いた
        val lost = stats.update(5, 10)
        assertEquals(2, lost)
        assertEquals(2, stats.lost)
        assertEquals(3, stats.totalFrames)

        stats.updateRssi(-80)
        stats.updateRssi(-70)
        assertEquals(-80, stats.rssiMin)
        assertEquals(-70, stats.rssiMax)
        assertEquals(-75.0, stats.rssiAvg!!, 0.001)
    }

    @Test
    fun testRssiScale() {
        // -115 dBm は最小音階
        val minIdx = RssiScale.noteIndex(-120)
        assertEquals(0, minIdx)
        assertEquals("ド4", RssiScale.noteName(-120))

        // -45 dBm 以上は最高音階
        val maxIdx = RssiScale.noteIndex(-30)
        assertEquals(RssiScale.levels - 1, maxIdx)

        assertTrue(RssiScale.frequencyHz(-50) > RssiScale.frequencyHz(-100))
    }
}
