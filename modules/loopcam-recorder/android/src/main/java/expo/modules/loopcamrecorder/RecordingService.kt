package expo.modules.loopcamrecorder

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService

/**
 * §5.1 — the foreground service is what makes "put the phone down and it keeps
 * recording" true on Android. The persistent notification is the price the OS
 * charges for not killing the process opportunistically.
 *
 * It is a [LifecycleService] because CameraX binds its use cases to a
 * LifecycleOwner, and the service — not an Activity — is what must outlive the
 * app going to the background. That makes the service instance itself the thing
 * the recorder needs, which is why [startAndAwait] hands it back rather than
 * just firing an intent: binding the camera before the service is foregrounded
 * is how you get a camera the OS immediately takes away again.
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
        return START_NOT_STICKY
      }
    }

    startForegroundCompat()

    // Only now is the lifecycle STARTED and the process exempt from background
    // camera restrictions, so this is the earliest safe moment to bind.
    live = this
    val waiting = pending.toList()
    pending.clear()
    waiting.forEach { it(this) }

    // Deliberately not sticky: after a process death the SegmentController and
    // its ring buffer are gone, so a restarted service would be a notification
    // with nothing behind it. The user restarts the loop with Play.
    return START_NOT_STICKY
  }

  override fun onDestroy() {
    live = null
    super.onDestroy()
  }

  private fun startForegroundCompat() {
    createChannel()
    val notification = buildNotification(bufferedSec = 0.0)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      startForeground(NOTIFICATION_ID, notification, foregroundTypes())
    } else {
      startForeground(NOTIFICATION_ID, notification)
    }
  }

  /**
   * Android 14+ rejects a foreground-service type whose backing runtime
   * permission is missing, so the declared types are derived from what is
   * actually granted rather than assumed.
   */
  private fun foregroundTypes(): Int {
    var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && granted(Manifest.permission.RECORD_AUDIO)) {
      types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
    }
    return types
  }

  private fun granted(permission: String) =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

  /**
   * Kept deliberately cheap and refreshed only on a clip boundary (§6) — a
   * notification rebuild per frame would itself be a measurable battery cost.
   */
  private fun buildNotification(bufferedSec: Double): Notification {
    val minutes = (bufferedSec / 60).toInt()
    val seconds = (bufferedSec % 60).toInt()
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
    private const val TAG = "LoopCam/Service"
    private const val CHANNEL_ID = "loopcam.recording"
    private const val NOTIFICATION_ID = 4201
    private const val START_TIMEOUT_MS = 10_000L
    const val ACTION_STOP = "expo.modules.loopcamrecorder.STOP"
    const val ACTION_SAVE = "expo.modules.loopcamrecorder.SAVE"

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Main-thread only, matching where [onStartCommand] runs. */
    private var live: RecordingService? = null
    private val pending = mutableListOf<(RecordingService?) -> Unit>()

    /**
     * Start the service and call back with the running instance — the
     * LifecycleOwner the camera binds to. Calls back with `null` if the service
     * never reaches the foreground, so Play fails loudly instead of hanging.
     */
    fun startAndAwait(context: Context, onReady: (RecordingService?) -> Unit) {
      mainHandler.post {
        live?.let {
          onReady(it)
          return@post
        }
        pending += onReady
        try {
          ContextCompat.startForegroundService(
            context,
            Intent(context, RecordingService::class.java),
          )
        } catch (t: Throwable) {
          Log.e(TAG, "Could not start the recording service", t)
          if (pending.remove(onReady)) onReady(null)
          return@post
        }
        mainHandler.postDelayed({
          if (pending.remove(onReady)) onReady(null)
        }, START_TIMEOUT_MS)
      }
    }

    fun stop(context: Context) {
      context.stopService(Intent(context, RecordingService::class.java))
    }

    /** Refresh the ongoing notification with how much footage Save would keep. */
    fun updateNotification(bufferedSec: Double) {
      mainHandler.post {
        val service = live ?: return@post
        val manager = service.getSystemService(NotificationManager::class.java) ?: return@post
        runCatching { manager.notify(NOTIFICATION_ID, service.buildNotification(bufferedSec)) }
          .onFailure { Log.w(TAG, "Could not refresh the recording notification", it) }
      }
    }
  }
}
