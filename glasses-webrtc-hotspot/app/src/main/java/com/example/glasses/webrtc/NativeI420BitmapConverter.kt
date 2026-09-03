package com.example.glasses.webrtc

import android.graphics.Bitmap
import org.webrtc.VideoFrame
import java.nio.ByteBuffer

/** Converts and rotates I420 frames directly into their final Android Bitmap. */
internal object NativeI420BitmapConverter {
    fun convert(
        buffer: VideoFrame.I420Buffer,
        rotation: Int,
    ): Bitmap {
        val normalizedRotation = ((rotation % 360) + 360) % 360
        require(normalizedRotation % 90 == 0) {
            "Unsupported WebRTC frame rotation: $rotation"
        }
        val swapsDimensions = normalizedRotation == 90 || normalizedRotation == 270
        val outputWidth = if (swapsDimensions) buffer.height else buffer.width
        val outputHeight = if (swapsDimensions) buffer.width else buffer.height
        val bitmap = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
        try {
            nativeConvert(
                yBuffer = buffer.dataY,
                yOffset = buffer.dataY.position(),
                yStride = buffer.strideY,
                uBuffer = buffer.dataU,
                uOffset = buffer.dataU.position(),
                uStride = buffer.strideU,
                vBuffer = buffer.dataV,
                vOffset = buffer.dataV.position(),
                vStride = buffer.strideV,
                width = buffer.width,
                height = buffer.height,
                rotation = normalizedRotation,
                bitmap = bitmap,
            )
            return bitmap
        } catch (error: Throwable) {
            bitmap.recycle()
            throw error
        }
    }

    private external fun nativeConvert(
        yBuffer: ByteBuffer,
        yOffset: Int,
        yStride: Int,
        uBuffer: ByteBuffer,
        uOffset: Int,
        uStride: Int,
        vBuffer: ByteBuffer,
        vOffset: Int,
        vStride: Int,
        width: Int,
        height: Int,
        rotation: Int,
        bitmap: Bitmap,
    )

    init {
        System.loadLibrary("ground_filter")
    }
}
