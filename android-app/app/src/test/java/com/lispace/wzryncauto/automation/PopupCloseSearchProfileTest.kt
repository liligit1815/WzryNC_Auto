package com.lispace.wzryncauto.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PopupCloseSearchProfileTest {
    @Test
    fun `update announcement accepts new left close position and rejects old false hit`() {
        val realAnnouncementClose = target(2_185, 280)
        val oldRightSideFalseHit = target(2_353, 277)

        assertTrue(PopupCloseSearchProfile.UPDATE_ANNOUNCEMENT.accepts(realAnnouncementClose))
        assertFalse(PopupCloseSearchProfile.UPDATE_ANNOUNCEMENT.accepts(oldRightSideFalseHit))
    }

    @Test
    fun `default profile keeps historical right side safety band`() {
        val realAnnouncementClose = target(2_185, 280)
        val historicalModalClose = target(2_353, 277)

        assertFalse(PopupCloseSearchProfile.DEFAULT.accepts(realAnnouncementClose))
        assertTrue(PopupCloseSearchProfile.DEFAULT.accepts(historicalModalClose))
    }

    @Test
    fun `update announcement phrase selects dedicated profile`() {
        assertEquals(
            PopupCloseSearchProfile.UPDATE_ANNOUNCEMENT,
            PopupCloseSearchProfile.forPopupPhrase("8月27日版本更新公告"),
        )
        assertEquals(
            PopupCloseSearchProfile.DEFAULT,
            PopupCloseSearchProfile.forPopupPhrase("每日充值送好礼"),
        )
    }

    @Test
    fun `device two ultra-wide announcement close is mapped inside centered game viewport`() {
        val deviceTwoClose = target(
            x = 1_877,
            y = 149,
            width = 2_400,
            height = 1_080,
        )
        val backgroundExitControl = target(
            x = 2_192,
            y = 45,
            width = 2_400,
            height = 1_080,
        )

        assertTrue(PopupCloseSearchProfile.UPDATE_ANNOUNCEMENT.accepts(deviceTwoClose))
        assertFalse(PopupCloseSearchProfile.UPDATE_ANNOUNCEMENT.accepts(backgroundExitControl))
    }

    @Test
    fun `device one search bounds preserve legacy full-frame ratios`() {
        assertEquals(
            PopupCloseSearchBounds(left = 2_099, top = 224, right = 2_458, bottom = 480),
            popupCloseSearchBounds(width = 2_560, height = 1_600),
        )
    }

    @Test
    fun `device two search bounds use centered sixteen by nine game viewport`() {
        assertEquals(
            GameUiViewport(
                left = 240,
                top = 0,
                width = 1_920,
                height = 1_080,
                horizontallyInset = true,
            ),
            GameUiViewport.forLandscape(width = 2_400, height = 1_080),
        )
        assertEquals(
            PopupCloseSearchBounds(left = 1_814, top = 86, right = 2_083, bottom = 324),
            popupCloseSearchBounds(width = 2_400, height = 1_080),
        )
    }

    private fun target(
        x: Int,
        y: Int,
        width: Int = 2_560,
        height: Int = 1_600,
    ) = PopupCloseTarget(
        centerX = x,
        centerY = y,
        sourceWidth = width,
        sourceHeight = height,
        score = 1.0,
        templateName = "test.png",
    )
}
