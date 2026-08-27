package com.lispace.wzryncauto.ocr

import kotlin.math.roundToInt

/** A text box in the 2x harvest OCR crop. */
data class HarvestRoiTextBox(
    val text: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

/** A clamped text box in full-screen coordinates. */
data class HarvestScreenTextBox(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val centerX: Int get() = left + (right - left) / 2
    val centerY: Int get() = top + (bottom - top) / 2
}

/** Maps ML Kit boxes from the scaled harvest crop back to the source screenshot. */
data class HarvestRoiMapping(
    val sourceWidth: Int,
    val sourceHeight: Int,
    val roiLeft: Int,
    val roiTop: Int,
    val roiWidth: Int,
    val roiHeight: Int,
    val ocrWidth: Int,
    val ocrHeight: Int,
) {
    init {
        require(sourceWidth > 0 && sourceHeight > 0)
        require(roiWidth > 0 && roiHeight > 0 && ocrWidth > 0 && ocrHeight > 0)
        require(roiLeft >= 0 && roiTop >= 0)
        require(roiLeft + roiWidth <= sourceWidth)
        require(roiTop + roiHeight <= sourceHeight)
    }

    fun map(box: HarvestRoiTextBox): HarvestScreenTextBox {
        val left = mapX(minOf(box.left, box.right))
        val right = mapX(maxOf(box.left, box.right))
        val top = mapY(minOf(box.top, box.bottom))
        val bottom = mapY(maxOf(box.top, box.bottom))
        return HarvestScreenTextBox(
            text = box.text,
            left = left,
            top = top,
            right = maxOf(left, right),
            bottom = maxOf(top, bottom),
        )
    }

    private fun mapX(value: Float): Int = (
        roiLeft + value.coerceIn(0f, ocrWidth.toFloat()) * roiWidth / ocrWidth
        ).roundToInt().coerceIn(0, sourceWidth - 1)

    private fun mapY(value: Float): Int = (
        roiTop + value.coerceIn(0f, ocrHeight.toFloat()) * roiHeight / ocrHeight
        ).roundToInt().coerceIn(0, sourceHeight - 1)

    companion object {
        fun forFullScreen(
            sourceWidth: Int,
            sourceHeight: Int,
            ocrWidth: Int,
            ocrHeight: Int,
        ): HarvestRoiMapping = HarvestRoiMapping(
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            roiLeft = 0,
            roiTop = 0,
            roiWidth = sourceWidth,
            roiHeight = sourceHeight,
            ocrWidth = ocrWidth,
            ocrHeight = ocrHeight,
        )

        fun forHarvestRoi(
            sourceWidth: Int,
            sourceHeight: Int,
            ocrWidth: Int,
            ocrHeight: Int,
        ): HarvestRoiMapping {
            val left = (sourceWidth * 0.15).toInt()
            val top = (sourceHeight * 0.15).toInt()
            val width = (sourceWidth * 0.70).toInt()
            val height = (sourceHeight * 0.77).toInt()
            return HarvestRoiMapping(
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight,
                roiLeft = left,
                roiTop = top,
                roiWidth = width,
                roiHeight = height,
                ocrWidth = ocrWidth,
                ocrHeight = ocrHeight,
            )
        }

        /**
         * High-resolution fallback crop for the low-contrast action text at
         * the bottom of harvest reward pages.
         */
        fun forBottomActionRoi(
            sourceWidth: Int,
            sourceHeight: Int,
            ocrWidth: Int,
            ocrHeight: Int,
        ): HarvestRoiMapping {
            val left = (sourceWidth * 0.20).toInt()
            val top = (sourceHeight * 0.68).toInt()
            val width = (sourceWidth * 0.60).toInt()
            val height = sourceHeight - top
            return HarvestRoiMapping(
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight,
                roiLeft = left,
                roiTop = top,
                roiWidth = width,
                roiHeight = height,
                ocrWidth = ocrWidth,
                ocrHeight = ocrHeight,
            )
        }
    }
}

sealed interface HarvestUiObservation {
    val rawText: String
    val textBoxes: List<HarvestScreenTextBox>
    val sourceWidth: Int
    val sourceHeight: Int

    data class Present(
        override val rawText: String,
        override val textBoxes: List<HarvestScreenTextBox>,
        override val sourceWidth: Int,
        override val sourceHeight: Int,
        val continueBox: HarvestScreenTextBox,
    ) : HarvestUiObservation

    data class Partial(
        override val rawText: String,
        override val textBoxes: List<HarvestScreenTextBox>,
        override val sourceWidth: Int,
        override val sourceHeight: Int,
        val hasCongratulations: Boolean,
        val hasClickToContinue: Boolean,
        val reason: String,
    ) : HarvestUiObservation

    data class Absent(
        override val rawText: String,
        override val textBoxes: List<HarvestScreenTextBox>,
        override val sourceWidth: Int,
        override val sourceHeight: Int,
    ) : HarvestUiObservation

    data class Unknown(
        override val rawText: String,
        override val textBoxes: List<HarvestScreenTextBox>,
        override val sourceWidth: Int,
        override val sourceHeight: Int,
        val reason: String,
    ) : HarvestUiObservation
}

/**
 * Finds a phrase even when ML Kit split it into several adjacent elements.
 * Returned coordinates are already mapped and clamped to the source screen.
 */
fun HarvestUiObservation.findTextBox(phrase: String): HarvestScreenTextBox? {
    val normalizedPhrase = normalizeUiText(phrase)
    if (normalizedPhrase.isEmpty()) return null
    val normalizedParts = textBoxes.map { normalizeUiText(it.text) }
    normalizedParts.indexOfFirst { normalizedPhrase in it }
        .takeIf { it >= 0 }
        ?.let { index -> return textBoxes[index].copy(text = phrase) }

    textBoxes.indices.forEach { start ->
        val matchedBoxes = mutableListOf<HarvestScreenTextBox>()
        val joined = StringBuilder()
        for (index in start until minOf(textBoxes.size, start + MAX_PHRASE_BOXES)) {
            val normalized = normalizedParts[index]
            if (normalized.isEmpty()) continue
            matchedBoxes += textBoxes[index]
            joined.append(normalized)
            if (joined.length > normalizedPhrase.length + MAX_PHRASE_EXTRA_CHARS) break
            if (normalizedPhrase in joined && boxesFormOneTextLine(matchedBoxes)) {
                return HarvestScreenTextBox(
                    text = phrase,
                    left = matchedBoxes.minOf(HarvestScreenTextBox::left),
                    top = matchedBoxes.minOf(HarvestScreenTextBox::top),
                    right = matchedBoxes.maxOf(HarvestScreenTextBox::right),
                    bottom = matchedBoxes.maxOf(HarvestScreenTextBox::bottom),
                )
            }
        }
    }
    return null
}

private fun HarvestUiObservation.boxesFormOneTextLine(
    boxes: List<HarvestScreenTextBox>,
): Boolean {
    if (boxes.size <= 1) return true
    val sorted = boxes.sortedBy(HarvestScreenTextBox::left)
    val centerYs = boxes.map(HarvestScreenTextBox::centerY)
    if (centerYs.max() - centerYs.min() > sourceHeight * MAX_LINE_VERTICAL_DRIFT) return false
    if (boxes.maxOf(HarvestScreenTextBox::right) - boxes.minOf(HarvestScreenTextBox::left) >
        sourceWidth * MAX_LINE_WIDTH
    ) return false
    return sorted.zipWithNext().all { (left, right) ->
        right.left - left.right <= sourceWidth * MAX_ELEMENT_GAP
    }
}

private const val MAX_PHRASE_BOXES = 4
private const val MAX_PHRASE_EXTRA_CHARS = 4
private const val MAX_LINE_VERTICAL_DRIFT = 0.05
private const val MAX_LINE_WIDTH = 0.35
private const val MAX_ELEMENT_GAP = 0.05

private fun normalizeUiText(text: String): String = text
    // ML Kit repeatedly reads the stylized leading “一” as a short dash in
    // the farm action button. Keep this correction phrase-specific; the
    // caller still enforces farm context, ROI and two-frame stability.
    .replace("-键务农", "一键务农")
    .replace("－键务农", "一键务农")
    .replace("—键务农", "一键务农")
    // The same outlined font can drop the first character of 对局奖励.
    .replace(Regex("""(?<![\p{IsHan}])局奖励"""), "对局奖励")
    .replace(Regex("""[\s，。！？、:：；;·•]+"""), "")
    .trim()
    .lowercase()

object HarvestUiParser {
    private const val CONGRATULATIONS = "恭喜您获得"
    private const val CLICK_TO_CONTINUE = "点击继续"

    fun parse(
        boxes: List<HarvestRoiTextBox>,
        mapping: HarvestRoiMapping,
    ): HarvestUiObservation {
        val normalizedParts = boxes.map { normalize(it.text) }
        val joined = normalizedParts.joinToString("")
        val rawText = boxes.joinToString(" ") { it.text }
        val mapped = boxes.map(mapping::map)
        val hasCongratulations = joined.contains(CONGRATULATIONS)
        val continueRange = joined.indexOf(CLICK_TO_CONTINUE)
            .takeIf { it >= 0 }
            ?.let { it until it + CLICK_TO_CONTINUE.length }
        val hasClickToContinue = continueRange != null

        if (!hasCongratulations && !hasClickToContinue) {
            return HarvestUiObservation.Absent(
                rawText,
                mapped,
                mapping.sourceWidth,
                mapping.sourceHeight,
            )
        }
        if (!hasCongratulations || continueRange == null) {
            return HarvestUiObservation.Partial(
                rawText = rawText,
                textBoxes = mapped,
                sourceWidth = mapping.sourceWidth,
                sourceHeight = mapping.sourceHeight,
                hasCongratulations = hasCongratulations,
                hasClickToContinue = hasClickToContinue,
                reason = "收获弹窗只识别到单边文字",
            )
        }

        val continueBoxes = mapped.filterIndexed { index, _ ->
            val start = normalizedParts.take(index).sumOf(String::length)
            val end = start + normalizedParts[index].length
            start < continueRange.last + 1 && end > continueRange.first
        }
        if (continueBoxes.isEmpty()) {
            return HarvestUiObservation.Unknown(
                rawText,
                mapped,
                mapping.sourceWidth,
                mapping.sourceHeight,
                "已识别点击继续，但无法定位文字框",
            )
        }
        val continueBox = HarvestScreenTextBox(
            text = CLICK_TO_CONTINUE,
            left = continueBoxes.minOf(HarvestScreenTextBox::left),
            top = continueBoxes.minOf(HarvestScreenTextBox::top),
            right = continueBoxes.maxOf(HarvestScreenTextBox::right),
            bottom = continueBoxes.maxOf(HarvestScreenTextBox::bottom),
        )
        return HarvestUiObservation.Present(
            rawText = rawText,
            textBoxes = mapped,
            sourceWidth = mapping.sourceWidth,
            sourceHeight = mapping.sourceHeight,
            continueBox = continueBox,
        )
    }

    private fun normalize(text: String): String = normalizeUiText(text)
}
