package com.lispace.wzryncauto.device

import android.graphics.BitmapFactory
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicLong
import java.util.UUID

enum class ScreenshotSource {
    STREAM,
    ROOT,
}

data class ScreenshotCaptureResult(
    val file: File,
    /** Immutable bytes for this capture; never re-read the shared current.png. */
    val encoded: ByteArray,
    val width: Int,
    val height: Int,
    val byteCount: Int,
    val attempts: Int,
    val durationMs: Long,
    val sequence: Long,
    val captureId: String,
    val source: ScreenshotSource,
    val sourceSequence: Long?,
    val capturedAtElapsedRealtimeNanos: Long,
)

internal fun eligibleStreamFrame(
    frame: StreamFrame?,
    overlayHiddenAtElapsedRealtimeNanos: Long,
): StreamFrame? = frame?.takeIf {
    it.capturedAtElapsedRealtimeNanos > overlayHiddenAtElapsedRealtimeNanos &&
        it.width > it.height
}

/**
 * Serializes screenshots, temporarily hides app overlays and only publishes a
 * fully decoded PNG. The previous current.png remains intact after failures.
 */
class SafeScreenshotCapture(
    private val provider: RootScreenshotProvider,
    private val cacheDirectory: File,
    private val hideOverlay: () -> Unit,
    private val restoreOverlay: () -> Unit,
    private val streamFrame: () -> StreamFrame? = { null },
    private val settleDelayMs: Long = DEFAULT_SETTLE_DELAY_MS,
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    private val nowElapsedRealtimeNanos: () -> Long = SystemClock::elapsedRealtimeNanos,
) {
    private val captureMutex = Mutex()
    private val publishedSequence = AtomicLong(0)
    private val captureSessionId = UUID.randomUUID().toString()

    init {
        require(settleDelayMs >= 0)
        require(maxAttempts > 0)
    }

    suspend fun capture(): Result<ScreenshotCaptureResult> = captureInternal(allowStream = true)

    /**
     * Captures a fresh lossless PNG through root even when a projection frame
     * is available. Critical OCR gates use this as a second opinion after
     * repeated semantic misses from the JPEG screen stream.
     */
    suspend fun captureRoot(): Result<ScreenshotCaptureResult> =
        captureInternal(allowStream = false)

    private suspend fun captureInternal(
        allowStream: Boolean,
    ): Result<ScreenshotCaptureResult> = captureMutex.withLock {
        val startedAt = System.nanoTime()
        try {
            hideOverlay()
            val overlayHiddenAt = nowElapsedRealtimeNanos()
            delay(settleDelayMs)
            if (allowStream) {
                eligibleStreamFrame(streamFrame(), overlayHiddenAt)?.let { frame ->
                    return@withLock publish(
                        bytes = frame.encoded,
                        width = frame.width,
                        height = frame.height,
                        attempts = 1,
                        startedAt = startedAt,
                        source = ScreenshotSource.STREAM,
                        sourceSequence = frame.sequence,
                        capturedAtElapsedRealtimeNanos = frame.capturedAtElapsedRealtimeNanos,
                    )
                }
            }
            var lastError = "unknown screenshot error"
            repeat(maxAttempts) { attemptIndex ->
                val capture = provider.capture()
                if (capture.isSuccess) {
                    val bounds = BitmapFactory.Options().apply {
                        inJustDecodeBounds = true
                    }
                    BitmapFactory.decodeByteArray(
                        capture.stdout,
                        0,
                        capture.stdout.size,
                        bounds,
                    )
                    if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                        val capturedAt = nowElapsedRealtimeNanos()
                        return@withLock publish(
                            capture.stdout,
                            bounds.outWidth,
                            bounds.outHeight,
                            attemptIndex + 1,
                            startedAt,
                            source = ScreenshotSource.ROOT,
                            sourceSequence = null,
                            capturedAtElapsedRealtimeNanos = capturedAt,
                        )
                    } else {
                        lastError = "PNG cannot be decoded"
                    }
                } else {
                    lastError = when {
                        capture.timedOut -> "screencap timed out"
                        capture.stderr.isNotBlank() -> capture.stderr.trim()
                        else -> "screencap exit code ${capture.exitCode}"
                    }
                }
                if (attemptIndex + 1 < maxAttempts) delay(RETRY_DELAY_MS)
            }
            Result.failure(IllegalStateException(lastError))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Result.failure(error)
        } finally {
            // Cancellation makes ordinary suspending cleanup unreliable. Keep
            // overlay restoration outside the cancelled job context.
            withContext(NonCancellable) {
                restoreOverlay()
            }
        }
    }

    private fun elapsedMs(startedAt: Long): Long =
        (System.nanoTime() - startedAt) / 1_000_000

    private fun publish(
        bytes: ByteArray,
        width: Int,
        height: Int,
        attempts: Int,
        startedAt: Long,
        source: ScreenshotSource,
        sourceSequence: Long?,
        capturedAtElapsedRealtimeNanos: Long,
    ): Result<ScreenshotCaptureResult> {
        cacheDirectory.mkdirs()
        val temporary = File(cacheDirectory, "current.png.tmp")
        val current = File(cacheDirectory, "current.png")
        temporary.writeBytes(bytes)
        val published = runCatching {
            Files.move(
                temporary.toPath(),
                current.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }.isSuccess
        if (!published) {
            temporary.delete()
            return Result.failure(IllegalStateException("cannot publish screenshot cache"))
        }
        val sequence = publishedSequence.incrementAndGet()
        return Result.success(
            ScreenshotCaptureResult(
                file = current,
                encoded = bytes.copyOf(),
                width = width,
                height = height,
                byteCount = bytes.size,
                attempts = attempts,
                durationMs = elapsedMs(startedAt),
                sequence = sequence,
                captureId = "$captureSessionId-$sequence",
                source = source,
                sourceSequence = sourceSequence,
                capturedAtElapsedRealtimeNanos = capturedAtElapsedRealtimeNanos,
            ),
        )
    }

    companion object {
        private const val DEFAULT_SETTLE_DELAY_MS = 180L
        private const val DEFAULT_MAX_ATTEMPTS = 3
        private const val RETRY_DELAY_MS = 250L
    }
}
