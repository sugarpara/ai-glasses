package com.rokid.glassesbaredevsample.provisioning

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors

class SignalingPortProbe(
    private val listener: Listener,
) {
    interface Listener {
        fun onReady()
        fun onFailure(message: String)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private var retryTask: Runnable? = null
    private var host: String? = null
    private var port: Int = 0
    private var deadlineMs: Long = 0L
    @Volatile
    private var active = false

    fun start(host: String, port: Int) {
        closeRetry()
        this.host = host
        this.port = port
        deadlineMs = SystemClock.elapsedRealtime() + TOTAL_TIMEOUT_MS
        active = true
        attempt()
    }

    fun close() {
        active = false
        closeRetry()
        executor.shutdownNow()
    }

    private fun attempt() {
        val targetHost = host ?: return
        if (!active) return
        executor.execute {
            val connected = runCatching {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(targetHost, port), CONNECT_TIMEOUT_MS)
                }
            }.isSuccess
            mainHandler.post {
                if (!active) return@post
                if (connected) {
                    active = false
                    Log.i(TAG, "Phone signaling port ready host=$targetHost port=$port")
                    listener.onReady()
                } else if (SystemClock.elapsedRealtime() >= deadlineMs) {
                    active = false
                    listener.onFailure("Phone signaling port $targetHost:$port is unreachable")
                } else {
                    retryTask = Runnable(::attempt).also { task ->
                        mainHandler.postDelayed(task, RETRY_DELAY_MS)
                    }
                }
            }
        }
    }

    private fun closeRetry() {
        retryTask?.let(mainHandler::removeCallbacks)
        retryTask = null
    }

    private companion object {
        const val TAG = "SignalProbe"
        const val CONNECT_TIMEOUT_MS = 1_000
        const val RETRY_DELAY_MS = 500L
        const val TOTAL_TIMEOUT_MS = 15_000L
    }
}
