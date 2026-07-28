package com.lispace.wzryncauto.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MaturityTextParserTest {
    @Test
    fun acceptsKnownMaturityFormats() {
        val values = listOf(
            "12:00成熟" to Pair(12, 0),
            "12：00 成熟" to Pair(12, 0),
            "12点00分" to Pair(12, 0),
            "明天 00：02成熟" to Pair(0, 2),
            "成熟时间 18:25 级" to Pair(18, 25),
        )
        values.forEach { (text, expected) ->
            val reading = MaturityTextParser.parse(text) as MaturityReading.Time
            assertEquals(expected.first, reading.hour)
            assertEquals(expected.second, reading.minute)
        }
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
    }
}
