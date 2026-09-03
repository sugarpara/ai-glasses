package com.rokid.glassesbaredevsample.camera

import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.rokid.glassesbaredevsample.webrtc.CameraXVideoBridge
import java.util.Locale

class FpsFrameAnalyzer : ImageAnalysis.Analyzer {
    private var frameCount = 0
    private var submittedFrameCount = 0
    private var windowStartNs = 0L
    private var firstFrameLogged = false

    override fun analyze(image: ImageProxy) {
        try {
            val now = System.nanoTime()
            if (windowStartNs == 0L) windowStartNs = now
            frameCount++
            if (CameraXVideoBridge.submit(image)) submittedFrameCount++

            if (!firstFrameLogged) {
                firstFrameLogged = true
                Log.i(
                    TAG,
                    "First frame size=${image.width}x${image.height} " +
                            "rotation=${image.imageInfo.rotationDegrees}",
                )
            }

            val elapsedNs = now - windowStartNs
            if (elapsedNs >= 1_000_000_000L) {
                val fps = frameCount * 1_000_000_000.0 / elapsedNs
                Log.i(
                    TAG,
                    String.format(
                        Locale.US,
                        "CameraFPS=%.1f WebRtcFPS=%.1f size=%dx%d rotation=%d",
                        fps,
                        submittedFrameCount * 1_000_000_000.0 / elapsedNs,
                        image.width,
                        image.height,
                        image.imageInfo.rotationDegrees,
                    ),
                )
                frameCount = 0
                submittedFrameCount = 0
                windowStartNs = now
            }
        } finally {
            // 必须释放，否则 CameraX 很快停止继续送帧。
            image.close()
        }
    }

    companion object {
        private const val TAG = "BareFrame"
    }
}
