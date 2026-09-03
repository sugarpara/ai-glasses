package com.rokid.glassesbaredevsample.webrtc

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CaptureRequest
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Range
import android.util.Size
import android.util.Log
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.rokid.glassesbaredevsample.R
import com.rokid.glassesbaredevsample.camera.FpsFrameAnalyzer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Runs the verified CameraX -> WebRTC path without requiring a visible Activity. */
class StreamingForegroundService : Service(), LifecycleOwner {
    enum class FailureType {
        CAMERA,
        WEBRTC,
    }

    interface Listener {
        fun onSignalingConnected()
        fun onStreaming()
        fun onFailure(type: FailureType, message: String)
    }

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val powerManager by lazy { getSystemService(PowerManager::class.java) }
    private val mainHandler = Handler(Looper.getMainLooper())

    private var cameraProvider: ProcessCameraProvider? = null
    private var webRtcClient: WebRtcSignalingClient? = null
    private var currentPhoneHost: String? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var stopping = false
    private var cameraBinding = false
    private var cameraBound = false
    private var peerConnected = false
    private var streamingReported = false
    private var startupTimeoutTask: Runnable? = null

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override fun onCreate() {
        super.onCreate()
        stopping = false
        active = true
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        createNotificationChannel()
        startForeground(
            NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Glasses video stream")
                .setContentText("Camera and WebRTC are active")
                .setOngoing(true)
                .build(),
        )
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$packageName:video-stream",
        ).also { lock -> lock.acquire() }
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        Log.i(TAG, "Streaming foreground service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val phoneHost = intent?.getStringExtra(EXTRA_PHONE_HOST)
        if (phoneHost.isNullOrBlank()) {
            Log.w(TAG, "Streaming start ignored because phone host is missing")
            stopSelf()
            return START_NOT_STICKY
        }
        if (phoneHost == currentPhoneHost && webRtcClient != null && cameraProvider != null) {
            return START_NOT_STICKY
        }

        Log.i(TAG, "Starting headless CameraX and WebRTC host=$phoneHost")
        stopStreaming()
        currentPhoneHost = phoneHost
        lateinit var client: WebRtcSignalingClient
        client = WebRtcSignalingClient(
            applicationContext,
            phoneHost,
            SIGNALING_PORT,
            object : WebRtcSignalingClient.Listener {
                override fun onSignalingConnected() {
                    mainHandler.post {
                        if (!stopping) stateListener?.onSignalingConnected()
                    }
                }

                override fun onPeerConnected() {
                    mainHandler.post {
                        if (stopping || webRtcClient !== client) return@post
                        peerConnected = true
                        bindCamera()
                        maybeReportStreaming()
                    }
                }

                override fun onPeerDisconnected() {
                    mainHandler.post {
                        if (stopping || webRtcClient !== client) return@post
                        peerConnected = false
                        streamingReported = false
                        releaseCamera()
                        Log.i(TAG, "Peer disconnected; camera released until reconnect")
                    }
                }
            },
        )
        webRtcClient = client
        client.start()
        scheduleStartupTimeout()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopping = true
        active = false
        stopStreaming()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        analysisExecutor.shutdownNow()
        mainHandler.removeCallbacksAndMessages(null)
        wakeLock?.let { lock -> if (lock.isHeld) lock.release() }
        wakeLock = null
        Log.i(TAG, "Streaming foreground service destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @ExperimentalCamera2Interop
    private fun bindCamera() {
        if (stopping || !peerConnected || cameraBound || cameraBinding) return
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Camera permission is missing")
            reportFailure(FailureType.CAMERA, "Camera permission is missing")
            stopSelf()
            return
        }
        cameraBinding = true
        val future = ProcessCameraProvider.getInstance(applicationContext)
        future.addListener(
            {
                try {
                    cameraBinding = false
                    if (stopping || !peerConnected) return@addListener
                    val provider = future.get()
                    if (stopping || !peerConnected) {
                        provider.unbindAll()
                        return@addListener
                    }
                    val analysisBuilder = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setResolutionSelector(
                            ResolutionSelector.Builder()
                                .setAspectRatioStrategy(
                                    AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY,
                                )
                                .setResolutionStrategy(
                                    ResolutionStrategy(
                                        Size(STREAM_WIDTH, STREAM_HEIGHT),
                                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                                    ),
                                )
                                .build(),
                        )
                    Camera2Interop.Extender(analysisBuilder).setCaptureRequestOption(
                        CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                        Range(CAMERA_CAPTURE_FPS, CAMERA_CAPTURE_FPS),
                    )
                    val analysis = analysisBuilder.build()
                        .also { useCase ->
                            useCase.setAnalyzer(analysisExecutor, FpsFrameAnalyzer())
                        }
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        this,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        analysis,
                    )
                    if (stopping) {
                        provider.unbindAll()
                        return@addListener
                    }
                    cameraProvider = provider
                    cameraBound = true
                    Log.i(TAG, "Headless CameraX bound")
                    maybeReportStreaming()
                } catch (error: Exception) {
                    cameraBinding = false
                    Log.e(TAG, "Headless CameraX bind failed", error)
                    reportFailure(
                        FailureType.CAMERA,
                        error.message ?: "Headless CameraX bind failed",
                    )
                    stopSelf()
                }
            },
            ContextCompat.getMainExecutor(applicationContext),
        )
    }

    private fun stopStreaming() {
        clearStartupTimeout()
        releaseCamera()
        webRtcClient?.stop()
        webRtcClient = null
        currentPhoneHost = null
        cameraBound = false
        peerConnected = false
        streamingReported = false
    }

    private fun releaseCamera() {
        cameraProvider?.unbindAll()
        cameraProvider = null
        cameraBinding = false
        cameraBound = false
    }

    private fun maybeReportStreaming() {
        if (!cameraBound || !peerConnected || streamingReported || stopping) return
        streamingReported = true
        clearStartupTimeout()
        Log.i(TAG, "CameraX and WebRTC are streaming")
        stateListener?.onStreaming()
    }

    private fun scheduleStartupTimeout() {
        clearStartupTimeout()
        startupTimeoutTask = Runnable {
            if (!streamingReported && !stopping) {
                reportFailure(FailureType.WEBRTC, "WebRTC startup timed out")
                stopSelf()
            }
        }.also { task -> mainHandler.postDelayed(task, STARTUP_TIMEOUT_MS) }
    }

    private fun clearStartupTimeout() {
        startupTimeoutTask?.let(mainHandler::removeCallbacks)
        startupTimeoutTask = null
    }

    private fun reportFailure(type: FailureType, message: String) {
        stateListener?.onFailure(type, message)
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Glasses video stream",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    companion object {
        private const val TAG = "GlassesStream"
        private const val CHANNEL_ID = "glasses_video_stream"
        private const val NOTIFICATION_ID = 203
        private const val SIGNALING_PORT = 8888
        private const val STARTUP_TIMEOUT_MS = 25_000L
        private const val STREAM_WIDTH = 640
        private const val STREAM_HEIGHT = 360
        private const val CAMERA_CAPTURE_FPS = 15
        private const val EXTRA_PHONE_HOST = "phone_host"

        @Volatile
        private var active = false
        @Volatile
        private var stateListener: Listener? = null

        fun isActive(): Boolean = active

        fun setStateListener(listener: Listener) {
            stateListener = listener
        }

        fun clearStateListener(listener: Listener) {
            if (stateListener === listener) stateListener = null
        }

        fun start(context: Context, phoneHost: String) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, StreamingForegroundService::class.java)
                    .putExtra(EXTRA_PHONE_HOST, phoneHost),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, StreamingForegroundService::class.java))
        }
    }
}
