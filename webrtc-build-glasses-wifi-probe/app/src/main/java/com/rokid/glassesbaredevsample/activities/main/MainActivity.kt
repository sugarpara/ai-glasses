package com.rokid.glassesbaredevsample.activities.main

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.rokid.glassesbaredevsample.BuildConfig
import com.rokid.glassesbaredevsample.activities.audio.AudioScreen
import com.rokid.glassesbaredevsample.activities.audio.AudioViewModel
import com.rokid.glassesbaredevsample.activities.imu.ImuScreen
import com.rokid.glassesbaredevsample.activities.keys.KeysWearScreen
import com.rokid.glassesbaredevsample.activities.keys.KeysWearViewModel
import com.rokid.glassesbaredevsample.activities.photo.PhotoScreen
import com.rokid.glassesbaredevsample.activities.photo.PhotoViewModel
import com.rokid.glassesbaredevsample.activities.video.VideoScreen
import com.rokid.glassesbaredevsample.activities.video.VideoViewModel
import com.rokid.glassesbaredevsample.input.BareGlassesInputDispatcher
import com.rokid.glassesbaredevsample.input.LocalBareGlassesInputDispatcher
import com.rokid.glassesbaredevsample.navigation.BareSceneRoutes
import com.rokid.glassesbaredevsample.provisioning.GlassesSessionService
import com.rokid.glassesbaredevsample.ui.design.GlassesDisplayFrame
import com.rokid.glassesbaredevsample.ui.theme.GlassesBareDevSampleTheme
import com.rokid.glassesbaredevsample.ui.theme.PitchBlack

class MainActivity : ComponentActivity() {
    private val keysWearViewModel by viewModels<KeysWearViewModel>()
    private val audioViewModel by viewModels<AudioViewModel>()
    private val photoViewModel by viewModels<PhotoViewModel>()
    private val videoViewModel by viewModels<VideoViewModel>()

    private var keyDispatcher: BareGlassesInputDispatcher? = null

    private val recordAudioPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            audioViewModel.onRecordAudioPermissionResult(granted)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(
            TAG,
            "Activity created version=${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE})",
        )
        streamingActivityActive = true
        setupFullscreen()
        val dispatcher = BareGlassesInputDispatcher(applicationContext)
        keyDispatcher = dispatcher
        handleLaunchIntent(intent, "activity-created")
        audioViewModel.refreshPermission()
        setContent {
            val sessionState by GlassesSessionService.state.collectAsStateWithLifecycle()
            val keepScreenOn = sessionState.isOpenAndWorn && !sessionState.cameraOwnedBySession
            DisposableEffect(keepScreenOn) {
                if (keepScreenOn) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                onDispose { }
            }
            GlassesBareDevSampleTheme {
                CompositionLocalProvider(LocalBareGlassesInputDispatcher provides dispatcher) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(PitchBlack),
                    ) {
                        GlassesDisplayFrame {
                            BareNavApp(
                                keysWearViewModel = keysWearViewModel,
                                audioViewModel = audioViewModel,
                                photoViewModel = photoViewModel,
                                videoViewModel = videoViewModel,
                                // StreamingForegroundService is the sole CameraX owner for WebRTC.
                                cameraEnabled = false,
                                onRequestRecordAudio = {
                                    recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_ENTER -> return true
            KeyEvent.KEYCODE_BACK -> {
                keyDispatcher?.dispatchBackKey()
                return true
            }
            KeyEvent.KEYCODE_PROG_BLUE -> {
                keyDispatcher?.dispatchLongKey()
                return true
            }
            KeyEvent.KEYCODE_SETTINGS -> {
                keyDispatcher?.consumeSystemKey("Key·SETTINGS")
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_ENTER && event != null && event.repeatCount == 0) {
            keyDispatcher?.dispatchEnterKey()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onDestroy() {
        streamingActivityActive = false
        keyDispatcher?.unregister(applicationContext)
        keyDispatcher = null
        super.onDestroy()
    }

    private fun setupFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onResume() {
        super.onResume()
        streamingActivityActive = true
        Log.i(TAG, "Activity resumed")
        GlassesSessionService.ensureStarted(
            applicationContext,
            reason = "activity-resumed",
        )
    }

    override fun onStop() {
        Log.i(TAG, "Activity stopped")
        streamingActivityActive = false
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        Log.i(TAG, "New provisioning intent received")
        handleLaunchIntent(intent, "activity-new-intent")
    }

    private fun handleLaunchIntent(source: Intent, reason: String) {
        if (GlassesSessionService.hasConnectionPayload(source)) {
            GlassesSessionService.requestConnection(applicationContext, source, reason)
        } else {
            GlassesSessionService.ensureStarted(applicationContext, reason)
        }
    }

    companion object {
        const val TAG = "GlassesMain"

        @Volatile
        private var streamingActivityActive = false

        fun isStreamingActivityActive(): Boolean = streamingActivityActive
    }
}

@Composable
private fun BareNavApp(
    keysWearViewModel: KeysWearViewModel,
    audioViewModel: AudioViewModel,
    photoViewModel: PhotoViewModel,
    videoViewModel: VideoViewModel,
    cameraEnabled: Boolean,
    onRequestRecordAudio: () -> Unit,
) {
    val nav = rememberNavController()
    NavHost(
        navController = nav,
        startDestination = BareSceneRoutes.PHOTO,
        modifier = Modifier.fillMaxSize(),
    ) {
        composable(BareSceneRoutes.HUB) {
            HubScreen(onNavigate = { route -> nav.navigate(route) })
        }
        composable(BareSceneRoutes.KEYS_WEAR) {
            KeysWearScreen(onBack = { nav.popBackStack() }, viewModel = keysWearViewModel)
        }
        composable(BareSceneRoutes.AUDIO) {
            AudioScreen(
                onBack = { nav.popBackStack() },
                viewModel = audioViewModel,
                onRequestRecordAudio = onRequestRecordAudio,
            )
        }
        composable(BareSceneRoutes.PHOTO) {
            PhotoScreen(
                onBack = { nav.popBackStack() },
                viewModel = photoViewModel,
                streamingEnabled = cameraEnabled,
            )
        }
        composable(BareSceneRoutes.VIDEO) {
            VideoScreen(onBack = { nav.popBackStack() }, viewModel = videoViewModel)
        }
        composable(BareSceneRoutes.IMU) {
            ImuScreen(onBack = { nav.popBackStack() })
        }
    }
}
