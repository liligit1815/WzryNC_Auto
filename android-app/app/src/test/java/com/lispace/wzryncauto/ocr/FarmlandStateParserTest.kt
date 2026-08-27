package com.lispace.wzryncauto.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class FarmlandStateParserTest {
    @Test
    fun recognizesEmptyFarmlandBeforeMaturityParsing() {
        val state = FarmlandStateParser.parse("农田 2级")

        assertTrue(state is FarmlandState.Empty)
        assertEquals(2, (state as FarmlandState.Empty).level)
    }

    @Test
    fun recognizesPlantedCardAndPreservesEvidence() {
        val state = FarmlandStateParser.parse(
            "变异 番茄 3 满级 0/3 1分钟后成熟 幽蓝",
            LocalDateTime.of(2026, 8, 10, 12, 0),
        )

        assertTrue(state is FarmlandState.Planted)
        state as FarmlandState.Planted
        assertEquals(1, state.maturity.relativeMinutes)
        assertEquals("0/3", state.evidence.progress)
        assertTrue("番茄" in state.evidence.tokens)
    }

    @Test
    fun recognizesMatureAndUnknownStates() {
        assertTrue(
            FarmlandStateParser.parse("当前作物 可收获") is FarmlandState.Mature,
        )
        assertTrue(
            FarmlandStateParser.parse("可升级") is FarmlandState.Unknown,
        )
    }
}
