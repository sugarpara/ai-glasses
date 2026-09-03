package com.example.glasses.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.glasses.camera.DepthCameraController
import com.example.glasses.audio.BluetoothAudioOutput
import com.example.glasses.ble.BleProtocolError
import com.example.glasses.ble.BleProtocolState
import com.example.glasses.ble.BleProvisioningClient
import com.example.glasses.ble.BleSessionStatus
import com.example.glasses.pipeline.AudioSpectrumBar
import com.example.glasses.pipeline.AudioWaveformBar
import com.example.glasses.pipeline.DepthAudioCoordinatorStatus
import com.example.glasses.ui.theme.AppBlue
import com.example.glasses.ui.theme.AppGreen
import com.example.glasses.ui.theme.AppMutedText
import com.example.glasses.ui.theme.AppOutline
import com.example.glasses.ui.theme.AppRed
import com.example.glasses.webrtc.LocalSignalingServer
import com.example.glasses.webrtc.WebRtcBitmapSink
import kotlinx.coroutines.delay
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

@Composable
internal fun DepthCameraScreen(
    settings: GlassesSettings,
    bluetoothAudioOutput: BluetoothAudioOutput,
    onRecoverBluetoothAudio: () -> Unit,
    onBack: () -> Unit,
    viewModel: DepthCameraViewModel = viewModel(),
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val audioState by viewModel.audioState.collectAsStateWithLifecycle()
    val modelReady = state is DepthCameraUiState.WaitingForInput ||
        state is DepthCameraUiState.Running
    val usePhoneCamera = settings.videoInputSource == VideoInputSource.PHONE_CAMERA
    var lifecycleStarted by remember {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }
    var permissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val controller = remember { DepthCameraController(context.applicationContext) }
    val remoteFrameSink = remember(viewModel) {
        WebRtcBitmapSink(
            shouldAcceptFrame = viewModel::canAcceptFrame,
            onBitmap = viewModel::process,
            onError = viewModel::reportInputError,
        )
    }
    var signalingReady by remember { mutableStateOf(false) }
    val signalingServerRef = remember { AtomicReference<LocalSignalingServer?>(null) }
    var activeSignalingServer by remember { mutableStateOf<LocalSignalingServer?>(null) }
    var remoteVideoFps by remember { mutableStateOf(0.0) }
    var selectedVideoPanelMask by rememberSaveable(usePhoneCamera) {
        mutableIntStateOf(defaultAssistanceVideoPanelMask(usePhoneCamera))
    }
    var foregroundGeneration by remember { mutableIntStateOf(0) }
    var reconnectGeneration by remember { mutableIntStateOf(0) }
    var bleStartRequested by remember { mutableStateOf(false) }
    var blePermissionRequested by remember { mutableStateOf(false) }
    var blePermissionsGranted by remember {
        mutableStateOf(hasRequiredBlePermissions(context))
    }
    var glassesBleState by remember {
        mutableStateOf<BleProvisioningClient.State>(BleProvisioningClient.State.Idle)
    }
    var stopRequested by remember { mutableStateOf(false) }
    var stopAcknowledged by remember { mutableStateOf(false) }
    var stopNavigationStarted by remember { mutableStateOf(false) }
    val bleClient = remember(context) {
        BleProvisioningClient(context.applicationContext) { state ->
            glassesBleState = state
            if (
                stopRequested &&
                state is BleProvisioningClient.State.StatusChanged &&
                state.status.state == BleProtocolState.STOPPED
            ) {
                stopAcknowledged = true
            }
            if (
                state is BleProvisioningClient.State.StatusChanged &&
                state.status.error.isPhysicalShutdown()
            ) {
                signalingServerRef.get()?.resetClientSession()
                reconnectGeneration++
            }
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { permissionGranted = it }
    val blePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        blePermissionsGranted = result.values.all { it }
        if (!blePermissionsGranted) {
            glassesBleState = BleProvisioningClient.State.Error("需要蓝牙权限才能启动眼镜会话")
        }
    }
    val startedAt = remember { SystemClock.elapsedRealtime() }
    var elapsedSeconds by remember { mutableLongStateOf(0L) }

    LaunchedEffect(usePhoneCamera) {
        signalingReady = false
        reconnectGeneration = 0
        bleStartRequested = false
        blePermissionRequested = false
        if (usePhoneCamera) bleClient.close()
        viewModel.prepareForInput()
        viewModel.initialize()
        if (usePhoneCamera && !permissionGranted) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
        while (true) {
            elapsedSeconds = (SystemClock.elapsedRealtime() - startedAt) / 1_000L
            delay(1_000L)
        }
    }

    DisposableEffect(context, lifecycleOwner, usePhoneCamera) {
        val observer = LifecycleEventObserver { _, event ->
            if (usePhoneCamera && event == Lifecycle.Event.ON_RESUME) {
                permissionGranted = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.CAMERA,
                ) == PackageManager.PERMISSION_GRANTED
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    DisposableEffect(viewModel, lifecycleOwner, usePhoneCamera, bleClient) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    Log.i(PHONE_SESSION_TAG, "Assistance screen started")
                    foregroundGeneration++
                    signalingReady = false
                    bleStartRequested = false
                    lifecycleStarted = true
                    viewModel.startAudio()
                }
                Lifecycle.Event.ON_STOP -> {
                    Log.i(PHONE_SESSION_TAG, "Assistance screen stopped; releasing foreground session")
                    lifecycleStarted = false
                    viewModel.stopAudio()
                    if (!usePhoneCamera) {
                        signalingReady = false
                        bleStartRequested = false
                        blePermissionRequested = false
                        glassesBleState = BleProvisioningClient.State.Idle
                        // Keep the subscribed GATT connection ready for the next foreground command.
                        signalingServerRef.getAndSet(null)?.stop()
                        viewModel.prepareForInput()
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            viewModel.startAudio()
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.stopAudio()
        }
    }

    DisposableEffect(
        usePhoneCamera,
        permissionGranted,
        modelReady,
        lifecycleStarted,
        lifecycleOwner,
    ) {
        if (usePhoneCamera && permissionGranted && modelReady && lifecycleStarted) {
            controller.start(
                lifecycleOwner = lifecycleOwner,
                onBitmap = viewModel::process,
                onError = viewModel::reportInputError,
            )
        }
        onDispose { controller.stop() }
    }

    LaunchedEffect(
        usePhoneCamera,
        signalingReady,
        lifecycleStarted,
        blePermissionsGranted,
        bleStartRequested,
    ) {
        if (!shouldBeginGlassesSession(
                usePhoneCamera = usePhoneCamera,
                signalingReady = signalingReady,
                lifecycleStarted = lifecycleStarted,
                bleStartRequested = bleStartRequested,
            )
        ) {
            return@LaunchedEffect
        }
        val missingPermissions = requiredBlePermissions().filter { permission ->
            ContextCompat.checkSelfPermission(context, permission) !=
                PackageManager.PERMISSION_GRANTED
        }
        if (missingPermissions.isEmpty()) {
            blePermissionsGranted = true
            bleStartRequested = true
            Log.i(PHONE_SESSION_TAG, "Port 8888 ready; sending START_SESSION")
            bleClient.startSession()
        } else if (!blePermissionRequested) {
            blePermissionRequested = true
            blePermissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    LaunchedEffect(reconnectGeneration) {
        if (reconnectGeneration == 0 || usePhoneCamera) return@LaunchedEffect
        delay(BLE_RECONNECT_DELAY_MS)
        bleClient.close()
        bleStartRequested = false
    }

    LaunchedEffect(stopRequested, stopAcknowledged) {
        if (!stopRequested || stopNavigationStarted) return@LaunchedEffect
        if (!stopAcknowledged) delay(ASSISTANCE_STOP_TIMEOUT_MS)
        Log.i(
            PHONE_SESSION_TAG,
            if (stopAcknowledged) {
                "STOP_SESSION acknowledged; leaving assistance"
            } else {
                "STOP_SESSION timeout; leaving assistance with local cleanup"
            },
        )
        stopNavigationStarted = true
        onBack()
    }

    DisposableEffect(
        usePhoneCamera,
        modelReady,
        lifecycleStarted,
        foregroundGeneration,
        context,
        remoteFrameSink,
    ) {
        val signalingServer = if (!usePhoneCamera && modelReady && lifecycleStarted) {
            LocalSignalingServer(
                context.applicationContext,
                SIGNALING_PORT,
                viewModel::reportInputError,
                object : LocalSignalingServer.ListeningListener {
                    override fun onListening() {
                        signalingReady = true
                    }

                    override fun onClientDisconnected() {
                        remoteVideoFps = 0.0
                        viewModel.prepareForInput()
                    }
                },
            ).also { server ->
                signalingServerRef.set(server)
                try {
                    server.setVideoStatsListener { fps -> remoteVideoFps = fps }
                    server.setRemoteInferenceSink(remoteFrameSink)
                    server.start()
                    activeSignalingServer = server
                } catch (error: Throwable) {
                    viewModel.reportInputError(error)
                }
            }
        } else {
            null
        }
        onDispose {
            signalingReady = false
            remoteVideoFps = 0.0
            if (activeSignalingServer === signalingServer) {
                activeSignalingServer = null
            }
            signalingServerRef.compareAndSet(signalingServer, null)
            signalingServer?.setRemoteInferenceSink(null)
            signalingServer?.setVideoStatsListener(null)
            signalingServer?.stop()
        }
    }

    DisposableEffect(bleClient) {
        onDispose { bleClient.close() }
    }

    DisposableEffect(controller, remoteFrameSink) {
        onDispose {
            remoteFrameSink.close()
            controller.close()
        }
    }

    val requestStop: (String) -> Unit = { source ->
        when {
            stopRequested -> Unit
            usePhoneCamera -> {
                Log.i(PHONE_SESSION_TAG, "Leaving phone-camera assistance source=$source")
                onBack()
            }
            else -> {
                Log.i(PHONE_SESSION_TAG, "STOP_SESSION requested source=$source")
                stopRequested = true
                bleClient.stopSession()
            }
        }
    }

    BackHandler { requestStop("system-back") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .statusBarsPadding(),
    ) {
        AssistanceTopBar(onBack = { requestStop("top-bar") })
        AssistanceStatus(
            elapsedSeconds = elapsedSeconds,
            inputSource = settings.videoInputSource,
            receivingFrames = state is DepthCameraUiState.Running,
        )
        BluetoothAudioStatusBanner(
            output = bluetoothAudioOutput,
            onRecover = onRecoverBluetoothAudio,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when (val current = state) {
                DepthCameraUiState.LoadingModel -> CameraPlaceholder("正在加载 YOLO26 Depth 模型...")
                DepthCameraUiState.WaitingForInput -> CameraPlaceholder(
                    if (usePhoneCamera) {
                        "正在启动手机摄像头..."
                    } else {
                        "等待眼镜视频流...\n${glassesBleState.assistanceText()}"
                    },
                )
                is DepthCameraUiState.Error -> CameraPlaceholder(
                    text = "运行失败\n${current.message}",
                    color = AppRed,
                )
                is DepthCameraUiState.Running -> RunningCameraPanel(
                    state = current,
                    showGrid = settings.showGrid,
                    signalingServer = activeSignalingServer.takeUnless { usePhoneCamera },
                    remoteVideoFps = remoteVideoFps,
                    selectedPanelMask = selectedVideoPanelMask,
                    onPanelToggled = { panel ->
                        selectedVideoPanelMask = toggleAssistanceVideoPanel(
                            currentMask = selectedVideoPanelMask,
                            panel = panel,
                            availableMask = availableAssistanceVideoPanelMask(
                                rawVideoAvailable = activeSignalingServer != null && !usePhoneCamera,
                            ),
                        )
                    },
                    onFrameDisplayed = viewModel::reportUiFrameDisplayed,
                )
            }

            if (usePhoneCamera && !permissionGranted && state !is DepthCameraUiState.LoadingModel) {
                Button(
                    onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("授予摄像头权限")
                }
            }

            val running = state as? DepthCameraUiState.Running
            SoundscapeStatusCard(audioState = audioState)
            StereoWaveforms(
                leftWaveform = audioState.leftWaveform,
                rightWaveform = audioState.rightWaveform,
            )
            StereoFrequencySpectra(
                leftSpectrum = audioState.leftSpectrum,
                rightSpectrum = audioState.rightSpectrum,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = running?.let {
                        String.format(Locale.US, "%s · %.1f FPS", it.accelerator, it.fps)
                    } ?: "YOLO26 Depth",
                    color = AppMutedText,
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = if (usePhoneCamera) "手机画面" else "眼镜画面",
                    color = AppMutedText,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.height(2.dp))
        }

        Button(
            onClick = { requestStop("stop-button") },
            enabled = !stopRequested,
            colors = ButtonDefaults.buttonColors(containerColor = AppRed),
            shape = RoundedCornerShape(7.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .height(48.dp),
        ) {
            Icon(Icons.Default.Stop, contentDescription = null)
            Text(
                if (stopRequested) "正在停止" else "停止辅助",
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

@Composable
private fun AssistanceTopBar(onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
        }
        Text(
            text = "实时辅助",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = {}) {
            Icon(Icons.Default.MoreVert, contentDescription = "更多")
        }
    }
}

@Composable
private fun AssistanceStatus(
    elapsedSeconds: Long,
    inputSource: VideoInputSource,
    receivingFrames: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(9.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(if (receivingFrames) AppGreen else AppBlue),
        )
        Text(
            text = when {
                receivingFrames -> "运行中"
                inputSource == VideoInputSource.GLASSES -> "等待眼镜连接"
                else -> "准备手机摄像头"
            },
            color = if (receivingFrames) AppGreen else AppBlue,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(start = 8.dp),
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = "%02d:%02d".format(elapsedSeconds / 60, elapsedSeconds % 60),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun RunningCameraPanel(
    state: DepthCameraUiState.Running,
    showGrid: Boolean,
    signalingServer: LocalSignalingServer?,
    remoteVideoFps: Double,
    selectedPanelMask: Int,
    onPanelToggled: (AssistanceVideoPanel) -> Unit,
    onFrameDisplayed: (Long) -> Unit,
) {
    LaunchedEffect(state.performanceFrameSequence) {
        onFrameDisplayed(state.performancePublishedAtNanos)
    }
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        VideoPanelSelector(
            selectedPanelMask = selectedPanelMask,
            rawVideoAvailable = signalingServer != null,
            onPanelToggled = onPanelToggled,
        )
        if (
            signalingServer != null &&
            selectedPanelMask and AssistanceVideoPanel.RAW_VIDEO.mask != 0
        ) {
            RawVideoPanel(
                signalingServer = signalingServer,
                remoteVideoFps = remoteVideoFps,
            )
        }
        if (selectedPanelMask and AssistanceVideoPanel.DEPTH.mask != 0) {
            CameraImagePanel(
                label = "深度图",
                image = state.image,
                showGrid = showGrid,
                badge = String.format(Locale.US, "模型 %.1f FPS", state.fps),
            )
        }
        if (selectedPanelMask and AssistanceVideoPanel.OBSTACLE.mask != 0) {
            CameraImagePanel(
                label = "障碍分类",
                image = state.classificationImage,
                showGrid = showGrid,
                badge = "${state.activeObstacleCells} 个障碍区域",
            )
        }
    }
}

@Composable
private fun VideoPanelSelector(
    selectedPanelMask: Int,
    rawVideoAvailable: Boolean,
    onPanelToggled: (AssistanceVideoPanel) -> Unit,
) {
    val availableMask = availableAssistanceVideoPanelMask(rawVideoAvailable)
    val selectedCount = Integer.bitCount(selectedPanelMask and availableMask)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        AssistanceVideoPanel.entries.forEach { panel ->
            val selected = selectedPanelMask and panel.mask != 0
            val available = availableMask and panel.mask != 0
            val enabled = available && when {
                selected -> true
                else -> selectedCount < MAX_VISIBLE_VIDEO_PANELS
            }
            FilterChip(
                selected = selected,
                onClick = { onPanelToggled(panel) },
                enabled = enabled,
                label = { Text(panel.selectorLabel, maxLines = 1) },
                leadingIcon = if (selected) {
                    {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                } else {
                    null
                },
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp),
            )
        }
    }
}

@Composable
private fun RawVideoPanel(
    signalingServer: LocalSignalingServer,
    remoteVideoFps: Double,
) {
    val context = LocalContext.current
    val renderer = remember(context, signalingServer) {
        SurfaceViewRenderer(context).apply {
            init(signalingServer.eglBaseContext, null)
            setEnableHardwareScaler(true)
            setMirror(false)
            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        }
    }
    DisposableEffect(signalingServer, renderer) {
        signalingServer.setRemoteVideoRenderer(renderer)
        onDispose {
            signalingServer.setRemoteVideoRenderer(null)
            renderer.release()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(RAW_VIDEO_ASPECT_RATIO)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black),
    ) {
        AndroidView(
            factory = { renderer },
            modifier = Modifier.fillMaxSize(),
        )
        Surface(
            color = Color.Black.copy(alpha = 0.66f),
            shape = RoundedCornerShape(5.dp),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp),
        ) {
            Text(
                text = String.format(Locale.US, "原始画面 · %.1f FPS", remoteVideoFps),
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun CameraImagePanel(
    label: String,
    image: androidx.compose.ui.graphics.ImageBitmap,
    showGrid: Boolean,
    badge: String? = null,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black),
    ) {
        Image(
            bitmap = image,
            contentDescription = label,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
        if (showGrid) {
            Canvas(Modifier.fillMaxSize()) {
                val gridColor = Color.White.copy(alpha = 0.16f)
                for (index in 1 until ASSISTANCE_GRID_SIZE) {
                    val x = size.width * index / ASSISTANCE_GRID_SIZE
                    drawLine(gridColor, Offset(x, 0f), Offset(x, size.height), 0.6f)
                }
                for (index in 1 until ASSISTANCE_GRID_SIZE) {
                    val y = size.height * index / ASSISTANCE_GRID_SIZE
                    drawLine(gridColor, Offset(0f, y), Offset(size.width, y), 0.6f)
                }
            }
        }
        Surface(
            color = Color.Black.copy(alpha = 0.66f),
            shape = RoundedCornerShape(5.dp),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp),
        ) {
            Text(
                text = label,
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        if (badge != null) {
            Surface(
                color = AppRed,
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(9.dp),
            ) {
                Text(
                    text = badge,
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                )
            }
        }
    }
}

private const val ASSISTANCE_GRID_SIZE = 64
private const val RAW_VIDEO_ASPECT_RATIO = 9f / 16f
private const val MAX_VISIBLE_VIDEO_PANELS = 2
private const val SIGNALING_PORT = 8888
private const val BLE_RECONNECT_DELAY_MS = 750L
private const val ASSISTANCE_STOP_TIMEOUT_MS = 5_000L
private const val PHONE_SESSION_TAG = "PhoneSession"

internal enum class AssistanceVideoPanel(
    val mask: Int,
    val selectorLabel: String,
) {
    RAW_VIDEO(1 shl 0, "原画"),
    DEPTH(1 shl 1, "深度"),
    OBSTACLE(1 shl 2, "障碍"),
}

internal fun defaultAssistanceVideoPanelMask(usePhoneCamera: Boolean): Int =
    if (usePhoneCamera) {
        AssistanceVideoPanel.DEPTH.mask or AssistanceVideoPanel.OBSTACLE.mask
    } else {
        AssistanceVideoPanel.RAW_VIDEO.mask or AssistanceVideoPanel.DEPTH.mask
    }

internal fun availableAssistanceVideoPanelMask(rawVideoAvailable: Boolean): Int =
    AssistanceVideoPanel.DEPTH.mask or
        AssistanceVideoPanel.OBSTACLE.mask or
        if (rawVideoAvailable) AssistanceVideoPanel.RAW_VIDEO.mask else 0

internal fun toggleAssistanceVideoPanel(
    currentMask: Int,
    panel: AssistanceVideoPanel,
    availableMask: Int,
): Int {
    val normalizedMask = currentMask and availableMask
    if (panel.mask and availableMask == 0) return normalizedMask
    val selected = normalizedMask and panel.mask != 0
    val selectedCount = Integer.bitCount(normalizedMask)
    return when {
        selected -> normalizedMask and panel.mask.inv()
        !selected && selectedCount < MAX_VISIBLE_VIDEO_PANELS -> normalizedMask or panel.mask
        else -> normalizedMask
    }
}

internal fun shouldBeginGlassesSession(
    usePhoneCamera: Boolean,
    signalingReady: Boolean,
    lifecycleStarted: Boolean,
    bleStartRequested: Boolean,
): Boolean = !usePhoneCamera && signalingReady && lifecycleStarted && !bleStartRequested

private fun BleProtocolError?.isPhysicalShutdown(): Boolean =
    this == BleProtocolError.GLASSES_FOLDED ||
        this == BleProtocolError.GLASSES_NOT_WORN

internal fun BleProvisioningClient.State.assistanceText(): String = when (this) {
    BleProvisioningClient.State.Idle -> "正在准备信令端口 8888"
    BleProvisioningClient.State.Scanning -> "正在搜索眼镜"
    BleProvisioningClient.State.Connecting -> "正在连接眼镜蓝牙"
    BleProvisioningClient.State.Discovering -> "正在读取眼镜 BLE 服务"
    BleProvisioningClient.State.Negotiating -> "正在协商蓝牙数据长度"
    BleProvisioningClient.State.Subscribing -> "正在订阅眼镜状态"
    is BleProvisioningClient.State.Sending -> "正在发送启动命令 · $requestId"
    is BleProvisioningClient.State.CommandTransferred -> "启动命令已传输 · $requestId"
    is BleProvisioningClient.State.Ready -> status.assistanceText()
    is BleProvisioningClient.State.StatusChanged -> status.assistanceText()
    is BleProvisioningClient.State.Error -> message
}

internal fun BleSessionStatus.assistanceText(): String {
    error?.let { return it.assistanceText() }
    return when (state) {
        BleProtocolState.COMMAND_RECEIVED -> "眼镜已接收启动命令"
        BleProtocolState.CREDENTIALS_ACCEPTED -> "眼镜已接收热点参数"
        BleProtocolState.WIFI_ENABLING -> "眼镜正在开启 Wi-Fi"
        BleProtocolState.WIFI_CONNECTING -> "眼镜正在连接已保存热点"
        BleProtocolState.WIFI_READY -> "眼镜已连接热点"
        BleProtocolState.PHONE_WAITING -> "眼镜正在确认手机信令端口"
        BleProtocolState.SIGNALING_CONNECTED -> "眼镜信令已连接"
        BleProtocolState.STREAMING -> "眼镜视频流已建立"
        BleProtocolState.STOPPED -> "眼镜会话已停止"
    }
}

internal fun BleProtocolError.assistanceText(): String = when (this) {
    BleProtocolError.INVALID_COMMAND -> "眼镜拒绝了启动命令"
    BleProtocolError.INVALID_CREDENTIALS -> "热点参数无效"
    BleProtocolError.PERMISSION_MISSING -> "眼镜缺少运行权限"
    BleProtocolError.GLASSES_FOLDED -> "眼镜镜腿已折叠"
    BleProtocolError.GLASSES_NOT_WORN -> "眼镜尚未佩戴"
    BleProtocolError.PHYSICAL_STATE_UNKNOWN ->
        "眼镜状态未知，请摘下折叠后重新展开佩戴"
    BleProtocolError.WIFI_ENABLE_REJECTED -> "眼镜无法开启 Wi-Fi"
    BleProtocolError.WIFI_CONFIGURATION_REJECTED -> "眼镜无法读取热点配置"
    BleProtocolError.WIFI_AUTH_FAILED -> "眼镜连接热点失败"
    BleProtocolError.WIFI_TIMEOUT -> "眼镜连接热点超时"
    BleProtocolError.NO_IPV4_GATEWAY -> "眼镜未获得手机网关"
    BleProtocolError.PHONE_SIGNALING_UNREACHABLE -> "眼镜无法访问手机信令端口"
    BleProtocolError.CAMERA_START_FAILED -> "眼镜相机启动失败"
    BleProtocolError.WEBRTC_FAILED -> "眼镜视频连接失败"
}

private fun hasRequiredBlePermissions(context: android.content.Context): Boolean =
    requiredBlePermissions().all { permission ->
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

private fun requiredBlePermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

@Composable
private fun CameraPlaceholder(text: String, color: Color = AppMutedText) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(356.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF10141A)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = color,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(24.dp),
        )
    }
}

@Composable
private fun SoundscapeStatusCard(audioState: DepthAudioUiState) {
    val title = when (audioState.status) {
        DepthAudioCoordinatorStatus.STOPPED -> "空间声景已停止"
        DepthAudioCoordinatorStatus.WAITING_FOR_FRAME -> "空间声景准备中"
        DepthAudioCoordinatorStatus.ACTIVE,
        DepthAudioCoordinatorStatus.DEPTH_ONLY,
        -> "空间声景运行中"
        DepthAudioCoordinatorStatus.STALE -> "空间声景等待画面"
        DepthAudioCoordinatorStatus.ERROR -> "空间声景异常"
    }
    val detail = when (audioState.status) {
        DepthAudioCoordinatorStatus.STOPPED -> "音频输出未启动"
        DepthAudioCoordinatorStatus.WAITING_FOR_FRAME -> "等待深度画面"
        DepthAudioCoordinatorStatus.ACTIVE,
        DepthAudioCoordinatorStatus.DEPTH_ONLY,
        -> if (audioState.activeObstacleCount > 0) {
            "${audioState.activeObstacleCount} 个障碍单元参与声景"
        } else {
            "等待障碍画面"
        }
        DepthAudioCoordinatorStatus.STALE -> "深度画面已暂停"
        DepthAudioCoordinatorStatus.ERROR -> audioState.errorMessage ?: "音频输出失败"
    }
    val progress = when {
        audioState.status == DepthAudioCoordinatorStatus.ERROR -> 1f
        audioState.leftWaveform.isNotEmpty() -> 0.78f
        audioState.status == DepthAudioCoordinatorStatus.STOPPED -> 0f
        else -> 0.18f
    }
    val accent = if (audioState.status == DepthAudioCoordinatorStatus.ERROR) AppRed else AppBlue
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = Color.White,
        border = androidx.compose.foundation.BorderStroke(1.dp, AppOutline),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.GraphicEq,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(30.dp),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 10.dp),
            ) {
                Text(title, style = MaterialTheme.typography.labelLarge)
                Text(
                    text = detail,
                    color = AppMutedText,
                    style = MaterialTheme.typography.bodySmall,
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 7.dp)
                        .height(3.dp)
                        .background(Color(0xFFE7ECF4)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progress)
                            .height(3.dp)
                            .background(accent),
                    )
                }
            }
        }
    }
}

@Composable
private fun StereoWaveforms(
    leftWaveform: List<AudioWaveformBar>,
    rightWaveform: List<AudioWaveformBar>,
) {
    val waiting = leftWaveform.isEmpty() || rightWaveform.isEmpty()
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = Color.White,
        border = androidx.compose.foundation.BorderStroke(1.dp, AppOutline),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            if (waiting) {
                Text(
                    text = "等待声景生成",
                    color = AppMutedText,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.align(Alignment.End),
                )
            }
            WaveformRow("左耳", AppBlue, leftWaveform)
            Spacer(Modifier.height(4.dp))
            WaveformRow("右耳", AppGreen, rightWaveform)
        }
    }
}

@Composable
private fun WaveformRow(
    label: String,
    color: Color,
    waveform: List<AudioWaveformBar>,
) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(38.dp),
        ) {
            val center = size.height / 2f
            drawLine(AppOutline, Offset(0f, center), Offset(size.width, center), 1f)
            val drawHeight = center * 0.9f
            waveform.forEachIndexed { index, bar ->
                val x = if (waveform.size == 1) {
                    size.width / 2f
                } else {
                    size.width * index / waveform.lastIndex.toFloat()
                }
                drawLine(
                    color = color.copy(alpha = 0.82f),
                    start = Offset(x, center - bar.maximum * drawHeight),
                    end = Offset(x, center - bar.minimum * drawHeight),
                    strokeWidth = 2f,
                )
            }
        }
    }
}

@Composable
private fun StereoFrequencySpectra(
    leftSpectrum: List<AudioSpectrumBar>,
    rightSpectrum: List<AudioSpectrumBar>,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = Color.White,
        border = androidx.compose.foundation.BorderStroke(1.dp, AppOutline),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp)) {
            FrequencySpectrumRow("Left frequency spectrum", AppBlue, leftSpectrum)
            Spacer(Modifier.height(8.dp))
            FrequencySpectrumRow("Right frequency spectrum", AppGreen, rightSpectrum)
        }
    }
}

@Composable
private fun FrequencySpectrumRow(
    label: String,
    color: Color,
    spectrum: List<AudioSpectrumBar>,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            if (spectrum.isEmpty()) {
                Text(
                    text = "等待真实 PCM",
                    color = AppMutedText,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(Color(0xFF10141A)),
        ) {
            val gridColor = Color.White.copy(alpha = 0.12f)
            for (line in 1..3) {
                val y = size.height * line / 4f
                drawLine(gridColor, Offset(0f, y), Offset(size.width, y), 1f)
            }
            if (spectrum.isNotEmpty()) {
                val slotWidth = size.width / spectrum.size
                val barWidth = (slotWidth * 0.68f).coerceAtLeast(1f)
                spectrum.forEachIndexed { index, bar ->
                    val x = slotWidth * (index + 0.5f)
                    val top = size.height * (1f - bar.level.coerceIn(0f, 1f))
                    drawLine(
                        color = color.copy(alpha = 0.92f),
                        start = Offset(x, size.height),
                        end = Offset(x, top),
                        strokeWidth = barWidth,
                    )
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("0 Hz", color = AppMutedText, style = MaterialTheme.typography.bodySmall)
            Text("8 kHz", color = AppMutedText, style = MaterialTheme.typography.bodySmall)
            Text("16 kHz", color = AppMutedText, style = MaterialTheme.typography.bodySmall)
        }
    }
}
