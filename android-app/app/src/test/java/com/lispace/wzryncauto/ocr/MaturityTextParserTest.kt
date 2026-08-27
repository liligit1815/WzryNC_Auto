package com.lispace.wzryncauto.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class MaturityTextParserTest {
    @Test
    fun acceptsKnownMaturityFormats() {
        val values = listOf(
            "12:00成熟" to Pair(12, 0),
            "12：00 成熟" to Pair(12, 0),
            "12点00分成熟" to Pair(12, 0),
            "明天 00：02成熟" to Pair(0, 2),
            "成熟时间 18:25 级" to Pair(18, 25),
            "葛笋 14:215成熟" to Pair(14, 21),
        )
        values.forEach { (text, expected) ->
            val reading = MaturityTextParser.parse(text) as MaturityReading.Time
            assertEquals(expected.first, reading.hour)
            assertEquals(expected.second, reading.minute)
        }
    }

    @Test
    fun preservesTodayTomorrowAndDayAfterOffsets() {
        val today = MaturityTextParser.parse("今天18:25成熟") as MaturityReading.Time
        val tomorrow = MaturityTextParser.parse("明天00:02成熟") as MaturityReading.Time
        val dayAfter = MaturityTextParser.parse("后天08:30成熟") as MaturityReading.Time

        assertEquals(0, today.dayOffset)
        assertEquals(1, tomorrow.dayOffset)
        assertEquals(2, dayAfter.dayOffset)
        assertTrue(tomorrow.tomorrowHint)
        assertTrue(dayAfter.tomorrowHint)
    }

    @Test
    fun resolvesRelativeHoursAndMinutesFromObservationTime() {
        val observedAt = LocalDateTime.of(2026, 8, 10, 23, 30)
        val reading = MaturityTextParser.parse(
            "2小时15分钟后成熟",
            observedAt,
        ) as MaturityReading.Time

        assertEquals(1, reading.hour)
        assertEquals(45, reading.minute)
        assertEquals(1, reading.dayOffset)
        assertEquals(135, reading.relativeMinutes)
        assertEquals(observedAt, reading.observedAt)
    }

    @Test
    fun recognizesHarvestableState() {
        assertTrue(MaturityTextParser.parse("当前作物 可收获") is MaturityReading.Mature)
        assertTrue(MaturityTextParser.parse("作物已成熟") is MaturityReading.Mature)
    }

    @Test
    fun rejectsIllegalTimes() {
        assertTrue(MaturityTextParser.parse("25:61成熟") is MaturityReading.Unrecognized)
        assertTrue(MaturityTextParser.parse("成熟时间未知") is MaturityReading.Unrecognized)
        assertTrue(MaturityTextParser.parse("聊天消息 12:30") is MaturityReading.Unrecognized)
        assertTrue(MaturityTextParser.parse("12点00分") is MaturityReading.Unrecognized)
        assertTrue(MaturityTextParser.parse("葛笋 14:215") is MaturityReading.Unrecognized)
        assertTrue(
            MaturityTextParser.parse(
                "聊天12:30 这是与土地卡无关的一长段文字 当前作物即将成熟",
            ) is MaturityReading.Unrecognized,
        )
    }
}
