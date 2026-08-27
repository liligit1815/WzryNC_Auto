package com.lispace.wzryncauto.ocr

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDateTime

@RunWith(AndroidJUnit4::class)
class OcrEngineInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

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
            assertEquals(896, observation.roiWidth)
            assertEquals(576, observation.roiHeight)
            assertTrue(
                observation.rawText,
                FarmlandStateParser.parse(observation.rawText) is FarmlandState.Planted,
            )
        } finally {
            engine.close()
        }
    }

    @Test
    fun fullScreenOcrFindsFarmPageAnchors() = runBlocking {
        val engine = MaturityOcrEngine()
        try {
            val observation = engine.recognizeHarvest(
                sample("nongchangzhuye.png"),
            ).getOrThrow()
            val anchors = FARM_PAGE_ANCHOR_REGIONS.mapNotNull { (label, region) ->
                observation.ui.findTextBox(label)?.also { box ->
                    assertBoxInRegion(label, box, observation.ui, region)
                }?.let { box -> label to box }
            }
            val coordinates = anchors.joinToString { (label, box) ->
                "$label=(${box.centerX},${box.centerY})"
            }

            println("nongchangzhuye OCR anchors: $coordinates")
            assertTrue(
                "Expected at least two farm-page anchors, found [$coordinates]. " +
                    "OCR=${observation.rawText}",
                anchors.size >= 2,
            )
        } finally {
            engine.close()
        }
    }

    @Test
    fun fullScreenOcrFindsSafeOneClickFarmingTargetAndContext() = runBlocking {
        val engine = MaturityOcrEngine()
        try {
            val observation = engine.recognizeHarvest(
                sample("chufayijianwunongnongchangzhuye.png"),
            ).getOrThrow()
            val oneClick = requireTextBox(observation.ui, "一键务农")
            assertBoxInRegion(
                label = "一键务农",
                box = oneClick,
                ui = observation.ui,
                region = ONE_CLICK_SAFE_REGION,
                maxWidthRatio = 0.18,
                maxHeightRatio = 0.12,
            )

            val contexts = listOf("农场升级", "流光加速").mapNotNull { label ->
                observation.ui.findTextBox(label)?.let { label to it }
            }
            val contextCoordinates = contexts.joinToString { (label, box) ->
                "$label=(${box.centerX},${box.centerY})"
            }
            println(
                "chufayijianwunongnongchangzhuye OCR: " +
                    "一键务农=(${oneClick.centerX},${oneClick.centerY}), " +
                    "context=[$contextCoordinates]",
            )
            assertTrue(
                "Expected 农场升级 or 流光加速 beside 一键务农. " +
                    "OCR=${observation.rawText}",
                contexts.isNotEmpty(),
            )
            contexts.forEach { (label, box) -> assertBoxWithinScreen(label, box, observation.ui) }
        } finally {
            engine.close()
        }
    }

    @Test
    fun fullScreenOcrFindsLegacyFarmEntryTextInSafeRegion() = runBlocking {
        val engine = MaturityOcrEngine()
        try {
            val observation = engine.recognizeHarvest(
                sample("wutanchuangdating.png"),
            ).getOrThrow()
            val farmEntry = requireTextBox(observation.ui, "来农场")

            assertBoxInRegion(
                label = "来农场",
                box = farmEntry,
                ui = observation.ui,
                region = FARM_ENTRY_SAFE_REGION,
                maxWidthRatio = 0.35,
                maxHeightRatio = 0.15,
            )
            println(
                "wutanchuangdating OCR farm entry: " +
                    "来农场=(${farmEntry.centerX},${farmEntry.centerY})",
            )
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

    private fun requireTextBox(
        ui: HarvestUiObservation,
        label: String,
    ): HarvestScreenTextBox = ui.findTextBox(label)
        ?: throw AssertionError("Expected OCR text '$label'. OCR=${ui.rawText}")

    private fun assertBoxWithinScreen(
        label: String,
        box: HarvestScreenTextBox,
        ui: HarvestUiObservation,
    ) {
        assertTrue(
            "$label has invalid OCR box $box for ${ui.sourceWidth}x${ui.sourceHeight}",
            box.left >= 0 && box.top >= 0 &&
                box.right < ui.sourceWidth && box.bottom < ui.sourceHeight &&
                box.right > box.left && box.bottom > box.top,
        )
    }

    private fun assertBoxInRegion(
        label: String,
        box: HarvestScreenTextBox,
        ui: HarvestUiObservation,
        region: NormalizedRegion,
        maxWidthRatio: Double? = null,
        maxHeightRatio: Double? = null,
    ) {
        assertBoxWithinScreen(label, box, ui)
        val left = (ui.sourceWidth * region.left).toInt()
        val top = (ui.sourceHeight * region.top).toInt()
        val rightExclusive = (ui.sourceWidth * region.right).toInt()
        val bottomExclusive = (ui.sourceHeight * region.bottom).toInt()
        val locationMessage =
            "$label center=(${box.centerX},${box.centerY}) must be inside " +
                "x=[$left,$rightExclusive), y=[$top,$bottomExclusive) on " +
                "${ui.sourceWidth}x${ui.sourceHeight}. OCR=${ui.rawText}"
        assertTrue(locationMessage, box.centerX in left until rightExclusive)
        assertTrue(locationMessage, box.centerY in top until bottomExclusive)
        maxWidthRatio?.let { ratio ->
            assertTrue(
                "$label box is too wide: $box. $locationMessage",
                box.right - box.left <= (ui.sourceWidth * ratio).toInt(),
            )
        }
        maxHeightRatio?.let { ratio ->
            assertTrue(
                "$label box is too tall: $box. $locationMessage",
                box.bottom - box.top <= (ui.sourceHeight * ratio).toInt(),
            )
        }
    }

    private data class NormalizedRegion(
        val left: Double,
        val top: Double,
        val right: Double,
        val bottom: Double,
    )

    companion object {
        // Keep these contracts aligned with the production OCR click guards.
        private val ONE_CLICK_SAFE_REGION = NormalizedRegion(0.50, 0.42, 0.80, 0.86)
        private val FARM_ENTRY_SAFE_REGION = NormalizedRegion(0.15, 0.55, 0.50, 0.86)

        private val FARM_PAGE_ANCHOR_REGIONS = linkedMapOf(
            "仓库" to NormalizedRegion(0.85, 0.08, 1.00, 0.32),
            "社交" to NormalizedRegion(0.85, 0.18, 1.00, 0.45),
            "百科" to NormalizedRegion(0.32, 0.82, 0.58, 1.00),
            "种植" to NormalizedRegion(0.72, 0.72, 1.00, 1.00),
            "对局奖励" to NormalizedRegion(0.62, 0.00, 0.88, 0.20),
        )
    }
}
