package com.seriecast.app.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.seriecast.app.R
import com.seriecast.app.SerieCastApp
import com.seriecast.app.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Keeps the DLNA server alive while the user watches on the television.
 *
 * Foreground service type `specialUse` (declared in the manifest): the work is neither a data sync
 * nor local playback, it is serving media over the LAN.
 */
class DlnaService : android.app.Service() {

    private var scope: CoroutineScope? = null
    private var ticker: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                shutdown()
                return START_NOT_STICKY
            }

            else -> {
                if (DlnaController.state.value is DlnaController.State.Running) {
                    startForegroundSafely()
                    startTicker()
                    return START_STICKY
                }
                when (val started = DlnaController.start(this)) {
                    is DlnaController.State.Running -> {
                        startForegroundSafely()
                        startTicker()
                    }

                    is DlnaController.State.Failed -> {
                        // Nothing to serve. Still satisfy the startForegroundService contract before
                        // stopping, otherwise Android 8+ kills the process for the missing notification.
                        notificationFailure = started.reason
                        startForegroundSafely()
                        stopForegroundCompat()
                        stopSelf()
                    }

                    DlnaController.State.Stopped -> stopSelf()
                }
            }
        }
        return START_STICKY
    }

    private fun startForegroundSafely() {
        val notification = buildNotification()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            startForeground(
                SerieCastApp.NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(SerieCastApp.NOTIFICATION_ID, notification)
        }
    }

    private fun startTicker() {
        if (ticker?.isActive == true) return
        val coroutineScope = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scope = it }
        ticker = coroutineScope.launch {
            while (isActive) {
                delay(TICK_MS)
                DlnaController.refreshLibrary()
                val manager = getSystemService(android.app.NotificationManager::class.java)
                manager?.notify(SerieCastApp.NOTIFICATION_ID, buildNotification())
            }
        }
    }

    private fun buildNotification(): Notification {
        val running = DlnaController.state.value as? DlnaController.State.Running
        val failed = DlnaController.state.value as? DlnaController.State.Failed
        val videos = running?.info?.videos ?: 0
        val address = running?.let { "http://${it.info.host}:${it.info.port}" }
            ?: failed?.reason
            ?: "iniciando…"
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, SerieCastApp.NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(getString(R.string.notification_title, videos))
            .setContentText(getString(R.string.notification_text, address))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openApp)
            .build()
    }

    private fun shutdown() {
        ticker?.cancel()
        ticker = null
        DlnaController.stop()
        stopForegroundCompat()
        stopSelf()
    }

    private fun stopForegroundCompat() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    override fun onDestroy() {
        ticker?.cancel()
        scope?.cancel()
        scope = null
        DlnaController.stop()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.seriecast.app.action.DLNA_START"
        const val ACTION_STOP = "com.seriecast.app.action.DLNA_STOP"
        private const val TICK_MS = 5_000L

        /** Last failure reason, so the UI can explain why the service refused to start. */
        @Volatile
        var notificationFailure: String? = null
            private set

        fun start(context: Context) {
            val intent = Intent(context, DlnaService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, DlnaService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }

        fun clearFailure() {
            notificationFailure = null
        }
    }
}
