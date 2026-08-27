package com.lispace.wzryncauto.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HarvestUiParserTest {
    private val mapping = HarvestRoiMapping(
        sourceWidth = 2560,
        sourceHeight = 1600,
        roiLeft = 384,
        roiTop = 240,
        roiWidth = 1792,
        roiHeight = 1232,
        ocrWidth = 3584,
        ocrHeight = 2464,
    )

    @Test
    fun `requires both new-popup phrases and maps continue target`() {
        val result = HarvestUiParser.parse(
            boxes = listOf(
                HarvestRoiTextBox("恭喜您获得", 800f, 300f, 1500f, 420f),
                HarvestRoiTextBox("点击继续", 1500f, 1800f, 2100f, 1950f),
            ),
            mapping = mapping,
        )

        assertTrue(result is HarvestUiObservation.Present)
        result as HarvestUiObservation.Present
        assertEquals(1284, result.continueBox.centerX)
        assertEquals(1177, result.continueBox.centerY)
    }

    @Test
    fun `single phrase is partial and never absent`() {
        val result = HarvestUiParser.parse(
            boxes = listOf(
                HarvestRoiTextBox("点击继续", 1500f, 1800f, 2100f, 1950f),
            ),
            mapping = mapping,
        )

        assertTrue(result is HarvestUiObservation.Partial)
    }

    @Test
    fun `mapping clamps malformed boxes to source bounds`() {
        val mapped = mapping.map(
            HarvestRoiTextBox("点击继续", -100f, -100f, 9999f, 9999f),
        )

        assertEquals(384, mapped.left)
        assertEquals(240, mapped.top)
        assertEquals(2176, mapped.right)
        assertEquals(1472, mapped.bottom)
    }

    @Test
    fun `bottom action fallback maps enlarged OCR coordinates to the real screen`() {
        val bottomMapping = HarvestRoiMapping.forBottomActionRoi(
            sourceWidth = 2560,
            sourceHeight = 1600,
            ocrWidth = 3072,
            ocrHeight = 1024,
        )

        val mapped = bottomMapping.map(
            HarvestRoiTextBox("点击继续", 1280f, 620f, 1792f, 780f),
        )

        assertEquals(1152, mapped.left)
        assertEquals(1408, mapped.right)
        assertEquals(1438, mapped.centerY)
    }

    @Test
    fun `finds start phrase split across OCR elements`() {
        val observation = HarvestUiObservation.Absent(
            rawText = "开始 游戏",
            textBoxes = listOf(
                HarvestScreenTextBox("开始", 1000, 1100, 1120, 1160),
                HarvestScreenTextBox("游戏", 1120, 1100, 1240, 1160),
            ),
            sourceWidth = 2560,
            sourceHeight = 1600,
        )

        val target = observation.findTextBox("开始游戏")

        requireNotNull(target)
        assertEquals(1120, target.centerX)
        assertEquals(1130, target.centerY)
    }

    @Test
    fun `finds farm phrase split across OCR elements`() {
        val observation = HarvestUiObservation.Absent(
            rawText = "王者 农场",
            textBoxes = listOf(
                HarvestScreenTextBox("王者", 520, 1200, 620, 1260),
                HarvestScreenTextBox("农场", 620, 1200, 740, 1260),
            ),
            sourceWidth = 2560,
            sourceHeight = 1600,
        )

        val target = observation.findTextBox("王者农场")

        requireNotNull(target)
        assertEquals(630, target.centerX)
        assertEquals(1230, target.centerY)
    }

    @Test
    fun `finds english farm phrase without case sensitivity`() {
        val observation = HarvestUiObservation.Absent(
            rawText = "Homestead",
            textBoxes = listOf(
                HarvestScreenTextBox("Homestead", 550, 1270, 750, 1320),
            ),
            sourceWidth = 2560,
            sourceHeight = 1600,
        )

        val target = observation.findTextBox("HOMESTEAD")

        requireNotNull(target)
        assertEquals(650, target.centerX)
        assertEquals(1295, target.centerY)
    }

    @Test
    fun `does not join matching words from distant screen regions`() {
        val observation = HarvestUiObservation.Absent(
            rawText = "王者 农场",
            textBoxes = listOf(
                HarvestScreenTextBox("王者", 100, 100, 220, 160),
                HarvestScreenTextBox("农场", 2100, 1300, 2250, 1360),
            ),
            sourceWidth = 2560,
            sourceHeight = 1600,
        )

        assertNull(observation.findTextBox("王者农场"))
    }
}
