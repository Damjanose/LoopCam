package expo.modules.loopcamrecorder

import android.Manifest
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.Log
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
  private val protectedIds = mutableSetOf<String>()

  override fun definition() = ModuleDefinition {
    Name("LoopcamRecorder")

    Events("onStateChange", "onClipFinished", "onSaved", "onStorageWarning", "onError")

    OnCreate {
      live = this@LoopcamRecorderModule
      // §7.2 — a temp session that survived a process death is orphaned by
      // definition; sweep before anything else touches the buffer.
      storage.cleanupOrphanedSessions()
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
      requireController().configure(config)
    }

    Function("getConfig") {
      (controller?.currentConfig() ?: RecorderConfig()).toMap()
    }

    AsyncFunction("requestPermissions") { promise: Promise ->
      // Only camera and mic gate recording. POST_NOTIFICATIONS is asked for at
      // the same time because the foreground service is invisible without it,
      // but a refusal must not block the drive. FOREGROUND_SERVICE_CAMERA is
      // install-time, not runtime — asking for it here would resolve false and
      // lock Play out entirely.
      val required = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
      val optional = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(Manifest.permission.POST_NOTIFICATIONS)
      } else {
        emptyArray()
      }
      // Location is deliberately not requested: the GPS sidecar (§7.1) is not in
      // this release, and a permission the app never uses is a Play policy
      // problem. Re-add ACCESS_FINE_LOCATION here together with the sidecar.
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
     * PLAY — the foreground service must be up *before* the camera binds, since
     * it is the LifecycleOwner that keeps the session alive in the background
     * (§5.1).
     */
    AsyncFunction("start") { promise: Promise ->
      RecordingService.startAndAwait(context) { service ->
        if (service == null) {
          promise.reject(
            "ERR_SERVICE_START",
            "The LoopCam recording service did not start",
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
        File(URI(clip.uri)).delete()
        clip.metadataUri?.let { File(URI(it)).delete() }
      }
    }

    AsyncFunction("setClipProtected") { id: String, isProtected: Boolean ->
      // TODO(phase-4): persist this alongside the sidecar so it survives a
      // restart; an in-memory set is enough to wire the UI for now.
      if (isProtected) protectedIds.add(id) else protectedIds.remove(id)
    }

    AsyncFunction("getStorageStatus") {
      val clips = savedClips()
      storage.storageStatus(clips.size, clips.sumOf { it.sizeBytes }).toMap()
    }

    AsyncFunction("cleanupOrphanedClips") {
      storage.cleanupOrphanedSessions()
    }

    View(LoopcamRecorderView::class) {
      Prop("lens") { view: LoopcamRecorderView, lens: String ->
        view.setLens(lens)
      }
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

  override fun onSaved(clip: SavedClip) = sendEvent("onSaved", clip.toMap())

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
    ).also { controller = it }
  }

  private fun idleStatus(): BufferStatus {
    val config = controller?.currentConfig() ?: RecorderConfig()
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
        isProtected = protectedIds.contains(file.nameWithoutExtension),
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
