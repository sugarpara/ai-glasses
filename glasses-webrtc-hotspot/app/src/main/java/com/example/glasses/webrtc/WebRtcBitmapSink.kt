package com.example.glasses.webrtc

import android.graphics.Bitmap
import android.util.Log
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/** Converts decoded WebRTC frames to upright Bitmaps for the existing depth pipeline. */
class WebRtcBitmapSink(
    private val shouldAcceptFrame: () -> Boolean,
    private val onBitmap: (Bitmap, Double) -> Unit,
    private val onError: (Throwable) -> Unit,
) : VideoSink, Closeable {
    private val executor = Executors.newSingleThreadExecutor()
    private val conversionRunning = AtomicBoolean(false)

    @Volatile
    private var closed = false

    override fun onFrame(frame: VideoFrame) {
        if (closed || !shouldAcceptFrame() || !conversionRunning.compareAndSet(false, true)) {
            return
        }

        frame.retain()
        try {
            executor.execute { convert(frame) }
        } catch (_: RejectedExecutionException) {
            frame.release()
            conversionRunning.set(false)
        }
    }

    private fun convert(frame: VideoFrame) {
        var converted: Bitmap? = null
        try {
            val conversionStartNs = System.nanoTime()
            val i420 = requireNotNull(frame.buffer.toI420()) {
                "WebRTC frame could not be converted to I420"
            }
            try {
                converted = NativeI420BitmapConverter.convert(i420, frame.rotation)
            } finally {
                i420.release()
            }

            val conversionMs = (System.nanoTime() - conversionStartNs) / 1_000_000.0
            onBitmap(requireNotNull(converted), conversionMs)
            converted = null
        } catch (error: Throwable) {
            converted?.recycle()
            Log.e(TAG, "Failed to convert remote WebRTC frame", error)
            onError(error)
        } finally {
            frame.release()
            conversionRunning.set(false)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        executor.shutdownNow()
    }

    private companion object {
        const val TAG = "PhoneVideo"
    }
}
