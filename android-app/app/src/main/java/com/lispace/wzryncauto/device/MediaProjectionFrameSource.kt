package com.lispace.wzryncauto.device

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.WindowManager
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

data class StreamFrame(
    val encoded: ByteArray,
    val width: Int,
    val height: Int,
    val capturedAtElapsedRealtimeNanos: Long,
    val sequence: Long,
    val captureId: String,
)

/**
 * Thread-safe latest-frame slot with process-lifetime monotonic identifiers.
 * Elapsed realtime is immune to wall-clock and timezone changes.
 */
internal class LatestStreamFrameStore(
    private val nowElapsedRealtimeNanos: () -> Long = SystemClock::elapsedRealtimeNanos,
) {
    private val latest = AtomicReference<StreamFrame?>()
    private val sequence = AtomicLong(0)

    fun publish(
        encoded: ByteArray,
        width: Int,
        height: Int,
        capturedAtElapsedRealtimeNanos: Long,
    ): StreamFrame {
        val next = sequence.incrementAndGet()
        return StreamFrame(
            encoded = encoded,
            width = width,
            height = height,
            capturedAtElapsedRealtimeNanos = capturedAtElapsedRealtimeNanos,
            sequence = next,
            captureId = "stream-$next",
        ).also(latest::set)
    }

    fun latest(maxAgeMs: Long): StreamFrame? {
        require(maxAgeMs >= 0) { "maxAgeMs must not be negative" }
        val now = nowElapsedRealtimeNanos()
        val maxAgeNanos = maxAgeMs * NANOS_PER_MILLISECOND
        return latest.get()?.takeIf { frame ->
            val ageNanos = now - frame.capturedAtElapsedRealtimeNanos
            ageNanos in 0..maxAgeNanos
        }
    }

    fun clear() {
        latest.set(null)
    }

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}

/**
 * Keeps only the newest display frame. Consumers never wait for PNG capture or
 * a root process; if the projection is unavailable they can use screencap.
 */
class MediaProjectionFrameSource(private val context: Context) : AutoCloseable {
    private val frames = LatestStreamFrameStore()
    private val sessionGeneration = AtomicLong(0)
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var thread: HandlerThread? = null

    @Synchronized
    fun start(resultCode: Int, data: Intent) {
        close()
        val session = sessionGeneration.incrementAndGet()
        val windowManager = context.getSystemService(WindowManager::class.java)
        val (width, height, density) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.maximumWindowMetrics.bounds
            Triple(
                bounds.width(),
                bounds.height(),
                context.resources.displayMetrics.densityDpi,
            )
        } else {
            @Suppress("DEPRECATION")
            val metrics = DisplayMetrics().also(windowManager.defaultDisplay::getRealMetrics)
            Triple(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
        }
        val handlerThread = HandlerThread("screen-frame-stream").apply { start() }
        val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        val manager = context.getSystemService(MediaProjectionManager::class.java)
        val mediaProjection = manager.getMediaProjection(resultCode, data)

        thread = handlerThread
        reader = imageReader
        projection = mediaProjection
        imageReader.setOnImageAvailableListener({ source ->
            val image = source.acquireLatestImage() ?: return@setOnImageAvailableListener
            // Record acquisition before expensive bitmap conversion. Consumers
            // can then reject a frame that predates hiding the overlay even if
            // JPEG encoding finishes later.
            val capturedAt = SystemClock.elapsedRealtimeNanos()
            try {
                val plane = image.planes.first()
                val rowPadding = plane.rowStride - plane.pixelStride * width
                val paddedWidth = width + rowPadding / plane.pixelStride
                val padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
                padded.copyPixelsFromBuffer(plane.buffer)
                val visible = if (paddedWidth == width) {
                    padded
                } else {
                    Bitmap.createBitmap(padded, 0, 0, width, height)
                }
                val output = ByteArrayOutputStream()
                // JPEG avoids spending several seconds losslessly encoding a
                // 2560x1600 game frame. Quality 92 retains template/OCR edges.
                val encoded = try {
                    if (visible.compress(Bitmap.CompressFormat.JPEG, 92, output)) {
                        output.toByteArray()
                    } else {
                        null
                    }
                } finally {
                    if (visible !== padded) visible.recycle()
                    padded.recycle()
                }
                encoded?.let {
                    publishIfActive(session, it, width, height, capturedAt)
                }
            } finally {
                image.close()
            }
        }, Handler(handlerThread.looper))
        mediaProjection.registerCallback(
            object : MediaProjection.Callback() {
                override fun onStop() {
                    clearIfActive(session)
                }
            },
            Handler(handlerThread.looper),
        )
        virtualDisplay = mediaProjection.createVirtualDisplay(
            "WzryFrameStream",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.surface,
            null,
            Handler(handlerThread.looper),
        )
    }

    fun latestFrame(maxAgeMs: Long = 1_000): StreamFrame? =
        frames.latest(maxAgeMs)

    @Synchronized
    private fun publishIfActive(
        session: Long,
        encoded: ByteArray,
        width: Int,
        height: Int,
        capturedAtElapsedRealtimeNanos: Long,
    ) {
        if (sessionGeneration.get() != session) return
        frames.publish(
            encoded = encoded,
            width = width,
            height = height,
            capturedAtElapsedRealtimeNanos = capturedAtElapsedRealtimeNanos,
        )
    }

    @Synchronized
    private fun clearIfActive(session: Long) {
        if (sessionGeneration.get() == session) frames.clear()
    }

    @Synchronized
    override fun close() {
        // Invalidate callbacks before releasing resources. A frame whose JPEG
        // conversion completes after close() must not repopulate the cache.
        sessionGeneration.incrementAndGet()
        reader?.setOnImageAvailableListener(null, null)
        virtualDisplay?.release()
        virtualDisplay = null
        reader?.close()
        reader = null
        projection?.stop()
        projection = null
        thread?.quitSafely()
        thread = null
        frames.clear()
    }
}
