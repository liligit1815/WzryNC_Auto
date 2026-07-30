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
        assertEquals(
            78_500,
            HarvestInfoParser.parse(
                listOf(
                    item("7.85万", 100f, 100f),
                    item("农场经验", 100f, 180f),
                ),
            )?.experience,
        )
        assertEquals(
            78_500,
            HarvestInfoParser.parse(
                listOf(
                    item("T:85万", 100f, 100f),
                    item("农场经验", 100f, 180f),
                ),
            )?.experience,
        )
        assertEquals(
            78_500,
            HarvestInfoParser.parse(
                listOf(
                    item("T85万", 100f, 100f),
                    item("农场经验", 100f, 180f),
                ),
            )?.experience,
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
    fun sumsRepeatedBlueberryCardsAndIgnoresDistantBadgeNumbers() {
        val result = HarvestInfoParser.parse(
            listOf(
                item("7.85万", 100f, 100f),
                item("农场经验", 100f, 180f),
                item("1", 300f, 100f),
                item("44", 480f, 220f),
                item("蓝莓", 300f, 300f),
                item("67", 500f, 220f),
                item("蓝莓", 500f, 300f),
                item("2", 700f, 100f),
                item("13", 700f, 220f),
                item("蓝莓", 700f, 300f),
                item("15", 900f, 220f),
                item("蓝莓", 900f, 300f),
                item("15", 300f, 420f),
                item("蓝莓", 300f, 500f),
            ),
        )

        assertEquals(78_500, result?.experience)
        assertEquals(mapOf("蓝莓" to 154), result?.crops)
    }

    @Test
    fun parsesFarmExperienceAndNormalizesCarrotOcrAlias() {
        val result = HarvestInfoParser.parse(
            listOf(
                item("66", 100f, 100f),
                item("农场经验", 100f, 180f),
                item("45", 300f, 220f),
                item("胡萝ト", 200f, 300f),
                item("10", 500f, 220f),
                item("胡萝ト", 400f, 300f),
            ),
        )

        assertEquals(66, result?.experience)
        assertEquals(mapOf("胡萝卜" to 55), result?.crops)
    }

    @Test
    fun discoversUnregisteredCropFromCardLayout() {
        val result = HarvestInfoParser.parse(
            listOf(
                item("123", 100f, 100f),
                item("农场经验", 100f, 180f),
                item("18", 350f, 220f),
                item("紫甘蓝", 350f, 300f),
                item("27", 650f, 220f),
                item("紫甘蓝", 650f, 300f),
            ),
        )

        assertEquals(123, result?.experience)
        assertEquals(mapOf("紫甘蓝" to 45), result?.crops)
    }

    @Test
    fun preservesUnregisteredCropWithMinorOcrGlyphError() {
        val result = HarvestInfoParser.parse(
            listOf(
                item("9", 350f, 220f),
                item("紫甘ト", 350f, 300f),
            ),
        )

        assertEquals(mapOf("紫甘ト" to 9), result?.crops)
    }

    @Test
    fun ignoresUiCopyAndDoesNotReuseExperienceNumberAsUnknownCrop() {
        val result = HarvestInfoParser.parse(
            listOf(
                item("66", 100f, 100f),
                item("农场经验", 100f, 180f),
                item("恭喜您获得", 120f, 260f),
                item("点击继续", 300f, 360f),
            ),
        )

        assertEquals(66, result?.experience)
        assertEquals(emptyMap<String, Int>(), result?.crops)
    }

    @Test
    fun returnsNullForUnrelatedText() {
        assertNull(HarvestInfoParser.parse(listOf(item("继续", 10f, 10f))))
    }

    private fun item(text: String, x: Float, y: Float) = OcrTextItem(text, x, y)
}
