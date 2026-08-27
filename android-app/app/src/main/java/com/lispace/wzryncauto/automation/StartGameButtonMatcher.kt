package com.lispace.wzryncauto.automation

import android.graphics.BitmapFactory
import android.graphics.Color
import kotlin.math.max
import kotlin.math.min

/**
 * Locates the large gold start-button graphic without relying on a fixed
 * coordinate. OCR remains the primary signal; this matcher is used to recover
 * from a bad OCR bounding box on the current frame.
 */
class StartGameButtonMatcher {
    fun find(encoded: ByteArray, sourceWidth: Int, sourceHeight: Int): StartGameVisualTarget? {
        val bitmap = BitmapFactory.decodeByteArray(encoded, 0, encoded.size) ?: return null
        return try {
            if (bitmap.width != sourceWidth || bitmap.height != sourceHeight) return null
            findGoldComponent(bitmap, sourceWidth, sourceHeight)
        } finally {
            bitmap.recycle()
        }
    }

    private fun findGoldComponent(
        bitmap: android.graphics.Bitmap,
        sourceWidth: Int,
        sourceHeight: Int,
    ): StartGameVisualTarget? {
        val sampleStep = max(2, min(sourceWidth, sourceHeight) / 500)
        val left = (sourceWidth * SEARCH_LEFT).toInt()
        val right = (sourceWidth * SEARCH_RIGHT).toInt()
        val top = (sourceHeight * SEARCH_TOP).toInt()
        val bottom = (sourceHeight * SEARCH_BOTTOM).toInt()
        val gridWidth = (right - left + sampleStep - 1) / sampleStep
        val gridHeight = (bottom - top + sampleStep - 1) / sampleStep
        val mask = BooleanArray(gridWidth * gridHeight)
        val pixels = IntArray(sourceWidth * sourceHeight)
        bitmap.getPixels(pixels, 0, sourceWidth, 0, 0, sourceWidth, sourceHeight)
        val hsv = FloatArray(3)

        for (gridY in 0 until gridHeight) {
            val y = min(sourceHeight - 1, top + gridY * sampleStep)
            for (gridX in 0 until gridWidth) {
                val x = min(sourceWidth - 1, left + gridX * sampleStep)
                Color.colorToHSV(pixels[y * sourceWidth + x], hsv)
                mask[gridY * gridWidth + gridX] =
                    hsv[0] in GOLD_HUE_MIN..GOLD_HUE_MAX &&
                        hsv[1] >= GOLD_SATURATION_MIN &&
                        hsv[2] >= GOLD_VALUE_MIN
            }
        }

        val visited = BooleanArray(mask.size)
        var best: StartGameVisualTarget? = null
        val queue = IntArray(mask.size)
        for (start in mask.indices) {
            if (!mask[start] || visited[start]) continue
            var head = 0
            var tail = 0
            queue[tail++] = start
            visited[start] = true
            var count = 0
            var minX = gridWidth
            var minY = gridHeight
            var maxX = 0
            var maxY = 0
            while (head < tail) {
                val current = queue[head++]
                val gridY = current / gridWidth
                val gridX = current % gridWidth
                count += 1
                minX = min(minX, gridX)
                minY = min(minY, gridY)
                maxX = max(maxX, gridX)
                maxY = max(maxY, gridY)
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nx = gridX + dx
                        val ny = gridY + dy
                        if (nx !in 0 until gridWidth || ny !in 0 until gridHeight) continue
                        val next = ny * gridWidth + nx
                        if (mask[next] && !visited[next]) {
                            visited[next] = true
                            queue[tail++] = next
                        }
                    }
                }
            }

            val candidateLeft = left + minX * sampleStep
            val candidateTop = top + minY * sampleStep
            val candidateRight = min(
                right,
                left + (maxX + 1) * sampleStep,
            )
            val candidateBottom = min(
                bottom,
                top + (maxY + 1) * sampleStep,
            )
            val candidateWidth = candidateRight - candidateLeft
            val candidateHeight = candidateBottom - candidateTop
            val aspect = candidateWidth.toDouble() / max(1, candidateHeight)
            val area = candidateWidth.toDouble() * candidateHeight
            val normalizedWidth = candidateWidth.toDouble() / sourceWidth
            val normalizedHeight = candidateHeight.toDouble() / sourceHeight
            val centerX = (candidateLeft + candidateRight) / 2
            val centerY = (candidateTop + candidateBottom) / 2
            val normalizedCenterX = centerX.toDouble() / sourceWidth
            val normalizedCenterY = centerY.toDouble() / sourceHeight
            if (
                count < MIN_COMPONENT_SAMPLES ||
                normalizedWidth !in MIN_BUTTON_WIDTH..MAX_BUTTON_WIDTH ||
                normalizedHeight !in MIN_BUTTON_HEIGHT..MAX_BUTTON_HEIGHT ||
                aspect !in MIN_BUTTON_ASPECT..MAX_BUTTON_ASPECT ||
                normalizedCenterX !in MIN_BUTTON_CENTER_X..MAX_BUTTON_CENTER_X ||
                normalizedCenterY !in MIN_BUTTON_CENTER_Y..MAX_BUTTON_CENTER_Y
            ) continue

            val density = count.toDouble() /
                max(1, (maxX - minX + 1) * (maxY - minY + 1))
            val centerScore = 1.0 -
                min(1.0, kotlin.math.abs(normalizedCenterX - 0.5) * 2.0)
            val score = density * 0.65 + centerScore * 0.35
            val target = StartGameVisualTarget(
                centerX = centerX,
                centerY = centerY,
                left = candidateLeft,
                top = candidateTop,
                right = candidateRight,
                bottom = candidateBottom,
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight,
                score = score,
            )
            if (best == null || target.score > best.score) best = target
        }
        return best?.takeIf { it.score >= MIN_SCORE }
    }

    private companion object {
        const val SEARCH_LEFT = 0.15
        const val SEARCH_RIGHT = 0.85
        const val SEARCH_TOP = 0.55
        const val SEARCH_BOTTOM = 0.96
        const val GOLD_HUE_MIN = 18f
        const val GOLD_HUE_MAX = 95f
        const val GOLD_SATURATION_MIN = 0.25f
        const val GOLD_VALUE_MIN = 0.35f
        const val MIN_COMPONENT_SAMPLES = 300
        const val MIN_BUTTON_WIDTH = 0.18
        const val MAX_BUTTON_WIDTH = 0.55
        const val MIN_BUTTON_HEIGHT = 0.10
        const val MAX_BUTTON_HEIGHT = 0.35
        const val MIN_BUTTON_ASPECT = 1.3
        const val MAX_BUTTON_ASPECT = 4.5
        const val MIN_BUTTON_CENTER_X = 0.30
        const val MAX_BUTTON_CENTER_X = 0.70
        const val MIN_BUTTON_CENTER_Y = 0.62
        const val MAX_BUTTON_CENTER_Y = 0.90
        const val MIN_SCORE = 0.20
    }
}
