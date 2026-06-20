package me.wcy.music.listen

import android.annotation.SuppressLint
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
import me.wcy.music.R
import me.wcy.music.main.MainActivity

class ListenTogetherKeepAliveService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        ListenTogetherDebugLogger.log("keep_alive_service_on_create")
        acquireWakeLock()
        startListenTogetherForeground()
        ListenTogetherDebugLogger.log("keep_alive_service_foreground_started", mapOf(
            "notificationId" to NOTIFICATION_ID
        ))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ListenTogetherDebugLogger.log("keep_alive_service_on_start_command", mapOf(
            "action" to intent?.action.orEmpty(),
            "flags" to flags,
            "startId" to startId,
            "wakeLockHeld" to (wakeLock?.isHeld == true)
        ))
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (wakeLock?.isHeld != true) {
            acquireWakeLock()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        ListenTogetherDebugLogger.log("keep_alive_service_task_removed", mapOf(
            "rootAction" to rootIntent?.action.orEmpty()
        ))
        ListenTogetherManager.leave(notifyPeer = true)
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        ListenTogetherDebugLogger.log("keep_alive_service_on_destroy", mapOf(
            "wakeLockHeld" to (wakeLock?.isHeld == true)
        ))
        releaseWakeLock()
        super.onDestroy()
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$packageName:ListenTogetherKeepAlive"
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
        ListenTogetherDebugLogger.log("keep_alive_wakelock_acquired", mapOf(
            "held" to (wakeLock?.isHeld == true)
        ))
    }

    private fun releaseWakeLock() {
        val wasHeld = wakeLock?.isHeld == true
        runCatching {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        }
        wakeLock = null
        ListenTogetherDebugLogger.log("keep_alive_wakelock_released", mapOf(
            "wasHeld" to wasHeld
        ))
    }

    private fun buildNotification(): Notification {
        ensureChannel()
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("\u4e00\u8d77\u542c\u8fdb\u884c\u4e2d")
            .setContentText("\u4fdd\u6301\u4e00\u8d77\u542c\u8fde\u63a5")
            .setContentIntent(pendingIntent)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setPriority(Notification.PRIORITY_LOW)
            .setOngoing(true)
            .setShowWhen(false)
            .build()
    }

    private fun startListenTogetherForeground() {
        val notification = buildNotification()
        when {
            Build.VERSION.SDK_INT >= 34 -> {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
                )
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            }
            else -> {
                startForeground(NOTIFICATION_ID, notification)
            }
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val notificationManager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "\u4e00\u8d77\u542c",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "\u4fdd\u6301\u4e00\u8d77\u542c\u8fde\u63a5"
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    companion object {
        private const val ACTION_STOP = "me.wcy.music.listen.STOP_KEEP_ALIVE"
        private const val CHANNEL_ID = "listen_together_keep_alive"
        private const val NOTIFICATION_ID = 2301

        fun start(context: Context) {
            val intent = Intent(context, ListenTogetherKeepAliveService::class.java)
            ListenTogetherDebugLogger.log("keep_alive_service_start_call", mapOf(
                "sdk" to Build.VERSION.SDK_INT
            ))
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure {
                ListenTogetherDebugLogger.log("keep_alive_service_start_failed", mapOf(
                    "error" to (it.message ?: ""),
                    "throwable" to it.javaClass.name
                ))
            }
        }

        fun stop(context: Context) {
            ListenTogetherDebugLogger.log("keep_alive_service_stop_call")
            context.stopService(Intent(context, ListenTogetherKeepAliveService::class.java))
        }
    }
}
