package com.lispace.wzryncauto.automation

import android.content.res.AssetManager
import android.graphics.BitmapFactory
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** The position of a close image found in one freshly captured frame. */
data class PopupCloseTarget(
    val centerX: Int,
    val centerY: Int,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val score: Double,
    val templateName: String,
    val correlation: Double = 0.0,
    val foregroundRatio: Double = 0.0,
    val popupContextScore: Double = 0.0,
)

/**
 * The game keeps most landscape UI inside a centered 16:9 safe area on
 * ultra-wide phones. Tablets and ordinary landscape screens keep the legacy
 * full-frame coordinate system so their verified matching behavior does not
 * change.
 */
internal data class GameUiViewport(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
    val horizontallyInset: Boolean,
) {
    fun normalizedX(x: Int): Double = (x - left).toDouble() / width

    fun normalizedY(y: Int): Double = (y - top).toDouble() / height

    companion object {
        fun forLandscape(width: Int, height: Int): GameUiViewport {
            require(width > height && height > 0) {
                "Invalid landscape resolution: ${width}x$height"
            }
            val safeWidth = (height * GAME_SAFE_ASPECT_RATIO).roundToInt()
            return if (width > safeWidth) {
                GameUiViewport(
                    left = (width - safeWidth) / 2,
                    top = 0,
                    width = safeWidth,
                    height = height,
                    horizontallyInset = true,
                )
            } else {
                GameUiViewport(
                    left = 0,
                    top = 0,
                    width = width,
                    height = height,
                    horizontallyInset = false,
                )
            }
        }

        private const val GAME_SAFE_ASPECT_RATIO = 16.0 / 9.0
    }
}

internal data class PopupCloseSearchBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

internal fun popupCloseSearchBounds(width: Int, height: Int): PopupCloseSearchBounds {
    val viewport = GameUiViewport.forLandscape(width, height)
    val topRatio = if (viewport.horizontallyInset) {
        ULTRAWIDE_ROI_TOP
    } else {
        ROI_TOP
    }
    return PopupCloseSearchBounds(
        left = viewport.left + (viewport.width * ROI_LEFT).roundToInt(),
        top = viewport.top + (viewport.height * topRatio).roundToInt(),
        right = viewport.left + (viewport.width * ROI_RIGHT).roundToInt(),
        bottom = viewport.top + (viewport.height * ROI_BOTTOM).roundToInt(),
    )
}

/**
 * Restricts close-image candidates to a layout that has already been proven
 * by OCR. The update announcement introduced on 2026-08-27 places its close
 * icon noticeably farther left than the historical promotional modals.
 */
enum class PopupCloseSearchProfile(
    private val centerXMin: Double,
    private val centerXMax: Double,
) {
    DEFAULT(0.90, 0.95),
    UPDATE_ANNOUNCEMENT(0.83, 0.89),
    ;

    fun accepts(target: PopupCloseTarget): Boolean {
        if (target.sourceWidth <= 0 || target.sourceHeight <= 0) return false
        if (target.sourceWidth <= target.sourceHeight) return false
        val viewport = GameUiViewport.forLandscape(target.sourceWidth, target.sourceHeight)
        val xRatio = viewport.normalizedX(target.centerX)
        val yRatio = viewport.normalizedY(target.centerY)
        val centerYMin = if (viewport.horizontallyInset) {
            ULTRAWIDE_CLOSE_CENTER_Y_MIN
        } else {
            CLOSE_CENTER_Y_MIN
        }
        return xRatio in centerXMin..centerXMax &&
            yRatio in centerYMin..CLOSE_CENTER_Y_MAX
    }

    companion object {
        private const val CLOSE_CENTER_Y_MIN = 0.14
        private const val ULTRAWIDE_CLOSE_CENTER_Y_MIN = 0.10
        private const val CLOSE_CENTER_Y_MAX = 0.34

        fun forPopupPhrase(phrase: String): PopupCloseSearchProfile =
            if (phrase.contains("更新公告") || phrase.contains("版本更新")) {
                UPDATE_ANNOUNCEMENT
            } else {
                DEFAULT
            }
    }
}

/**
 * Locates the close icon in the upper-right modal area.
 *
 * The icon is matched against the full grayscale template, its foreground
 * ratio, and its local contrast rather than against bright pixels alone.
 * Several historical templates and a small range of scales are used because
 * the game changes the ad artwork and close-button size. A dimmed-backdrop
 * score is also required before the result can be treated as an ad.
 */
class PopupCloseTemplateMatcher(
    private val assets: AssetManager,
) {
    private val fallbackTemplates: List<Template> by lazy {
        loadTemplates(FALLBACK_TEMPLATE_ASSETS)
    }
    private val resolutionTemplateCache = mutableMapOf<Pair<Int, Int>, List<Template>>()

    fun find(
        encoded: ByteArray,
        sourceWidth: Int,
        sourceHeight: Int,
        profile: PopupCloseSearchProfile = PopupCloseSearchProfile.DEFAULT,
    ): PopupCloseTarget? {
        if (sourceWidth <= sourceHeight || sourceWidth <= 0 || sourceHeight <= 0) {
            return null
        }
        val bitmap = BitmapFactory.decodeByteArray(encoded, 0, encoded.size) ?: return null
        return try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            templatesFor(bitmap.width, bitmap.height).asSequence()
                .flatMap { template ->
                    matchTemplate(template, pixels, bitmap.width, bitmap.height).asSequence()
                }
                // Filter by the OCR-proven layout before selecting the best
                // score. Selecting globally first allowed a high-scoring
                // right-side lobby control to beat the real announcement X.
                .filter(profile::accepts)
                .filter {
                    it.score >= MATCH_THRESHOLD &&
                        it.correlation >= MIN_CORRELATION &&
                        it.foregroundRatio >= MIN_FOREGROUND_RATIO &&
                        it.popupContextScore >= MIN_POPUP_CONTEXT_SCORE
                }
                .maxByOrNull(PopupCloseTarget::score)
        } finally {
            bitmap.recycle()
        }
    }

    private fun matchTemplate(
        template: Template,
        pixels: IntArray,
        width: Int,
        height: Int,
    ): List<PopupCloseTarget> {
        val searchBounds = popupCloseSearchBounds(width, height)
        val roiLeft = searchBounds.left
        val roiTop = searchBounds.top
        val roiRight = searchBounds.right
        val roiBottom = searchBounds.bottom
        val resolutionScale = width / template.referenceWidth.toFloat()
        val contextScore = popupContextScore(pixels, width, height)
        val results = mutableListOf<PopupCloseTarget>()
        for (scale in SCALE_FACTORS) {
            val patchWidth = (template.width * scale * resolutionScale).roundToInt()
            val patchHeight = (template.height * scale * resolutionScale).roundToInt()
            if (patchWidth !in MIN_PATCH_SIZE..MAX_PATCH_SIZE ||
                patchHeight !in MIN_PATCH_SIZE..MAX_PATCH_SIZE
            ) {
                continue
            }
            val maxX = min(roiRight - patchWidth, width - patchWidth)
            val maxY = min(roiBottom - patchHeight, height - patchHeight)
            if (maxX < roiLeft || maxY < roiTop) continue
            var bestForScale: PopupCloseTarget? = null
            var y = roiTop
            while (y <= maxY) {
                var x = roiLeft
                while (x <= maxX) {
                    val quality = scoreAt(template, pixels, width, x, y, patchWidth, patchHeight)
                    if (quality != null && quality.score >= (bestForScale?.score ?: 0.0)) {
                        bestForScale = PopupCloseTarget(
                            centerX = x + patchWidth / 2,
                            centerY = y + patchHeight / 2,
                            sourceWidth = width,
                            sourceHeight = height,
                            score = quality.score,
                            templateName = template.assetName,
                            correlation = quality.correlation,
                            foregroundRatio = quality.foregroundRatio,
                            popupContextScore = contextScore,
                        )
                    }
                    x += SEARCH_STEP
                }
                y += SEARCH_STEP
            }
            bestForScale?.let(results::add)
        }
        return results
    }

    private fun scoreAt(
        template: Template,
        pixels: IntArray,
        sourceWidth: Int,
        left: Int,
        top: Int,
        patchWidth: Int,
        patchHeight: Int,
    ): MatchQuality? {
        var sumCandidate = 0.0
        var sumCandidateSquared = 0.0
        var sumTemplateCandidate = 0.0
        var foregroundHits = 0
        var foregroundTotal = 0
        var foregroundLuminance = 0.0
        var backgroundLuminance = 0.0
        var backgroundTotal = 0
        template.samples.forEach { sample ->
            val x = left + (sample.x * (patchWidth - 1)).roundToInt()
            val y = top + (sample.y * (patchHeight - 1)).roundToInt()
            val luminance = luminance(pixels[y * sourceWidth + x])
            sumCandidate += luminance
            sumCandidateSquared += luminance * luminance
            sumTemplateCandidate += sample.luminance * luminance
            if (sample.foreground) {
                foregroundTotal += 1
                foregroundLuminance += luminance
                if (luminance >= FOREGROUND_LUMINANCE) {
                    foregroundHits += 1
                }
            } else {
                backgroundTotal += 1
                backgroundLuminance += luminance
            }
        }
        val sampleCount = template.samples.size.toDouble()
        if (foregroundTotal == 0 || backgroundTotal == 0 || sampleCount <= 1) return null
        val covariance = sampleCount * sumTemplateCandidate -
            template.sumLuminance * sumCandidate
        val candidateVariance = sampleCount * sumCandidateSquared -
            sumCandidate * sumCandidate
        val denominator = sqrt(template.variance * candidateVariance)
        if (denominator <= 0.0) return null
        val correlation = (covariance / denominator).coerceIn(-1.0, 1.0)
        val foregroundRatio = foregroundHits.toDouble() / foregroundTotal
        val foregroundMean = foregroundLuminance / foregroundTotal
        val backgroundMean = backgroundLuminance / backgroundTotal
        val contrast = ((foregroundMean - backgroundMean) / 255.0).coerceIn(0.0, 1.0)
        if (
            correlation < MIN_CORRELATION ||
            foregroundRatio < MIN_FOREGROUND_RATIO ||
            contrast < MIN_FOREGROUND_CONTRAST
        ) {
            return null
        }
        val score = correlation * CORRELATION_WEIGHT +
            foregroundRatio * FOREGROUND_WEIGHT +
            contrast * CONTRAST_WEIGHT
        return MatchQuality(score, correlation, foregroundRatio)
    }

    private fun popupContextScore(
        pixels: IntArray,
        width: Int,
        height: Int,
    ): Double {
        val center = regionMean(pixels, width, height, 0.22f, 0.18f, 0.78f, 0.82f)
        val edge = listOf(
            regionMean(pixels, width, height, 0.03f, 0.15f, 0.18f, 0.85f),
            regionMean(pixels, width, height, 0.82f, 0.15f, 0.97f, 0.85f),
            regionMean(pixels, width, height, 0.18f, 0.08f, 0.82f, 0.16f),
            regionMean(pixels, width, height, 0.18f, 0.84f, 0.82f, 0.94f),
        ).average()
        return ((center - edge) / 255.0).coerceIn(0.0, 1.0)
    }

    private fun regionMean(
        pixels: IntArray,
        width: Int,
        height: Int,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ): Double {
        val startX = (width * left).roundToInt()
        val endX = (width * right).roundToInt().coerceAtMost(width)
        val startY = (height * top).roundToInt()
        val endY = (height * bottom).roundToInt().coerceAtMost(height)
        val stepX = max(8, width / 120)
        val stepY = max(8, height / 80)
        var sum = 0.0
        var count = 0
        var y = startY
        while (y < endY) {
            var x = startX
            while (x < endX) {
                sum += luminance(pixels[y * width + x])
                count += 1
                x += stepX
            }
            y += stepY
        }
        return if (count == 0) 0.0 else sum / count
    }

    private fun templatesFor(width: Int, height: Int): List<Template> = synchronized(
        resolutionTemplateCache,
    ) {
        resolutionTemplateCache.getOrPut(width to height) {
            val directory = "${width}x$height"
            val exactAssets = runCatching {
                assets.list(directory).orEmpty()
                    .filter { fileName ->
                        fileName.startsWith("close_popup") && fileName.endsWith(".png")
                    }
                    .sorted()
                    .map { fileName -> "$directory/$fileName" }
            }.getOrDefault(emptyList())
            val exactTemplates = loadTemplates(exactAssets)
            (exactTemplates + fallbackTemplates).distinctBy(Template::assetName)
        }
    }

    private fun loadTemplates(assetNames: List<String>): List<Template> =
        assetNames.mapNotNull { assetName ->
            runCatching {
                assets.open(assetName).use { input ->
                    BitmapFactory.decodeStream(input)
                }
            }.getOrNull()?.let { bitmap ->
                try {
                    val samples = mutableListOf<Sample>()
                    val pixels = IntArray(bitmap.width * bitmap.height)
                    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                    val sampleStep = maxOf(
                        MIN_TEMPLATE_SAMPLE_STEP,
                        max(bitmap.width, bitmap.height) / TEMPLATE_SAMPLE_GRID,
                    )
                    for (y in 0 until bitmap.height step sampleStep) {
                        for (x in 0 until bitmap.width step sampleStep) {
                            val value = luminance(pixels[y * bitmap.width + x])
                            samples += Sample(
                                x = x.toFloat() / max(1, bitmap.width - 1),
                                y = y.toFloat() / max(1, bitmap.height - 1),
                                luminance = value,
                                foreground = value >= TEMPLATE_FOREGROUND_LUMINANCE,
                            )
                        }
                    }
                    val sumLuminance = samples.sumOf { it.luminance.toDouble() }
                    val meanLuminance = sumLuminance / samples.size
                    val variance = samples.sumOf {
                        val delta = it.luminance - meanLuminance
                        delta * delta
                    }
                    Template(
                        assetName = assetName,
                        referenceWidth = templateReferenceWidth(assetName),
                        width = bitmap.width,
                        height = bitmap.height,
                        samples = samples,
                        sumLuminance = sumLuminance,
                        variance = variance,
                    )
                } finally {
                    bitmap.recycle()
                }
            }
        }

    private data class Template(
        val assetName: String,
        val referenceWidth: Int,
        val width: Int,
        val height: Int,
        val samples: List<Sample>,
        val sumLuminance: Double,
        val variance: Double,
    )

    private data class Sample(
        val x: Float,
        val y: Float,
        val luminance: Int,
        val foreground: Boolean,
    )

    private data class MatchQuality(
        val score: Double,
        val correlation: Double,
        val foregroundRatio: Double,
    )

    private companion object {
        private const val REFERENCE_WIDTH = 2400
        private const val MIN_PATCH_SIZE = 16
        private const val MAX_PATCH_SIZE = 180
        private const val SEARCH_STEP = 3
        private const val MIN_TEMPLATE_SAMPLE_STEP = 2
        private const val TEMPLATE_SAMPLE_GRID = 8
        private const val TEMPLATE_FOREGROUND_LUMINANCE = 150
        private const val FOREGROUND_LUMINANCE = 120
        private const val MIN_CORRELATION = 0.70
        private const val MIN_FOREGROUND_RATIO = 0.72
        private const val MIN_FOREGROUND_CONTRAST = 0.08
        private const val CORRELATION_WEIGHT = 0.70
        private const val FOREGROUND_WEIGHT = 0.20
        private const val CONTRAST_WEIGHT = 0.10
        // Real announcement screenshots are JPEG/PNG-compressed and the
        // white X is rendered over a blue animated header. On the observed
        // 2560x1600 update notice the known-good close icon scores ~0.779;
        // OCR popup context, foreground ratio, contrast, and the restricted
        // top-right ROI still provide the safety checks below.
        private const val MATCH_THRESHOLD = 0.74
        private const val MIN_POPUP_CONTEXT_SCORE = 0.08
        // Legacy tablet screens still use the verified full-frame bands.
        // Ultra-wide phones normalize those same bands inside the centered
        // 16:9 game viewport so unrelated controls outside that viewport are
        // never promoted to close-button candidates.
        private val SCALE_FACTORS = floatArrayOf(
            0.65f, 0.80f, 1.0f, 1.20f, 1.50f, 1.80f, 2.20f,
        )
        private val FALLBACK_TEMPLATE_ASSETS = listOf(
            "close_popup.png",
            "2400x1080/close_popup.png",
            "2400x1080/close_popup_event.png",
        )

        private fun templateReferenceWidth(assetName: String): Int =
            assetName.substringBefore('/').substringBefore('x').toIntOrNull()
                ?: REFERENCE_WIDTH

        private fun luminance(color: Int): Int =
            (0.299 * ((color shr 16) and 0xff) +
                0.587 * ((color shr 8) and 0xff) +
                0.114 * (color and 0xff)).roundToInt()
    }
}

private const val ROI_LEFT = 0.82f
private const val ROI_TOP = 0.14f
private const val ULTRAWIDE_ROI_TOP = 0.08f
private const val ROI_RIGHT = 0.96f
private const val ROI_BOTTOM = 0.30f
