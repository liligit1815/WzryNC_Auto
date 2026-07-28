package com.lispace.wzryncauto.vision

import android.content.res.AssetManager
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.android.OpenCVLoader
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.util.UUID

data class TemplateMatch(
    val templateName: String,
    val score: Double,
    val centerX: Int,
    val centerY: Int,
    val bounds: PixelBounds,
    val screenshotId: String,
    val threshold: Double,
    val matched: Boolean,
    val scale: Double,
)

class OpenCvTemplateMatcher(
    private val assets: AssetManager,
) {
    init {
        check(OpenCVLoader.initLocal()) { "OpenCV native library initialization failed" }
    }

    fun match(
        screenshotPng: ByteArray,
        templateName: String,
        screenshotId: String = UUID.randomUUID().toString(),
    ): Result<TemplateMatch> = runCatching {
        val screenshot = decodeColor(screenshotPng)
        check(!screenshot.empty()) { "Screenshot cannot be decoded" }
        try {
            matchMat(screenshot, templateName, screenshotId)
        } finally {
            screenshot.release()
        }
    }

    fun annotate(screenshotPng: ByteArray, match: TemplateMatch): Result<ByteArray> =
        runCatching {
            val screenshot = decodeColor(screenshotPng)
            check(!screenshot.empty()) { "Screenshot cannot be decoded" }
            try {
                val color = if (match.matched) Scalar(0.0, 220.0, 0.0) else Scalar(0.0, 0.0, 255.0)
                Imgproc.rectangle(
                    screenshot,
                    Point(match.bounds.left.toDouble(), match.bounds.top.toDouble()),
                    Point(match.bounds.right.toDouble(), match.bounds.bottom.toDouble()),
                    color,
                    4,
                )
                Imgproc.putText(
                    screenshot,
                    "${match.templateName} %.3f/%.2f".format(match.score, match.threshold),
                    Point(match.bounds.left.toDouble(), (match.bounds.top - 12).coerceAtLeast(24).toDouble()),
                    Imgproc.FONT_HERSHEY_SIMPLEX,
                    0.8,
                    color,
                    2,
                )
                val encoded = MatOfByte()
                try {
                    check(Imgcodecs.imencode(".png", screenshot, encoded)) {
                        "Cannot encode diagnostic PNG"
                    }
                    encoded.toArray()
                } finally {
                    encoded.release()
                }
            } finally {
                screenshot.release()
            }
        }

    private fun matchMat(
        screenshot: Mat,
        templateName: String,
        screenshotId: String,
    ): TemplateMatch {
        val width = screenshot.cols()
        val height = screenshot.rows()
        val spec = TemplateCatalog.get(templateName)
        val roi = CoordinateMapper.roiToPixels(spec.roi, width, height)
        val search = screenshot.submat(roi.top, roi.bottom, roi.left, roi.right)
        try {
            var bestScore = -1.0
            var bestBounds: PixelBounds? = null
            var bestScale = 1.0

            templateCandidates(templateName, width, height).forEach { candidate ->
                val template = decodeColor(candidate.bytes)
                try {
                    TemplateCatalog.scales(width, height, candidate.resolutionSpecific)
                        .forEach { scale ->
                            val scaled = resize(template, scale)
                            try {
                                if (scaled.cols() < 5 || scaled.rows() < 5 ||
                                    scaled.cols() > search.cols() || scaled.rows() > search.rows()
                                ) return@forEach

                                val result = Mat(
                                    search.rows() - scaled.rows() + 1,
                                    search.cols() - scaled.cols() + 1,
                                    CvType.CV_32FC1,
                                )
                                try {
                                    Imgproc.matchTemplate(
                                        search,
                                        scaled,
                                        result,
                                        Imgproc.TM_CCOEFF_NORMED,
                                    )
                                    val maximum = Core.minMaxLoc(result)
                                    if (maximum.maxVal > bestScore) {
                                        bestScore = maximum.maxVal
                                        val left = maximum.maxLoc.x.toInt() + roi.left
                                        val top = maximum.maxLoc.y.toInt() + roi.top
                                        bestBounds = PixelBounds(
                                            left,
                                            top,
                                            left + scaled.cols(),
                                            top + scaled.rows(),
                                        )
                                        bestScale = scale
                                    }
                                } finally {
                                    result.release()
                                }
                            } finally {
                                if (scaled !== template) scaled.release()
                            }
                        }
                } finally {
                    template.release()
                }
            }

            val bounds = checkNotNull(bestBounds) { "Template asset not found: $templateName" }
            return TemplateMatch(
                templateName = templateName,
                score = bestScore,
                centerX = bounds.center.x,
                centerY = bounds.center.y,
                bounds = bounds,
                screenshotId = screenshotId,
                threshold = spec.threshold,
                matched = bestScore >= spec.threshold,
                scale = bestScale,
            )
        } finally {
            search.release()
        }
    }

    private fun templateCandidates(
        templateName: String,
        width: Int,
        height: Int,
    ): List<TemplateAsset> = buildList {
        readAsset("templates/${width}x${height}/$templateName")?.let {
            add(TemplateAsset(it, resolutionSpecific = true))
        }
        readAsset("templates/$templateName")?.let {
            add(TemplateAsset(it, resolutionSpecific = false))
        }
    }

    private fun readAsset(path: String): ByteArray? =
        runCatching { assets.open(path).use { it.readBytes() } }.getOrNull()

    private fun decodeColor(bytes: ByteArray): Mat {
        val encoded = MatOfByte(*bytes)
        return try {
            Imgcodecs.imdecode(encoded, Imgcodecs.IMREAD_COLOR)
        } finally {
            encoded.release()
        }
    }

    private fun resize(source: Mat, scale: Double): Mat {
        if (kotlin.math.abs(scale - 1.0) < 0.01) return source
        return Mat().also {
            Imgproc.resize(
                source,
                it,
                Size(source.cols() * scale, source.rows() * scale),
                0.0,
                0.0,
                Imgproc.INTER_LINEAR,
            )
        }
    }

    private data class TemplateAsset(
        val bytes: ByteArray,
        val resolutionSpecific: Boolean,
    )
}
