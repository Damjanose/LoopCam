package expo.modules.loopcamrecorder

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import expo.modules.interfaces.permissions.PermissionsStatus
import expo.modules.kotlin.Promise
import expo.modules.kotlin.exception.Exceptions
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import java.io.File
import java.net.URI

/**
 * JS-facing surface of the recording engine (§3).
 *
 * Nothing here does timing-sensitive work: every call hands off to
 * [SegmentController], which owns the loop on its own thread. The bridge is a
 * control channel, not part of the recording path (§3.1).
 */
class LoopcamRecorderModule : Module(), SegmentController.Listener {

  private val context get() = appContext.reactContext ?: throw Exceptions.ReactContextLost()

  private val storage by lazy { StorageManager(context) }
  private val cameraRecorder by lazy { CameraXSegmentRecorder(context) }
  private val configStore by lazy { ConfigStore(context) }
  private val cameraProbe by lazy { CameraProbe(context) }

  override fun definition() = ModuleDefinition {
    Name("LoopcamRecorder")

    Events("onStateChange", "onClipFinished", "onSaved", "onStorageWarning", "onError")

    OnCreate {
      live = this@LoopcamRecorderModule
      // The tracker outlives every Activity — the foreground service keeps
      // recording with the app swiped away — so it is given the application
      // context here, once, rather than reaching for whatever is current.
      LocationTracker.attach(context)
      LocationTracker.configure(configStore.load())
      // §7.2 — a temp session that survived a process death is orphaned by
      // definition; sweep before anything else touches the buffer.
      storage.cleanupOrphanedSessions()
      // Kicked off here so the answer is ready by the time the first render
      // asks for it; the probe itself runs on its own thread.
      cameraProbe
    }

    OnDestroy {
      // A reload leaves the service and the camera bound to a controller that
      // no longer has anywhere to report; tear the whole thing down.
      if (live === this@LoopcamRecorderModule) live = null
      controller?.release()
      controller = null
      cameraRecorder.lifecycleOwner = null
      runCatching { RecordingService.stop(context) }
    }

    AsyncFunction("configure") { config: RecorderConfig ->
      // Persisted as well as applied. The service can outlive the JS side
      // (§5.1), so the stored copy is what a cold start reads — a config that
      // only lived in the controller would reset on every launch.
      configStore.save(config)
      requireController().configure(config)
    }

    /**
     * The live config if there is one, else whatever was last saved. Not the
     * type's defaults: those would tell a freshly-launched app it is set to the
     * back camera at 1080p regardless of what the user actually chose.
     */
    Function("getConfig") {
      (controller?.currentConfig() ?: configStore.load()).toMap()
    }

    Function("getCapabilities") {
      cameraProbe.capabilities()
    }

    AsyncFunction("requestPermissions") { promise: Promise ->
      // Only camera and mic gate recording. POST_NOTIFICATIONS is asked for at
      // the same time because the foreground service is invisible without it,
      // but a refusal must not block the drive. FOREGROUND_SERVICE_CAMERA is
      // install-time, not runtime — asking for it here would resolve false and
      // lock Play out entirely.
      val required = REQUIRED_PERMISSIONS
      // Asked at the same moment as camera and mic, and optional for the same
      // reason POST_NOTIFICATIONS is: a driver who refuses location still gets a
      // working dashcam, one whose footage stamps `--` where the speed would be.
      // Requesting it here rather than at Play also keeps the system dialog off
      // the screen of someone who has just started recording, quite possibly
      // while already moving.
      val optional = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
          add(Manifest.permission.POST_NOTIFICATIONS)
        }
        // Coarse alongside fine because Android 12+ shows the user a choice
        // between them, and asking for fine alone offers no approximate option
        // at all. A coarse-only grant is detected by LocationTracker and
        // reported through getLocationStatus rather than silently burning in
        // numbers from network positioning.
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
      }.toTypedArray()
      val permissionsManager = appContext.permissions
      if (permissionsManager == null) {
        promise.resolve(false)
      } else {
        permissionsManager.askForPermissions({ result ->
          promise.resolve(required.all { result[it]?.status == PermissionsStatus.GRANTED })
        }, *(required + optional))
      }
    }

    /**
     * Whether recording could start right now without a prompt. Synchronous and
     * side-effect free on purpose: it is asked on mount to decide whether the
     * viewfinder may light up, and a screen opening is not the moment to throw a
     * system dialog at someone who has not asked for anything yet.
     */
    Function("hasPermissions") {
      REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
      }
    }

    /**
     * Why the burned-in speed is reading `--`, for Settings to explain.
     *
     * A permanently blank speed field has several quite different causes — no
     * lock yet, a tunnel, a refused permission, an approximate-only grant — and
     * on a screen the driver only looks at while parked, guessing which is not
     * something to leave to them.
     */
    Function("getLocationStatus") {
      LocationTracker.status().jsValue
    }

    /**
     * Light the viewfinder without recording. No foreground service and no
     * files — this is the picture only, bound to the Activity so it goes away
     * with the screen.
     */
    AsyncFunction("startPreview") {
      val owner = appContext.currentActivity as? LifecycleOwner
        ?: throw Exceptions.MissingActivity()
      // The stored mode, so the standby picture is laid out the way a recording
      // would be — including the front-camera inset — rather than only
      // revealing the layout once Play is pressed.
      val mode = (controller?.currentConfig() ?: configStore.load()).camera
      cameraRecorder.startPreview(owner, mode)
    }

    AsyncFunction("stopPreview") {
      cameraRecorder.stopPreview()
    }

    /**
     * PLAY — the foreground service must be up *before* the camera binds, since
     * it is the LifecycleOwner that keeps the session alive in the background
     * (§5.1).
     */
    AsyncFunction("start") { promise: Promise ->
      RecordingService.startAndAwait(context) { service ->
        if (service == null) {
          promise.reject(
            "ERR_SERVICE_START",
            "The DashCam recording service did not start",
            null,
          )
          return@startAndAwait
        }
        cameraRecorder.lifecycleOwner = service
        requireController().start { result ->
          result
            .onSuccess { promise.resolve(it.toMap()) }
            .onFailure { promise.reject("ERR_START_FAILED", it.message, it) }
        }
      }
    }

    /** STOP. */
    AsyncFunction("stop") { promise: Promise ->
      val segmentController = controller
      if (segmentController == null) {
        promise.resolve(idleStatus().toMap())
        return@AsyncFunction
      }
      segmentController.stop { status ->
        cameraRecorder.lifecycleOwner = null
        RecordingService.stop(context)
        promise.resolve(status.toMap())
      }
    }

    /** SAVE — resolves once the merged file is on disk; recording never pauses. */
    AsyncFunction("save") { promise: Promise ->
      requireController().save(SaveTrigger.MANUAL) { result ->
        result
          .onSuccess { promise.resolve(it.toMap()) }
          .onFailure { promise.reject("ERR_SAVE_FAILED", it.message, it) }
      }
    }

    Function("getStatus") {
      (controller?.status() ?: idleStatus()).toMap()
    }

    AsyncFunction("listSavedClips") {
      savedClips().map { it.toMap() }
    }

    AsyncFunction("deleteSavedClip") { id: String ->
      savedClips().firstOrNull { it.id == id }?.let { clip ->
        val video = File(URI(clip.uri))
        // The marker goes with its clip; an orphan would silently protect the
        // next clip that happened to land on the same timestamped name.
        storage.protectionMarkerFor(video).delete()
        video.delete()
        clip.metadataUri?.let { File(URI(it)).delete() }
      }
    }

    AsyncFunction("setClipProtected") { id: String, isProtected: Boolean ->
      savedClips().firstOrNull { it.id == id }?.let { clip ->
        storage.setProtected(File(URI(clip.uri)), isProtected)
      }
    }

    AsyncFunction("getStorageStatus") {
      val (count, bytes) = storage.savedFootprint()
      storage.storageStatus(count, bytes).toMap()
    }

    AsyncFunction("cleanupOrphanedClips") {
      storage.cleanupOrphanedSessions()
    }

    View(LoopcamRecorderView::class) {
      // No `lens` prop: which camera records is `RecorderConfig.cameraMode`,
      // owned by the controller. A view prop that also selected it could only
      // ever be the second, disagreeing source of truth.
      Prop("resizeMode") { view: LoopcamRecorderView, mode: String ->
        view.setResizeMode(mode)
      }
    }
  }

  // --- SegmentController.Listener ----------------------------------------

  override fun onStateChanged(status: BufferStatus) = sendEvent("onStateChange", status.toMap())

  override fun onClipFinished(status: BufferStatus) {
    // The notification is the only view of the buffer once the app is
    // backgrounded, and a clip boundary is the cheapest honest moment to
    // refresh it (§6).
    RecordingService.updateNotification(status.bufferedSec)
    sendEvent("onClipFinished", status.toMap())
  }

  override fun onSaved(clip: SavedClip) {
    sendEvent("onSaved", clip.toMap())

    // A save is the only moment saved storage grows, so the only moment the
    // budget can be breached (§7.2).
    val deleted = storage.enforceBudget()
    val (count, bytes) = storage.savedFootprint()
    val status = storage.storageStatus(count, bytes)
    if (deleted.isNotEmpty() || status.lowSpaceWarning) {
      sendEvent("onStorageWarning", status.toMap() + mapOf("deletedClipIds" to deleted))
    }
  }

  override fun onError(code: RecorderErrorCode, message: String) =
    sendEvent("onError", mapOf("code" to code.jsValue, "message" to message))

  // --- notification controls ----------------------------------------------

  /**
   * The lock-screen Stop, running the same teardown as the JS `stop` — the
   * camera's LifecycleOwner has to be dropped here too, or the next Play binds
   * CameraX to a service that is on its way to being destroyed.
   *
   * [onDone] fires only once the controller has actually finished, so the
   * caller knows when it is safe to let the service die.
   */
  internal fun stopFromNotification(onDone: () -> Unit) {
    val segmentController = controller
    if (segmentController == null) {
      cameraRecorder.lifecycleOwner = null
      onDone()
      return
    }
    segmentController.stop {
      cameraRecorder.lifecycleOwner = null
      onDone()
    }
  }

  /** The lock-screen Save. JS still hears about it through `onSaved` (§3.1). */
  internal fun saveFromNotification(onResult: (Result<SavedClip>) -> Unit) {
    val segmentController = controller
    if (segmentController == null) {
      onResult(Result.failure(IllegalStateException("Not recording")))
      return
    }
    segmentController.save(SaveTrigger.MANUAL, onResult)
  }

  // --- wiring -------------------------------------------------------------

  /**
   * The controller needs no Activity: the camera's LifecycleOwner is
   * [RecordingService], handed to the recorder at Play. That also means
   * `configure` works before the first Play, which it could not when this
   * required a live Activity.
   */
  private fun requireController(): SegmentController {
    controller?.let { return it }
    return SegmentController(
      storage = storage,
      recorder = cameraRecorder,
      merger = ClipMerger(storage),
      listener = this,
    ).also {
      // Seeded from disk, not from the type's defaults: a Play issued before JS
      // has pushed anything — the notification's action, or a restart — must
      // record with the camera and tier the user actually chose.
      it.configure(configStore.load())
      controller = it
    }
  }

  private fun idleStatus(): BufferStatus {
    val config = controller?.currentConfig() ?: configStore.load()
    return BufferStatus(
      state = RecorderState.IDLE,
      clipCount = 0,
      maxClips = config.maxClips,
      bufferedSec = 0.0,
      bufferedBytes = 0L,
      elapsedSec = 0.0,
    )
  }

  /**
   * §7.1 — the saved directory *is* the index: one .mp4 plus an optional .json
   * sidecar per incident. No database to fall out of sync with disk.
   */
  private fun savedClips(): List<SavedClip> {
    // Empty files are skipped rather than listed: a merge interrupted by a
    // process death can still leave one, and an unplayable row in the gallery
    // is worse than a missing one.
    val files = storage.savedRoot.listFiles { f: File -> f.extension == "mp4" && f.length() > 0L }
      ?: return emptyList()
    return files.sortedByDescending { it.lastModified() }.map { file ->
      val sidecar = storage.metadataFileFor(file)
      SavedClip(
        id = file.nameWithoutExtension,
        uri = file.toURI().toString(),
        metadataUri = if (sidecar.exists()) sidecar.toURI().toString() else null,
        createdAtMs = file.lastModified(),
        durationSec = durationSecOf(file),
        sizeBytes = file.length(),
        isProtected = storage.isProtected(file),
        trigger = SaveTrigger.MANUAL,
      )
    }
  }

  /**
   * The container is the only honest source for how long a saved clip runs: the
   * merge concatenates a variable number of segments, and the last one is
   * usually cut short by the Save itself, so no arithmetic over the config
   * predicts it. A file that cannot be read reports 0 rather than guessing.
   */
  private fun durationSecOf(file: File): Double {
    val retriever = MediaMetadataRetriever()
    return try {
      retriever.setDataSource(file.absolutePath)
      val ms = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
      if (ms == null) 0.0 else ms / 1000.0
    } catch (t: Throwable) {
      Log.w(TAG, "Could not read duration from ${file.name}", t)
      0.0
    } finally {
      runCatching { retriever.release() }
    }
  }

  companion object {
    private const val TAG = "LoopCam/Module"

    /** The two that actually gate capture; everything else is a nicety. */
    private val REQUIRED_PERMISSIONS =
      arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)

    /** Shared with [RecordingService] so notification actions hit the live loop. */
    @JvmStatic
    var controller: SegmentController? = null
      internal set

    /**
     * The attached module, so [RecordingService] can route a notification tap
     * through the same code the JS buttons use rather than reaching past it
     * into the controller. Null between a reload and the next OnCreate.
     */
    @JvmStatic
    internal var live: LoopcamRecorderModule? = null
  }
}
