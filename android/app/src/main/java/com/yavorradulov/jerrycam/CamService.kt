package com.yavorradulov.jerrycam

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Foreground service that keeps Jerry Cam reachable. It holds camera/microphone access but opens them only per session. */
class CamService : LifecycleService() {
    private var signaling: RtdbSignaling? = null
    private var coordinator: SessionCoordinator? = null
    private var stopObserving: (() -> Unit)? = null
    private var battery: BatteryMonitor? = null
    private var destroyed = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (state.value.running) return START_STICKY

        createChannel()
        try {
            ServiceCompat.startForeground(
                this, ONGOING_ID, notification(getString(R.string.notify_ready)),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } catch (e: Exception) {
            // A sticky restart from the background may not use the camera: ask someone at home to reopen the app.
            if (e !is SecurityException && !(Build.VERSION.SDK_INT >= 31 && e is ForegroundServiceStartNotAllowedException)) throw e
            Log.w(TAG, "can't start in the background: $e")
            postAlert(this, R.string.notify_restart)
            stopSelf()
            return START_NOT_STICKY
        }
        if (FirebaseAuth.getInstance().currentUser == null) {
            postAlert(this, R.string.notify_sign_in)
            stopSelf()
            return START_NOT_STICKY
        }

        acquireLocks()
        val signaling = RtdbSignaling(FirebaseDatabase.getInstance()).also { signaling = it }
        signaling.goOnline()
        signaling.publishHasFlash(backCameraHasFlash())
        battery = BatteryMonitor(this) { level ->
            signaling.publishBattery(level)
            state.update { it.copy(battery = level) }
        }.also { it.start() }
        lifecycleScope.launch {
            signaling.permissionDenied.collect { denied -> state.update { it.copy(wrongAccount = denied) } }
        }
        val heartbeatsAnswered = MutableStateFlow(true)
        lifecycleScope.launch {
            combine(signaling.connected, heartbeatsAnswered) { connected, answered -> connected && answered }
                .collect { online -> state.update { it.copy(online = online) } }
        }
        lifecycleScope.launch {
            watchConnection(HEARTBEAT_MS, HEARTBEAT_TIMEOUT_MS, signaling::heartbeat, signaling::reconnect) {
                heartbeatsAnswered.value = it
            }
        }
        lifecycleScope.launch {
            state.map(::notificationText).distinctUntilChanged().collect(::refreshNotification)
        }
        val turn = TurnClient(BuildConfig.TURN_KEY_ID, BuildConfig.TURN_API_TOKEN)
        val coordinator = SessionCoordinator(
            scope = lifecycleScope,
            signaling = signaling,
            fetchIceServers = turn::fetchIceServers,
            newCall = { WebRtcCall(this) },
            onLiveChanged = { live ->
                Log.i(TAG, if (live) "viewer connected" else "session ended")
                state.update { it.copy(live = live) }
            },
        ).also { coordinator = it }
        stopObserving = signaling.observeSession { snapshot ->
            state.update { it.copy(viewer = snapshot?.viewer) }
            coordinator.onSession(snapshot)
        }
        state.update { it.copy(running = true) }
        return START_STICKY
    }

    override fun onDestroy() {
        destroyed = true
        stopObserving?.invoke()
        battery?.stop()
        coordinator?.shutdown()
        signaling?.goOffline()
        wakeLock?.takeIf { it.isHeld }?.release()
        wifiLock?.takeIf { it.isHeld }?.release()
        state.value = ServiceStatus()
        super.onDestroy()
    }

    /** WebRtcCall streams from the first back camera; the flashlight button only makes sense if it has a flash. */
    private fun backCameraHasFlash(): Boolean = try {
        val cameras = getSystemService(CameraManager::class.java)
        cameras.cameraIdList
            .map(cameras::getCameraCharacteristics)
            .firstOrNull { it.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK }
            ?.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
    } catch (e: Exception) {
        false
    }

    private fun acquireLocks() {
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "JerryCam:service").apply { acquire() }
        @Suppress("DEPRECATION") // still keeps Wi-Fi awake with the screen off on the Android versions we target
        wifiLock = getSystemService(WifiManager::class.java)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "JerryCam:wifi").apply { acquire() }
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun notification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_cam)
            .setContentTitle(text)
            .setOngoing(true)
            .setContentIntent(openApp(this))
            .build()

    private fun notificationText(status: ServiceStatus): String = when {
        status.wrongAccount -> getString(R.string.notify_wrong_account)
        status.live && status.viewer != null -> getString(R.string.notify_live_viewer, status.viewer)
        status.live -> getString(R.string.notify_live)
        !status.online -> getString(R.string.notify_offline)
        else -> getString(R.string.notify_ready)
    }

    private fun refreshNotification(text: String) {
        if (destroyed) return
        getSystemService(NotificationManager::class.java).notify(ONGOING_ID, notification(text))
    }

    companion object {
        private const val CHANNEL_ID = "jerrycam"
        private const val ONGOING_ID = 1
        private const val ALERT_ID = 2

        private const val HEARTBEAT_MS = 60_000L
        private const val HEARTBEAT_TIMEOUT_MS = 20_000L

        private val state = MutableStateFlow(ServiceStatus())
        val status: StateFlow<ServiceStatus> = state

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, CamService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CamService::class.java))
        }

        /** A dismissible notification that opens the app, for when the service can't run on its own. */
        fun postAlert(context: Context, @StringRes text: Int) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW),
            )
            manager.notify(
                ALERT_ID,
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_stat_cam)
                    .setContentTitle(context.getString(text))
                    .setAutoCancel(true)
                    .setContentIntent(openApp(context))
                    .build(),
            )
        }

        private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
