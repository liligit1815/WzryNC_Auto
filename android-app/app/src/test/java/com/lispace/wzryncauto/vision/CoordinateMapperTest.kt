package com.lispace.wzryncauto.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoordinateMapperTest {
    @Test
    fun mapsNormalizedRoiTo2400x1080Pixels() {
        val roi = TemplateCatalog.get("oneclick_farm.png").roi
        assertEquals(
            PixelBounds(1080, 378, 1920, 864),
            CoordinateMapper.roiToPixels(roi, 2400, 1080),
        )
    }

    @Test
    fun preservesPythonResolutionSpecificScales() {
        assertEquals(
            listOf(0.90, 0.95, 1.0, 1.05, 1.10),
            TemplateCatalog.scales(2400, 1080, resolutionSpecific = true),
        )
    }

    @Test
    fun validatesSafeTapMargin() {
        assertTrue(CoordinateMapper.isSafeTap(PixelPoint(1200, 540), 2400, 1080))
        assertFalse(CoordinateMapper.isSafeTap(PixelPoint(1, 1), 2400, 1080))
    }
}
