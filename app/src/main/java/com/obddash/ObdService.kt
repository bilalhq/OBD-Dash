package com.obddash

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Foreground service that runs while the app is connected (or reconnecting) to the car.
 *
 * Without it, the connection lives in a plain background process, and Android — especially
 * OEM builds like ColorOS — kills it as soon as the user switches to another app or turns the
 * screen off, which ends the trip. The service also holds a partial wake lock so polling keeps
 * going with the screen off.
 */
class ObdService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var updates: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val controller get() = (application as ObdApp).controller

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Car connection", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while OBD Dash is connected to your car and recording a trip"
                setShowBadge(false)
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT) {
            controller.disconnect() // ObdApp stops the service once the state goes idle
            return START_NOT_STICKY
        }

        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, buildNotification(controller.state.value),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't enter foreground", e)
            stopSelf()
            return START_NOT_STICKY
        }

        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "obddash:connection")
                .apply {
                    setReferenceCounted(false)
                    acquire(8 * 60 * 60 * 1000L) // safety cap: 8 h
                }
        }

        if (updates == null) {
            updates = scope.launch {
                // Refresh the notification when the connection state changes or every ~0.1 km.
                controller.state
                    .map { Triple(it.conn, it.message, (it.tripDistanceKm * 10).toInt()) }
                    .distinctUntilChanged()
                    .collect {
                        getSystemService(NotificationManager::class.java)
                            .notify(NOTIFICATION_ID, buildNotification(controller.state.value))
                    }
            }
        }

        // Restarted by the system after being killed (intent == null): pick the connection back up.
        if (intent == null && !controller.connectLastIfIdle()) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    private fun buildNotification(s: UiState): Notification {
        val car = s.profile.carTitle.ifBlank { s.deviceName ?: "your car" }
        val title = when {
            s.demo -> "Demo mode"
            s.conn == ConnState.Connected -> "Connected to $car"
            else -> s.message ?: "Connecting to $car…"
        }
        val text = if (s.recordingTripId != null) {
            val km = s.tripDistanceKm
            "Recording trip · " + if (s.imperial) "%.1f mi".format(km * 0.621371) else "%.1f km".format(km)
        } else {
            "Tap to open the dashboard"
        }
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val disconnect = PendingIntent.getService(
            this, 1,
            Intent(this, ObdService::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_gauge)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .addAction(0, "Disconnect", disconnect)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setColor(0xFF22D3EE.toInt())
            .build()
    }

    companion object {
        private const val TAG = "ObdService"
        private const val CHANNEL = "connection"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_DISCONNECT = "com.obddash.DISCONNECT"

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, ObdService::class.java))
            } catch (e: Exception) {
                // Android 12+ refuses to start foreground services from the background. That only
                // happens if a connection starts while no part of the app is visible; the
                // connection still works, it's just not protected from being killed.
                Log.w(TAG, "Couldn't start foreground service", e)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ObdService::class.java))
        }
    }
}
