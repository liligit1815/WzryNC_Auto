package com.lili.wzryfarm.ocr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

data class OcrObservation(
    val rawText: String,
    val reading: MaturityReading,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val roiWidth: Int,
    val roiHeight: Int,
)

data class HarvestOcrObservation(
    val rawText: String,
    val parsed: HarvestInfo?,
    val items: List<OcrTextItem>,
)

class MaturityOcrEngine(
    private val recognizer: TextRecognizer = TextRecognition.getClient(
        ChineseTextRecognizerOptions.Builder().build(),
    ),
) : AutoCloseable {
    suspend fun recognize(screenshotPng: ByteArray): Result<OcrObservation> {
        val source = BitmapFactory.decodeByteArray(screenshotPng, 0, screenshotPng.size)
            ?: return Result.failure(IllegalArgumentException("Screenshot cannot be decoded"))
        val prepared = runCatching { prepareMaturityRoi(source) }
            .getOrElse {
                source.recycle()
                return Result.failure(it)
            }
        val sourceWidth = source.width
        val sourceHeight = source.height
        source.recycle()
        return try {
            val text = process(InputImage.fromBitmap(prepared, 0))
            val raw = text.textBlocks
                .flatMap { block -> block.lines }
                .joinToString(" ") { line -> line.text }
                .ifBlank { text.text }
            Result.success(
                OcrObservation(
                    rawText = raw,
                    reading = MaturityTextParser.parse(raw),
                    sourceWidth = sourceWidth,
                    sourceHeight = sourceHeight,
                    roiWidth = prepared.width,
                    roiHeight = prepared.height,
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Result.failure(error)
        } finally {
            prepared.recycle()
        }
    }

    suspend fun recognizeHarvest(screenshotPng: ByteArray): Result<HarvestOcrObservation> {
        val source = BitmapFactory.decodeByteArray(screenshotPng, 0, screenshotPng.size)
            ?: return Result.failure(IllegalArgumentException("Screenshot cannot be decoded"))
        val prepared = runCatching { prepareHarvestRoi(source) }
            .getOrElse {
                source.recycle()
                return Result.failure(it)
            }
        source.recycle()
        return try {
            val text = process(InputImage.fromBitmap(prepared, 0))
            val items = text.textBlocks.flatMap { block ->
                block.lines.mapNotNull { line ->
                    line.boundingBox?.let { box ->
                        OcrTextItem(
                            text = line.text,
                            centerX = box.exactCenterX(),
                            centerY = box.exactCenterY(),
                        )
                    }
                }
            }
            Result.success(
                HarvestOcrObservation(
                    rawText = items.joinToString(" ") { it.text },
                    parsed = HarvestInfoParser.parse(items),
                    items = items,
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Result.failure(error)
        } finally {
            prepared.recycle()
        }
    }

    override fun close() {
        recognizer.close()
    }

    private suspend fun process(image: InputImage) =
        suspendCancellableCoroutine { continuation ->
            recognizer.process(image)
                .addOnSuccessListener { result ->
                    if (continuation.isActive) continuation.resume(result)
                }
                .addOnFailureListener { error ->
                    if (continuation.isActive) {
                        continuation.resumeWith(Result.failure(error))
                    }
                }
        }

    companion object {
        /**
         * Python ROI: x=0..w/3, y=h/3..2h/3. A 2x grayscale/contrast pass
         * improves small white game text while keeping the bundled OCR offline.
         */
        fun prepareMaturityRoi(source: Bitmap): Bitmap {
            require(source.width >= 6 && source.height >= 6)
            val crop = Bitmap.createBitmap(
                source,
                0,
                source.height / 3,
                source.width / 3,
                source.height / 3,
            )
            val output = Bitmap.createBitmap(
                crop.width * 2,
                crop.height * 2,
                Bitmap.Config.ARGB_8888,
            )
            val contrast = 1.35f
            val translate = (-0.5f * contrast + 0.5f) * 255f
            val matrix = ColorMatrix().apply {
                setSaturation(0f)
                postConcat(
                    ColorMatrix(
                        floatArrayOf(
                            contrast, 0f, 0f, 0f, translate,
                            0f, contrast, 0f, 0f, translate,
                            0f, 0f, contrast, 0f, translate,
                            0f, 0f, 0f, 1f, 0f,
                        ),
                    ),
                )
            }
            Canvas(output).drawBitmap(
                crop,
                null,
                android.graphics.Rect(0, 0, output.width, output.height),
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                    colorFilter = ColorMatrixColorFilter(matrix)
                },
            )
            crop.recycle()
            return output
        }

        fun prepareHarvestRoi(source: Bitmap): Bitmap {
            require(source.width >= 10 && source.height >= 10)
            val left = (source.width * 0.15).toInt()
            val top = (source.height * 0.15).toInt()
            val width = (source.width * 0.70).toInt()
            val height = (source.height * 0.77).toInt()
            val crop = Bitmap.createBitmap(source, left, top, width, height)
            return Bitmap.createScaledBitmap(
                crop,
                crop.width * 2,
                crop.height * 2,
                true,
            ).also {
                if (it !== crop) crop.recycle()
            }
        }
    }
}
