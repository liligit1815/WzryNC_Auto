package com.lispace.wzryncauto.ocr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicReference
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
    val ui: HarvestUiObservation,
)

class MaturityOcrEngine(
    private val recognizer: TextRecognizer = TextRecognition.getClient(
        ChineseTextRecognizerOptions.Builder().build(),
    ),
) : AutoCloseable {
    private val recognitionMutex = Mutex()
    private val activeRecognition = AtomicReference<Task<Text>?>(null)

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
            val text = process(prepared)
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
        }
    }

    suspend fun recognizeHarvest(screenshotPng: ByteArray): Result<HarvestOcrObservation> {
        val source = BitmapFactory.decodeByteArray(screenshotPng, 0, screenshotPng.size)
            ?: return Result.failure(IllegalArgumentException("Screenshot cannot be decoded"))
        val prepared = runCatching { prepareFullScreenOcr(source) }
            .getOrElse {
                source.recycle()
                return Result.failure(it)
            }
        val sourceWidth = source.width
        val sourceHeight = source.height
        val mapping = HarvestRoiMapping.forFullScreen(
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            ocrWidth = prepared.width,
            ocrHeight = prepared.height,
        )
        return try {
            val primaryBoxes = extractTextBoxes(process(prepared))
            val primaryUi = HarvestUiParser.parse(primaryBoxes, mapping)
            val uiBoxes = if (needsBottomActionFallback(primaryUi)) {
                val bottomPrepared = prepareBottomActionOcr(source)
                val bottomMapping = HarvestRoiMapping.forBottomActionRoi(
                    sourceWidth = sourceWidth,
                    sourceHeight = sourceHeight,
                    ocrWidth = bottomPrepared.width,
                    ocrHeight = bottomPrepared.height,
                )
                val bottomBoxes = extractTextBoxes(process(bottomPrepared)).map { box ->
                    bottomMapping.map(box).let { mapped ->
                        HarvestRoiTextBox(
                            text = mapped.text,
                            left = mapped.left.toFloat(),
                            top = mapped.top.toFloat(),
                            right = mapped.right.toFloat(),
                            bottom = mapped.bottom.toFloat(),
                        )
                    }
                }
                primaryBoxes + bottomBoxes
            } else {
                primaryBoxes
            }
            val items = primaryBoxes.map { box ->
                OcrTextItem(
                    text = box.text,
                    centerX = box.left + (box.right - box.left) / 2f,
                    centerY = box.top + (box.bottom - box.top) / 2f,
                )
            }
            val ui = if (uiBoxes === primaryBoxes) {
                primaryUi
            } else {
                HarvestUiParser.parse(uiBoxes, mapping)
            }
            Result.success(
                HarvestOcrObservation(
                    rawText = ui.rawText,
                    // Navigation uses full-screen OCR, while reward parsing is
                    // restricted to the central modal. Edge counters and farm
                    // controls otherwise resemble crop/count pairs.
                    parsed = HarvestInfoParser.parse(
                        items.filter { item ->
                            item.centerX in sourceWidth * 0.18f..sourceWidth * 0.82f &&
                                item.centerY in sourceHeight * 0.15f..sourceHeight * 0.92f
                        },
                    ),
                    items = items,
                    ui = ui,
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Result.failure(error)
        } finally {
            source.recycle()
        }
    }

    private fun extractTextBoxes(text: Text): List<HarvestRoiTextBox> =
        text.textBlocks.flatMap { block ->
            block.lines.flatMap { line ->
                val elements = line.elements.mapNotNull { element ->
                    element.boundingBox?.let { box ->
                        HarvestRoiTextBox(
                            text = element.text,
                            left = box.left.toFloat(),
                            top = box.top.toFloat(),
                            right = box.right.toFloat(),
                            bottom = box.bottom.toFloat(),
                        )
                    }
                }
                elements.ifEmpty {
                    listOfNotNull(
                        line.boundingBox?.let { box ->
                            HarvestRoiTextBox(
                                text = line.text,
                                left = box.left.toFloat(),
                                top = box.top.toFloat(),
                                right = box.right.toFloat(),
                                bottom = box.bottom.toFloat(),
                            )
                        },
                    )
                }
            }
        }

    private fun needsBottomActionFallback(ui: HarvestUiObservation): Boolean =
        ui.findTextBox("点击继续") == null &&
            listOf("恭喜您获得", "农场经验", "经验", "XP")
                .any { ui.findTextBox(it) != null }

    override fun close() {
        recognizer.close()
    }

    /**
     * ML Kit tasks keep running after coroutine cancellation. The bitmap is
     * therefore released by the task completion callback, and a timed-out task
     * blocks new OCR work until the recognizer has actually finished it.
     */
    private suspend fun process(bitmap: Bitmap): Text {
        var handedToTask = false
        try {
            return withTimeout(OCR_TIMEOUT_MS) {
                recognitionMutex.withLock {
                    check(activeRecognition.get() == null) {
                        "Previous OCR task is still completing"
                    }
                    val task = recognizer.process(InputImage.fromBitmap(bitmap, 0))
                    check(activeRecognition.compareAndSet(null, task)) {
                        "OCR task ownership changed unexpectedly"
                    }
                    handedToTask = true
                    task.addOnCompleteListener {
                        activeRecognition.compareAndSet(task, null)
                        if (!bitmap.isRecycled) bitmap.recycle()
                    }
                    awaitTask(task)
                }
            }
        } finally {
            if (!handedToTask && !bitmap.isRecycled) bitmap.recycle()
        }
    }

    private suspend fun awaitTask(task: Task<Text>): Text =
        suspendCancellableCoroutine { continuation ->
            task.addOnSuccessListener { result ->
                if (continuation.isActive) continuation.resume(result)
            }.addOnFailureListener { error ->
                if (continuation.isActive) {
                    continuation.resumeWith(Result.failure(error))
                }
            }
        }

    companion object {
        private const val OCR_TIMEOUT_MS = 15_000L
        /**
         * The crop card is vertically centered on wide phones, but moves to
         * the upper-left on 16:10 tablets. A 2x grayscale/contrast pass
         * improves small dark game text while keeping the bundled OCR offline.
         */
        fun prepareMaturityRoi(source: Bitmap): Bitmap {
            require(source.width >= 6 && source.height >= 6)
            val tabletLayout = source.height.toDouble() / source.width >= 0.55
            val left = 0
            val top = if (tabletLayout) {
                (source.height * 0.08).toInt()
            } else {
                source.height / 3
            }
            val right = if (tabletLayout) {
                (source.width * 0.35).toInt()
            } else {
                source.width / 3
            }
            val bottom = if (tabletLayout) {
                (source.height * 0.48).toInt()
            } else {
                source.height * 2 / 3
            }
            val crop = Bitmap.createBitmap(
                source,
                left,
                top,
                right - left,
                bottom - top,
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
            val roi = HarvestRoiMapping.forHarvestRoi(
                sourceWidth = source.width,
                sourceHeight = source.height,
                ocrWidth = 1,
                ocrHeight = 1,
            )
            val crop = Bitmap.createBitmap(
                source,
                roi.roiLeft,
                roi.roiTop,
                roi.roiWidth,
                roi.roiHeight,
            )
            return Bitmap.createScaledBitmap(
                crop,
                crop.width * 2,
                crop.height * 2,
                true,
            ).also {
                if (it !== crop) crop.recycle()
            }
        }

        fun prepareBottomActionOcr(source: Bitmap): Bitmap {
            require(source.width >= 10 && source.height >= 10)
            val roi = HarvestRoiMapping.forBottomActionRoi(
                sourceWidth = source.width,
                sourceHeight = source.height,
                ocrWidth = 1,
                ocrHeight = 1,
            )
            val crop = Bitmap.createBitmap(
                source,
                roi.roiLeft,
                roi.roiTop,
                roi.roiWidth,
                roi.roiHeight,
            )
            val output = Bitmap.createBitmap(
                crop.width * 2,
                crop.height * 2,
                Bitmap.Config.ARGB_8888,
            )
            val contrast = 1.45f
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

        /**
         * Navigation and farm actions use text near every screen edge, so the
         * shared UI OCR pass must preserve the complete frame. Keeping it at
         * native resolution avoids the large memory spike of scaling a
         * 2560x1600 frame to 2x while retaining enough detail for ML Kit.
         */
        fun prepareFullScreenOcr(source: Bitmap): Bitmap {
            require(source.width >= 10 && source.height >= 10)
            return Bitmap.createBitmap(
                source.width,
                source.height,
                Bitmap.Config.ARGB_8888,
            ).also { output ->
                Canvas(output).drawBitmap(
                    source,
                    0f,
                    0f,
                    Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
                )
            }
        }
    }
}
