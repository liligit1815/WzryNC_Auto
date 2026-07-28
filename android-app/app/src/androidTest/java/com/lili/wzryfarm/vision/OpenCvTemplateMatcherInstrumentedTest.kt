package com.lili.wzryfarm.vision

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lili.wzryfarm.ocr.MaturityOcrEngine
import com.lili.wzryfarm.ocr.MaturityReading
import com.lili.wzryfarm.ocr.OcrObservation
import com.lili.wzryfarm.ocr.OcrSampleStore
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import java.io.File
import java.time.LocalDateTime

@RunWith(AndroidJUnit4::class)
class OpenCvTemplateMatcherInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val matcher = OpenCvTemplateMatcher(instrumentation.targetContext.assets)

    @Test
    fun matchesPythonPositiveSamplesWithinTolerance() {
        val samples = listOf(
            Expected("wutanchuangzhuye.png", "start_game.png", 0.891, 632, 556),
            Expected("youtanchuangdating.png", "close_popup.png", 0.990, 1188, 99),
            Expected("wutanchuangdating.png", "lainongchang.png", 0.839, 408, 468),
            Expected("nongchangzhuye.png", "refresh_pos.png", 0.856, 1184, 653),
            Expected("chufayijianwunongnongchangzhuye.png", "oneclick_farm.png", 0.940, 855, 413),
        )

        samples.forEach { expected ->
            val result = matcher.match(
                screenshotPng = sample(expected.screenshot),
                templateName = expected.template,
                screenshotId = expected.screenshot,
            ).getOrThrow()
            assertTrue("${expected.template}: ${result.score}", result.matched)
            assertTrue(abs(result.score - expected.score) <= SCORE_TOLERANCE)
            assertTrue(abs(result.centerX - expected.centerX) <= POSITION_TOLERANCE_PX)
            assertTrue(abs(result.centerY - expected.centerY) <= POSITION_TOLERANCE_PX)
            assertEquals(expected.screenshot, result.screenshotId)
        }
    }

    @Test
    fun rejectsTemplateOutsideItsRoi() {
        val result = matcher.match(
            screenshotPng = sample("nongchangzhuye.png"),
            templateName = "start_game.png",
            screenshotId = "negative-roi",
        ).getOrThrow()

        assertFalse(result.matched)
        assertTrue(result.score < result.threshold)
    }

    @Test
    fun bundledChineseOcrRunsWithoutNetworkDownload() = runBlocking {
        val engine = MaturityOcrEngine()
        try {
            val observation = engine.recognize(
                sample("juesezhanzaitudishang.png"),
            ).getOrThrow()
            assertTrue(observation.rawText.isNotBlank())
            assertEquals(1280, observation.sourceWidth)
            assertEquals(720, observation.sourceHeight)
            assertEquals(852, observation.roiWidth)
            assertEquals(480, observation.roiHeight)
        } finally {
            engine.close()
        }
    }

    @Test
    fun storesReviewableOcrSamplePair() {
        val directory = File(
            instrumentation.targetContext.cacheDir,
            "ocr-store-test-${System.nanoTime()}",
        )
        val store = OcrSampleStore(directory)
        val observation = OcrObservation(
            rawText = "12:00成熟",
            reading = MaturityReading.Time(12, 0, false, "12:00成熟"),
            sourceWidth = 2400,
            sourceHeight = 1080,
            roiWidth = 1600,
            roiHeight = 720,
        )
        val stored = store.saveMaturity(
            screenshotPng = byteArrayOf(0x01, 0x02, 0x03),
            observation = observation,
            capturedAt = LocalDateTime.of(2026, 7, 27, 12, 0),
        )
        val metadata = JSONObject(stored.metadataFile.readText())
        assertTrue(stored.imageFile.isFile)
        assertTrue(stored.metadataFile.isFile)
        assertEquals(1, stored.sampleCount)
        assertEquals("time", metadata.getString("result_type"))
        assertEquals("pending", metadata.getString("review_status"))
        assertEquals(12, metadata.getInt("hour"))
    }

    private fun sample(name: String): ByteArray =
        instrumentation.context.assets.open("screenshots/$name").use { it.readBytes() }

    private data class Expected(
        val screenshot: String,
        val template: String,
        val score: Double,
        val centerX: Int,
        val centerY: Int,
    )

    companion object {
        private const val SCORE_TOLERANCE = 0.015
        private const val POSITION_TOLERANCE_PX = 3
    }
}
