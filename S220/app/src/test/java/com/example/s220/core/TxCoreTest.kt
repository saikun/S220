package com.example.s220.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class TxCoreTest {

    @Test
    fun testBuildPayload() {
        val t = Calendar.getInstance().apply {
            clear()
            set(2026, Calendar.OCTOBER, 15, 0, 11, 0)
            set(Calendar.MILLISECOND, 123)
        }.timeInMillis

        // tx_app.py の build_payload と同じ形式（ローカル時刻、ミリ秒まで、末尾 '\n'）
        val p = String(buildPayload(1, "TEST", t), Charsets.UTF_8)
        assertEquals("SEQ=000001,TS=2026-10-15T00:11:00.123,MSG=TEST\n", p)

        val noTs = String(buildPayload(42, "hello", t, withTimestamp = false), Charsets.UTF_8)
        assertEquals("SEQ=000042,MSG=hello\n", noTs)
    }

    @Test
    fun testPayloadRoundTripWithReceiver() {
        // 送信したペイロードを受信側の処理層がそのまま解析できること
        val frame = buildPayload(7, "TEST", 0L).let { it.copyOfRange(0, it.size - 1) } // 改行除去
        val fields = parseMessage(frame)
        assertEquals("000007", fields["SEQ"])
        assertEquals(7, fields["SEQ"]?.toIntOrNull())
        assertEquals("TEST", fields["MSG"])
        assertTrue(fields.containsKey("TS"))
    }

    @Test
    fun testBuildFixedHeader() {
        // python tx_app.py -f --address 0 --channel 0
        assertArrayEquals(byteArrayOf(0, 0, 0), buildFixedHeader(0, 0))
        assertArrayEquals(byteArrayOf(0x12, 0x34, 0x0F), buildFixedHeader(0x1234, 15))
        assertArrayEquals(byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()), buildFixedHeader(0xFFFF, 0xFF))
    }

    @Test(expected = IllegalArgumentException::class)
    fun testBuildFixedHeaderRejectsAddress() {
        buildFixedHeader(0x10000, 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testBuildFixedHeaderRejectsChannel() {
        buildFixedHeader(0, 256)
    }

    @Test
    fun testIntervals() {
        assertArrayEquals(intArrayOf(1, 2, 3, 5, 13, 34, 55), TxConfig.INTERVALS_SEC)
        assertTrue(TxConfig.DEFAULT_INTERVAL_SEC in TxConfig.INTERVALS_SEC)
        assertEquals(3, TxConfig.DEFAULT_INTERVAL_SEC)
    }

    @Test
    fun testPeriodicScheduleNoDrift() {
        var now = 1000L
        val s = PeriodicSchedule(3000L, clock = { now })

        // 送信処理に 100ms かかっても、次の送信は開始時刻 + 3000ms（ずれが溜まらない）
        now += 100
        assertEquals(2900L, s.advance())
        now += 2900 + 250 // 次の送信に 250ms 遅れて起床
        assertEquals(2750L, s.advance())
    }

    @Test
    fun testFixBurstStopsOnGoodAccuracy() {
        var now = 0L
        val s = FixBurstScheduler<String>(periodMs = 300_000, timeoutMs = 60_000, goodAccuracyM = 20f, clock = { now })

        // 起動直後に測位開始
        assertTrue(s.onTick(null) is FixDecision.Start)
        assertTrue(s.isMeasuring)

        // 古い位置（前回の測位地点）は採用しない
        now += 1000
        assertTrue(s.onTick(FixCandidate("old", ageSec = 300.0, accuracyM = 5f)) is FixDecision.Measuring)
        // 精度の悪い位置は候補として保持しつつ測位継続
        now += 1000
        assertTrue(s.onTick(FixCandidate("net", ageSec = 1.0, accuracyM = 80f)) is FixDecision.Measuring)
        // 十分な精度が得られたら即終了（GPS を止める）
        now += 1000
        val d = s.onTick(FixCandidate("gps", ageSec = 0.5, accuracyM = 8f))
        assertTrue(d is FixDecision.Done)
        assertEquals("gps", (d as FixDecision.Done).fix)
        assertTrue(!s.isMeasuring)

        // 次の測位は「前回の開始時刻 + 5 分」
        assertEquals(300_000L - 3000L, s.msUntilNext())
        now = 299_999
        assertTrue(s.onTick(null) is FixDecision.Idle)
        now = 300_000
        assertTrue(s.onTick(null) is FixDecision.Start)
    }

    @Test
    fun testFixBurstTimeout() {
        var now = 0L
        val s = FixBurstScheduler<String>(periodMs = 300_000, timeoutMs = 60_000, goodAccuracyM = 20f, clock = { now })
        assertTrue(s.onTick(null) is FixDecision.Start)

        // 屋内などで精度が足りない: 打ち切り時にそれまでの最良の位置を返す
        now += 5_000
        s.onTick(FixCandidate("net100", ageSec = 1.0, accuracyM = 100f))
        now += 5_000
        s.onTick(FixCandidate("net40", ageSec = 1.0, accuracyM = 40f))
        now += 5_000
        s.onTick(FixCandidate("net60", ageSec = 1.0, accuracyM = 60f))
        now = 60_000
        val d = s.onTick(null)
        assertEquals("net40", (d as FixDecision.Done).fix)

        // 何も取れなければ null
        now = 300_000
        assertTrue(s.onTick(null) is FixDecision.Start)
        now = 360_000
        assertEquals(null, (s.onTick(null) as FixDecision.Done).fix)
    }

    @Test
    fun testPeriodicScheduleLateAndReset() {
        var now = 0L
        val s = PeriodicSchedule(1000L, clock = { now })

        // 間隔より大幅に遅れたら、遅れ分をまとめて送らず現在時刻から数え直す
        now += 3500
        assertEquals(0L, s.advance())
        now += 10
        assertEquals(990L, s.advance())

        // 間隔変更: 直ちに送信可能（残り 0）になり、以後は新しい間隔
        s.reset(5000L)
        assertEquals(0L, s.remaining())
        assertEquals(5000L, s.advance())
        assertEquals(5000L, s.intervalMs)
    }
}
