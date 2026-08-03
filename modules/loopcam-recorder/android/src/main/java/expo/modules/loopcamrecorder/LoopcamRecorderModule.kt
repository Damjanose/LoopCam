package expo.modules.loopcamrecorder

import android.Manifest
import android.os.Build
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
  private val protectedIds = mutableSetOf<String>()

  override fun definition() = ModuleDefinition {
    Name("LoopcamRecorder")

    Events("onStateChange", "onClipFinished", "onSaved", "onStorageWarning", "onError")

    OnCreate {
      // §7.2 — a temp session that survived a process death is orphaned by
      // definition; sweep before anything else touches the buffer.
      storage.cleanupOrphanedSessions()
    }

    OnDestroy {
      controller?.release()
      controller = null
    }

    AsyncFunction("configure") { config: RecorderConfig ->
      requireController().configure(config)
    }

    Function("getConfig") {
      (controller?.currentConfig() ?: RecorderConfig()).toMap()
    }

    AsyncFunction("requestPermissions") { promise: Promise ->
      val permissions = buildList {
        add(Manifest.permission.CAMERA)
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
          add("android.permission.FOREGROUND_SERVICE_CAMERA")
        }
      }.toTypedArray()
      // TODO(phase-2): also prompt for ACCESS_FINE_LOCATION when GPS tagging is
      // on, and deep-link OEM battery-whitelist screens on first run (§10).
      val permissionsManager = appContext.permissions
      if (permissionsManager == null) {
        promise.resolve(false)
      } else {
        permissionsManager.askForPermissions({ result ->
          promise.resolve(result.values.all { it.status == PermissionsStatus.GRANTED })
        }, *permissions)
      }
    }

    /** PLAY. */
    AsyncFunction("start") {
      RecordingService.start(context)
      val segmentController = requireController()
      segmentController.start()
      segmentController.status().toMap()
    }

    /** STOP. */
    AsyncFunction("stop") {
      val segmentController = requireController()
      segmentController.stop()
      RecordingService.stop(context)
      segmentController.status().toMap()
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
      requireController().status().toMap()
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

  override fun onClipFinished(status: BufferStatus) = sendEvent("onClipFinished", status.toMap())

  override fun onSaved(clip: SavedClip) = sendEvent("onSaved", clip.toMap())

  override fun onError(code: RecorderErrorCode, message: String) =
    sendEvent("onError", mapOf("code" to code.jsValue, "message" to message))

  // --- wiring -------------------------------------------------------------

  private fun requireController(): SegmentController {
    controller?.let { return it }
    // TODO(phase-2): once RecordingService owns the capture session, use the
    // service as the LifecycleOwner so recording survives the Activity going
    // away — binding to the Activity only holds while the app is foregrounded.
    val owner = appContext.currentActivity as? LifecycleOwner
      ?: throw Exceptions.MissingActivity()
    return SegmentController(
      storage = storage,
      recorder = CameraXSegmentRecorder(context, owner),
      merger = ClipMerger(storage),
      listener = this,
    ).also { controller = it }
  }

  /**
   * §7.1 — the saved directory *is* the index: one .mp4 plus an optional .json
   * sidecar per incident. No database to fall out of sync with disk.
   */
  private fun savedClips(): List<SavedClip> {
    val files = storage.savedRoot.listFiles { f: File -> f.extension == "mp4" } ?: return emptyList()
    return files.sortedByDescending { it.lastModified() }.map { file ->
      val sidecar = storage.metadataFileFor(file)
      SavedClip(
        id = file.nameWithoutExtension,
        uri = file.toURI().toString(),
        metadataUri = if (sidecar.exists()) sidecar.toURI().toString() else null,
        createdAtMs = file.lastModified(),
        // TODO(phase-3): read the real duration via MediaMetadataRetriever.
        durationSec = 0.0,
        sizeBytes = file.length(),
        isProtected = protectedIds.contains(file.nameWithoutExtension),
        trigger = SaveTrigger.MANUAL,
      )
    }
  }

  companion object {
    /** Shared with [RecordingService] so notification actions hit the live loop. */
    @JvmStatic
    var controller: SegmentController? = null
      internal set
  }
}
