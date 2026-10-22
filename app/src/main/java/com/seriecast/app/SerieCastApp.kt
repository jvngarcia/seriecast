package com.seriecast.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform

/**
 * Starts Python once per process and publishes whether it worked.
 *
 * Chaquopy is initialized eagerly because the first `Python.getInstance()` call otherwise happens
 * on a background download thread, where a failure is much harder to explain to the user.
 */
class SerieCastApp : Application() {

    override fun onCreate() {
        super.onCreate()
        startPython()
        createNotificationChannel()
    }

    private fun startPython() {
        try {
            if (!Python.isStarted()) {
                Python.start(AndroidPlatform(this))
            }
            pythonAvailable = true
            pythonError = null
            Log.i(TAG, "Chaquopy started: ${Python.getInstance().getModule("sys").callAttr("version").toString()}")
        } catch (t: Throwable) {
            pythonAvailable = false
            pythonError = "${t.javaClass.simpleName}: ${t.message}"
            Log.e(TAG, "Chaquopy failed to start", t)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Mantiene el servidor DLNA activo mientras mirás un episodio"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val TAG = "SerieCastApp"
        const val NOTIFICATION_CHANNEL_ID = "dlna_server"
        const val NOTIFICATION_ID = 4242

        @Volatile
        var pythonAvailable: Boolean = false
            private set

        @Volatile
        var pythonError: String? = null
            private set
    }
}
