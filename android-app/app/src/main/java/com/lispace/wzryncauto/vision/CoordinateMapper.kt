package com.lispace.wzryncauto.vision

data class PixelPoint(val x: Int, val y: Int)

data class PixelBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val center: PixelPoint get() = PixelPoint(left + width / 2, top + height / 2)
}

object CoordinateMapper {
    fun roiToPixels(roi: NormalizedRoi?, width: Int, height: Int): PixelBounds {
        require(width > 0 && height > 0)
        if (roi == null) return PixelBounds(0, 0, width, height)
        return PixelBounds(
            left = (roi.left * width).toInt().coerceIn(0, width - 1),
            top = (roi.top * height).toInt().coerceIn(0, height - 1),
            right = (roi.right * width).toInt().coerceIn(1, width),
            bottom = (roi.bottom * height).toInt().coerceIn(1, height),
        )
    }

    fun isSafeTap(point: PixelPoint, width: Int, height: Int): Boolean {
        if (width <= 0 || height <= 0) return false
        val marginX = (width * 0.01).toInt().coerceAtLeast(4)
        val marginY = (height * 0.01).toInt().coerceAtLeast(4)
        return point.x in marginX until (width - marginX) &&
            point.y in marginY until (height - marginY)
    }
}
