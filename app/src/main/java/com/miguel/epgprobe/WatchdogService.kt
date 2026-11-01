package com.miguel.epgprobe

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings

/**
 * Vigila enabled_accessibility_services y vuelve a añadir ProbeService
 * si el sistema (o cualquier otro) lo quita.
 */
class WatchdogService : Service() {

    companion object {
        private const val CHANNEL = "watchdog"
        private const val CHECK_MS = 3000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private val target by lazy { ComponentName(this, ProbeService::class.java) }

    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            ensureEnabled()
        }
    }

    // Respaldo por si el sistema no avisa del cambio
    private val tick = object : Runnable {
        override fun run() {
            ensureEnabled()
            handler.postDelayed(this, CHECK_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        startForeground(1, buildNotification())
        contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
            false,
            observer
        )
        handler.post(tick)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        contentResolver.unregisterContentObserver(observer)
        super.onDestroy()
    }

    private fun ensureEnabled() {
        try {
            val cr = contentResolver
            val current = Settings.Secure.getString(
                cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""
            val list = current.split(":").filter { it.isNotBlank() }
            val present = list.any { ComponentName.unflattenFromString(it) == target }

            if (!present) {
                val updated = (list + target.flattenToShortString()).joinToString(":")
                Settings.Secure.putString(
                    cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, updated
                )
            }
            if (Settings.Secure.getInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 0) != 1) {
                Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            }
        } catch (_: SecurityException) {
            // Sin el permiso WRITE_SECURE_SETTINGS concedido por ADB
        }
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Vigilante", NotificationManager.IMPORTANCE_MIN)
            )
            return Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.sym_def_app_icon)
                .setContentTitle("EpgProbe activo")
                .build()
        }
        @Suppress("DEPRECATION")
        return Notification.Builder(this)
            .setSmallIcon(android.R.drawable.sym_def_app_icon)
            .setContentTitle("EpgProbe activo")
            .build()
    }
}
