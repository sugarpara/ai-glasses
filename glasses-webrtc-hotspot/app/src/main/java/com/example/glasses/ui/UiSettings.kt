package com.example.glasses.ui

internal enum class VideoInputSource {
    GLASSES,
    PHONE_CAMERA,
}

internal data class GlassesSettings(
    val outputVolume: Float = 0.8f,
    val showGrid: Boolean = false,
    val videoInputSource: VideoInputSource = VideoInputSource.GLASSES,
)
