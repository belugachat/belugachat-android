package com.belugachat.app.chat

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.belugachat.app.MainActivity
import com.belugachat.app.R
import com.belugachat.app.container
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps the process in the foreground while an answer streams. Without it, locking the screen or
 * switching apps lets Android freeze the app and cut its network, which kills the stream mid-response.
 */
class GenerationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watcher: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("InlinedApi") // ServiceCompat ignores the type below API 29.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must run first on every start, or the system kills the app for not going foreground in time.
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (wakeLock == null) {
            wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BelugaChat:generation")
                .apply { setReferenceCounted(false); acquire(MAX_HOLD_MS) }
        }
        if (watcher == null) {
            watcher = scope.launch {
                // The grace period covers regenerate, where one run ends just before the next begins.
                container.engine.live.map { it.isEmpty() }.distinctUntilChanged().collectLatest { idle ->
                    if (idle) { delay(STOP_GRACE_MS); stopSelf() }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) = stopSelf()

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    private fun notification() = ensureChannel().let { NotificationCompat.Builder(this, CHANNEL_ID) }
        .setSmallIcon(R.drawable.ic_stat_generating)
        .setContentTitle("Writing a response…")
        .setContentText("Keeps going while the app is in the background.")
        .setOngoing(true)
        .setSilent(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .setContentIntent(
            PendingIntent.getActivity(
                this, 0,
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        .build()

    private fun ensureChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Responses in progress", NotificationManager.IMPORTANCE_LOW),
        )
    }

    companion object {
        private const val CHANNEL_ID = "generation"
        private const val NOTIFICATION_ID = 1
        private const val MAX_HOLD_MS = 60 * 60 * 1000L
        private const val STOP_GRACE_MS = 1_000L

        /** Called when a run starts; that is always a user action, so the app is in the foreground. */
        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, GenerationService::class.java)) }
        }
    }
}
