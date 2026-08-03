package expo.modules.loopcamrecorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService

/**
 * §5.1 — the foreground service is what makes "put the phone down and it keeps
 * recording" true on Android. The persistent notification is the price the OS
 * charges for not killing the process opportunistically.
 *
 * It is a [LifecycleService] because CameraX binds its use cases to a
 * LifecycleOwner, and the service — not an Activity — is what must outlive the
 * app going to the background.
 */
class RecordingService : LifecycleService() {

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    super.onStartCommand(intent, flags, startId)

    when (intent?.action) {
      ACTION_STOP -> {
        LoopcamRecorderModule.controller?.stop()
        stopSelf()
        return START_NOT_STICKY
      }
      ACTION_SAVE -> {
        LoopcamRecorderModule.controller?.save(SaveTrigger.MANUAL) { }
        return START_STICKY
      }
    }

    startForegroundCompat()
    return START_STICKY
  }

  private fun startForegroundCompat() {
    createChannel()
    val notification = buildNotification(elapsedSec = 0.0)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      // Android 14+ requires the declared type to match the manifest and the
      // matching FOREGROUND_SERVICE_CAMERA runtime permission (§5.1).
      var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
      }
      startForeground(NOTIFICATION_ID, notification, type)
    } else {
      startForeground(NOTIFICATION_ID, notification)
    }
  }

  /**
   * Kept deliberately cheap and refreshed at most once per second (§6) — a
   * notification rebuild per frame would itself be a measurable battery cost.
   */
  private fun buildNotification(elapsedSec: Double): Notification {
    val minutes = (elapsedSec / 60).toInt()
    val seconds = (elapsedSec % 60).toInt()
    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setContentTitle("LoopCam is recording")
      .setContentText(String.format("%02d:%02d buffered", minutes, seconds))
      .setSmallIcon(android.R.drawable.presence_video_online)
      .setOngoing(true)
      .setPriority(NotificationCompat.PRIORITY_LOW)
      .setCategory(NotificationCompat.CATEGORY_SERVICE)
      .addAction(0, "Save", actionIntent(ACTION_SAVE))
      .addAction(0, "Stop", actionIntent(ACTION_STOP))
      .build()
  }

  private fun actionIntent(action: String): PendingIntent {
    val intent = Intent(this, RecordingService::class.java).setAction(action)
    return PendingIntent.getService(
      this,
      action.hashCode(),
      intent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
  }

  private fun createChannel() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val manager = getSystemService(NotificationManager::class.java)
    if (manager.getNotificationChannel(CHANNEL_ID) != null) return
    manager.createNotificationChannel(
      NotificationChannel(CHANNEL_ID, "Recording", NotificationManager.IMPORTANCE_LOW).apply {
        description = "Shown while LoopCam is holding a rolling buffer"
        setShowBadge(false)
      }
    )
  }

  companion object {
    private const val CHANNEL_ID = "loopcam.recording"
    private const val NOTIFICATION_ID = 4201
    const val ACTION_STOP = "expo.modules.loopcamrecorder.STOP"
    const val ACTION_SAVE = "expo.modules.loopcamrecorder.SAVE"

    fun start(context: Context) {
      val intent = Intent(context, RecordingService::class.java)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(intent)
      } else {
        context.startService(intent)
      }
    }

    fun stop(context: Context) {
      context.stopService(Intent(context, RecordingService::class.java))
    }
  }
}
