package com.lispace.wzryncauto.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyLogDisplayFormatterTest {
    @Test
    fun `renders the requested two-line round summary`() {
        assertEquals(
            "09:31:20：第1轮开始 · 收获\n" +
                "09:33:35：执行一键务农，本次操作后作物成熟时间：08-27 10:29:00，" +
                "下次操作时间：08-27 09:51:26",
            KeyLogDisplayFormatter.render(
                listOf(
                    RoundLogEvent(1, "09:31:20", "第 1 轮开始"),
                    RoundLogEvent(1, "09:33:35", "执行一键务农"),
                    RoundLogEvent(1, "09:34:17", "本轮类型：收获"),
                    RoundLogEvent(1, "09:34:17", "成熟时间：08-27 10:29:00"),
                    RoundLogEvent(1, "09:34:17", "下次操作时间：08-27 09:51:26"),
                ),
            ),
        )
    }

    @Test
    fun `marks a non-harvest round as watering`() {
        assertEquals(
            "09:51:26：第2轮开始 · 浇水\n09:53:23：执行一键务农",
            KeyLogDisplayFormatter.render(
                listOf(
                    RoundLogEvent(2, "09:51:26", "第 2 轮开始"),
                    RoundLogEvent(2, "09:53:23", "执行一键务农"),
                    RoundLogEvent(2, "09:54:00", "本轮类型：浇水"),
                ),
            ),
        )
    }

    @Test
    fun `shows partial current round without inventing future values`() {
        assertEquals(
            "10:11:43：第3轮开始\n10:13:55：执行一键务农",
            KeyLogDisplayFormatter.render(
                listOf(
                    RoundLogEvent(3, "10:11:43", "第 3 轮开始"),
                    RoundLogEvent(3, "10:13:55", "执行一键务农"),
                ),
            ),
        )
    }

    @Test
    fun `ignores verbose runtime details in the compact log panel`() {
        assertFalse(KeyLogDisplayFormatter.isVisible("点击一键务农（1720, 980）"))
        assertFalse(KeyLogDisplayFormatter.isVisible("启动到一键务农：134秒"))
        assertEquals(
            "",
            KeyLogDisplayFormatter.render(
                listOf(RoundLogEvent(1, "09:33:35", "处理收获弹窗")),
            ),
        )
    }

    @Test
    fun `parses persisted event time and keeps failures visible`() {
        val entry = KeyLogDisplayFormatter.parsePersisted(
            "2026-08-27 09:33:35.042 执行一键务农",
        )
        assertEquals("09:33:35", entry?.time)
        assertEquals("执行一键务农", entry?.message)
        assertTrue(KeyLogDisplayFormatter.isVisible("自动化失败：入口超时"))
        assertEquals(null, KeyLogDisplayFormatter.parsePersisted("invalid line"))
    }
}
