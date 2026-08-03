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

  /**
   * Remembered so an action can redraw the notification without the counter
   * snapping back to 00:00 — the buffered figure only arrives on a clip
   * boundary (§6), which may be seconds away.
   */
  private var lastBufferedSec = 0.0

  /** Transient line under the title: "Saving…", "Clip saved", an error. */
  private var banner: String? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    super.onStartCommand(intent, flags, startId)

    val action = intent?.action
    if (action == ACTION_STOP || action == ACTION_SAVE) {
      // An action can only arrive from a notification this service posted, so
      // reaching here on a *fresh* instance means the process died and the
      // notification outlived it. There is no loop to act on, and this instance
      // was never foregrounded, so leave rather than trip the FGS timeout.
      if (live !== this) {
        stopSelf()
        return START_NOT_STICKY
      }
      if (action == ACTION_STOP) handleStopAction() else handleSaveAction()
      return START_NOT_STICKY
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

  // --- notification actions ------------------------------------------------

  /**
   * STOP from the lock screen must run the *same* teardown as the in-app Stop:
   * releasing the camera, draining the buffer, and telling JS. Critically the
   * service may only die once that has finished — this is the LifecycleOwner
   * CameraX is bound to, so calling [stopSelf] first pulls the camera out from
   * under a stop that is still in flight.
   */
  private fun handleStopAction() {
    banner = "Stopping…"
    refreshNotification()
    val module = LoopcamRecorderModule.live
    if (module == null) {
      // No JS module attached (a reload, say) — the loop is already orphaned.
      stopSelf()
      return
    }
    module.stopFromNotification { mainHandler.post { stopSelf() } }
  }

  /**
   * SAVE cuts the in-flight clip and waits for it to finalize (§2.3), which can
   * take until the next clip boundary. With the phone locked the notification is
   * the only feedback there is, so it has to say something immediately —
   * otherwise a working Save is indistinguishable from a dead button.
   */
  private fun handleSaveAction() {
    val module = LoopcamRecorderModule.live
    if (module == null) {
      banner = "Can't save right now"
      refreshNotification()
      return
    }
    banner = "Saving…"
    refreshNotification()
    module.saveFromNotification { result ->
      mainHandler.post {
        result
          .onSuccess {
            // The window was just handed to the merge, so the buffer really is
            // empty again — reporting anything else would be a lie.
            lastBufferedSec = 0.0
            banner = "Clip saved"
          }
          .onFailure { banner = it.message ?: "Save failed" }
        refreshNotification()
      }
    }
  }

  private fun refreshNotification() {
    val manager = getSystemService(NotificationManager::class.java) ?: return
    runCatching { manager.notify(NOTIFICATION_ID, buildNotification(lastBufferedSec)) }
      .onFailure { Log.w(TAG, "Could not refresh the recording notification", it) }
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
    lastBufferedSec = bufferedSec
    val minutes = (bufferedSec / 60).toInt()
    val seconds = (bufferedSec % 60).toInt()
    val buffered = String.format("%02d:%02d buffered", minutes, seconds)
    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setContentTitle("LoopCam is recording")
      .setContentText(banner?.let { "$it · $buffered" } ?: buffered)
      .setSmallIcon(android.R.drawable.presence_video_online)
      .setOngoing(true)
      .setPriority(NotificationCompat.PRIORITY_LOW)
      .setCategory(NotificationCompat.CATEGORY_SERVICE)
      // Without this the lock screen redacts the whole thing to "LoopCam:
      // notification hidden" — including the two buttons, which are the entire
      // point of the notification while driving.
      .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
      // Android 12+ otherwise defers the first foreground-service notification
      // by ~10s; lock the phone inside that window and the controls never show.
      .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
      .setContentIntent(openAppIntent())
      // A zero icon leaves the action without an IconCompat, which some OEM
      // lock screens drop entirely rather than falling back to the label.
      .addAction(android.R.drawable.ic_menu_save, "Save", actionIntent(ACTION_SAVE))
      .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", actionIntent(ACTION_STOP))
      .build()
  }

  /**
   * Tapping the body reopens LoopCam. MainActivity is `singleTask`, so the
   * launcher intent brings the existing task forward rather than stacking a
   * second copy on top of a live recording session.
   */
  private fun openAppIntent(): PendingIntent? {
    val launch = packageManager.getLaunchIntentForPackage(packageName)?.apply {
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    } ?: return null
    return PendingIntent.getActivity(
      this,
      REQUEST_OPEN_APP,
      launch,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
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
    // A channel created by an earlier build carries the old (private) lock
    // screen visibility forever — createNotificationChannel cannot change it
    // once it exists — so the fix ships under a new channel id.
    manager.deleteNotificationChannel(LEGACY_CHANNEL_ID)
    if (manager.getNotificationChannel(CHANNEL_ID) != null) return
    manager.createNotificationChannel(
      NotificationChannel(CHANNEL_ID, "Recording", NotificationManager.IMPORTANCE_LOW).apply {
        description = "Shown while LoopCam is holding a rolling buffer"
        setShowBadge(false)
        lockscreenVisibility = Notification.VISIBILITY_PUBLIC
      }
    )
  }

  companion object {
    private const val TAG = "LoopCam/Service"
    private const val CHANNEL_ID = "loopcam.recording.v2"
    private const val LEGACY_CHANNEL_ID = "loopcam.recording"
    private const val NOTIFICATION_ID = 4201
    private const val REQUEST_OPEN_APP = 1
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
        // A clip boundary is the natural expiry for "Clip saved" / an error —
        // the counter moving is proof enough that the loop is alive.
        service.banner = null
        service.lastBufferedSec = bufferedSec
        service.refreshNotification()
      }
    }
  }
}
