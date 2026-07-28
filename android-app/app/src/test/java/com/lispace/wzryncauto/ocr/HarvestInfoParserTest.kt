package com.lispace.wzryncauto.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HarvestInfoParserTest {
    @Test
    fun parsesExperienceFormats() {
        assertEquals(
            80,
            HarvestInfoParser.parse(listOf(item("XP 80", 10f, 10f)))?.experience,
        )
        assertEquals(
            25,
            HarvestInfoParser.parse(listOf(item("经验＋25", 10f, 10f)))?.experience,
        )
        assertEquals(
            25,
            HarvestInfoParser.parse(
                listOf(item("经验", 100f, 100f), item("+25", 220f, 100f)),
            )?.experience,
        )
        assertEquals(
            25,
            HarvestInfoParser.parse(listOf(item("+25", 10f, 10f)))?.experience,
        )
    }

    @Test
    fun pairsEachCropWithNearestNumberAboveIt() {
        val result = HarvestInfoParser.parse(
            listOf(
                item("12", 100f, 100f),
                item("8", 300f, 110f),
                item("番茄", 105f, 180f),
                item("小麦", 295f, 190f),
            ),
        )
        assertEquals(mapOf("番茄" to 12, "小麦" to 8), result?.crops)
    }

    @Test
    fun neverReusesOneNumberForTwoCrops() {
        val result = HarvestInfoParser.parse(
            listOf(
                item("5", 100f, 100f),
                item("番茄", 90f, 170f),
                item("小麦", 110f, 180f),
            ),
        )
        assertEquals(1, result?.crops?.size)
    }

    @Test
    fun doesNotTreatPlusPrefixedExperienceAsCropQuantity() {
        val result = HarvestInfoParser.parse(
            listOf(
                item("+25", 100f, 100f),
                item("12", 105f, 110f),
                item("番茄", 105f, 180f),
            ),
        )

        assertEquals(25, result?.experience)
        assertEquals(mapOf("番茄" to 12), result?.crops)
    }

    @Test
    fun returnsNullForUnrelatedText() {
        assertNull(HarvestInfoParser.parse(listOf(item("继续", 10f, 10f))))
    }

    private fun item(text: String, x: Float, y: Float) = OcrTextItem(text, x, y)
}
