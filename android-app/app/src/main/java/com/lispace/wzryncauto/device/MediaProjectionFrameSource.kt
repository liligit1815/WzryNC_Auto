package com.lispace.wzryncauto.device

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.view.WindowManager
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicReference

data class StreamFrame(
    val encoded: ByteArray,
    val width: Int,
    val height: Int,
    val capturedAtMs: Long,
)

/**
 * Keeps only the newest display frame. Consumers never wait for PNG capture or
 * a root process; if the projection is unavailable they can use screencap.
 */
class MediaProjectionFrameSource(private val context: Context) : AutoCloseable {
    private val latest = AtomicReference<StreamFrame?>()
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null

    @Synchronized
    fun start(resultCode: Int, data: Intent) {
        close()
        val windowManager = context.getSystemService(WindowManager::class.java)
        val bounds = windowManager.maximumWindowMetrics.bounds
        val width = bounds.width()
        val height = bounds.height()
        val density = context.resources.displayMetrics.densityDpi
        val handlerThread = HandlerThread("screen-frame-stream").apply { start() }
        val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        val manager = context.getSystemService(MediaProjectionManager::class.java)
        val mediaProjection = manager.getMediaProjection(resultCode, data)

        thread = handlerThread
        reader = imageReader
        projection = mediaProjection
        imageReader.setOnImageAvailableListener({ source ->
            val image = source.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val plane = image.planes.first()
                val rowPadding = plane.rowStride - plane.pixelStride * width
                val paddedWidth = width + rowPadding / plane.pixelStride
                val padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
                padded.copyPixelsFromBuffer(plane.buffer)
                val visible = Bitmap.createBitmap(padded, 0, 0, width, height)
                padded.recycle()
                val output = ByteArrayOutputStream()
                // JPEG avoids spending several seconds losslessly encoding a
                // 2560x1600 game frame. Quality 92 retains template/OCR edges.
                visible.compress(Bitmap.CompressFormat.JPEG, 92, output)
                visible.recycle()
                latest.set(StreamFrame(output.toByteArray(), width, height, System.currentTimeMillis()))
            } finally {
                image.close()
            }
        }, Handler(handlerThread.looper))
        mediaProjection.registerCallback(
            object : MediaProjection.Callback() {
                override fun onStop() {
                    latest.set(null)
                }
            },
            Handler(handlerThread.looper),
        )
        mediaProjection.createVirtualDisplay(
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
        latest.get()?.takeIf { System.currentTimeMillis() - it.capturedAtMs <= maxAgeMs }

    @Synchronized
    override fun close() {
        reader?.setOnImageAvailableListener(null, null)
        reader?.close()
        reader = null
        projection?.stop()
        projection = null
        thread?.quitSafely()
        thread = null
        latest.set(null)
    }
}
