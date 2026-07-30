package com.lispace.wzryncauto.device

import android.graphics.BitmapFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

data class ScreenshotCaptureResult(
    val file: File,
    val width: Int,
    val height: Int,
    val byteCount: Int,
    val attempts: Int,
    val durationMs: Long,
)

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
) {
    private val captureMutex = Mutex()

    init {
        require(settleDelayMs >= 0)
        require(maxAttempts > 0)
    }

    suspend fun capture(): Result<ScreenshotCaptureResult> = captureMutex.withLock {
        val startedAt = System.nanoTime()
        hideOverlay()
        try {
            delay(settleDelayMs)
            streamFrame()?.let { frame ->
                return@withLock publish(
                    bytes = frame.encoded,
                    width = frame.width,
                    height = frame.height,
                    attempts = 1,
                    startedAt = startedAt,
                )
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
                        return@withLock publish(
                            capture.stdout,
                            bounds.outWidth,
                            bounds.outHeight,
                            attemptIndex + 1,
                            startedAt,
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
            restoreOverlay()
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
        return Result.success(
            ScreenshotCaptureResult(
                file = current,
                width = width,
                height = height,
                byteCount = bytes.size,
                attempts = attempts,
                durationMs = elapsedMs(startedAt),
            ),
        )
    }

    companion object {
        private const val DEFAULT_SETTLE_DELAY_MS = 180L
        private const val DEFAULT_MAX_ATTEMPTS = 3
        private const val RETRY_DELAY_MS = 250L
    }
}
