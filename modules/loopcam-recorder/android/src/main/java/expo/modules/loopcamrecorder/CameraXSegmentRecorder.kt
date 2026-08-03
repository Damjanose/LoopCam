package expo.modules.loopcamrecorder

import android.annotation.SuppressLint
import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * CameraX implementation of the capture primitive (§4).
 *
 * One [Recorder] is bound for the whole session and each clip is a separate
 * recording against it — rebinding per clip would blink the preview and drop
 * frames at every boundary.
 *
 * Encoding stays on the device's hardware encoder; nothing here re-encodes,
 * which is the single biggest battery lever in §6.
 */
class CameraXSegmentRecorder(private val context: Context) : SegmentRecorder {

  private val mainExecutor: Executor = ContextCompat.getMainExecutor(context)

  /**
   * Whose lifecycle holds the camera open. This is [RecordingService], never the
   * Activity: an Activity-bound session unbinds the moment the app is
   * backgrounded, which is exactly the case the rolling buffer exists to
   * survive (§5.1). Set by the module before [prepare].
   */
  var lifecycleOwner: LifecycleOwner? = null

  private var cameraProvider: ProcessCameraProvider? = null
  private var videoCapture: VideoCapture<Recorder>? = null
  private var previewUseCase: Preview? = null
  private var activeRecording: Recording? = null
  private var stateSource: LiveData<CameraState>? = null
  private var stateObserver: Observer<CameraState>? = null
  private var clipStartedAtMs = 0L
  private var discardCurrent = false
  private var audioEnabled = true

  override fun prepare(config: RecorderConfig) {
    audioEnabled = config.audioEnabled
    val owner = lifecycleOwner
      ?: throw IllegalStateException("The recording service is not running; cannot bind the camera")

    val provider = ProcessCameraProvider.getInstance(context).get(BIND_TIMEOUT_SEC, TimeUnit.SECONDS)
    cameraProvider = provider

    val recorder = Recorder.Builder()
      .setQualitySelector(
        QualitySelector.from(
          when (config.videoQuality) {
            VideoQuality.HD_720 -> Quality.HD
            VideoQuality.HD_1080 -> Quality.FHD
            VideoQuality.UHD_4K -> Quality.UHD
          },
          // Never fail to record because a device lacks the requested tier.
          FallbackStrategy.lowerQualityOrHigherThan(Quality.HD),
        )
      )
      .build()
    val capture = VideoCapture.withOutput(recorder)
    val preview = Preview.Builder().build()

    // Bind on the main thread and *wait for the camera to actually open*.
    // Returning early is what makes the first clip finalize with
    // ERROR_SOURCE_INACTIVE (CameraX error 4): `bindToLifecycle` only attaches
    // the use cases, and the segment loop would otherwise start a recording
    // against a VideoCapture whose camera is still opening. Failures propagate
    // instead of being logged, so Play reports "camera unavailable" rather than
    // silently idling.
    val latch = CountDownLatch(1)
    var failure: Throwable? = null
    mainExecutor.execute {
      try {
        provider.unbindAll()
        val camera = provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
        // Whichever preview view is mounted now — or mounts later — gets this
        // session's frames, without either side owning the other (§3.1).
        CameraPreviewBus.subscribe { surfaceProvider -> preview.setSurfaceProvider(surfaceProvider) }

        val states = camera.cameraInfo.cameraState
        val observer = object : Observer<CameraState> {
          override fun onChanged(value: CameraState) {
            val error = value.error
            when {
              error != null -> {
                failure = IllegalStateException("Camera error ${error.code}", error.cause)
                stopWatchingState()
                latch.countDown()
              }
              value.type == CameraState.Type.OPEN -> {
                stopWatchingState()
                latch.countDown()
              }
            }
          }
        }
        stateSource = states
        stateObserver = observer
        // observeForever replays the current state, so a camera that is already
        // open releases the latch immediately.
        states.observeForever(observer)
      } catch (t: Throwable) {
        failure = t
        latch.countDown()
      }
    }

    if (!latch.await(BIND_TIMEOUT_SEC, TimeUnit.SECONDS)) {
      mainExecutor.execute { stopWatchingState() }
      throw IllegalStateException("Timed out waiting for the camera to open")
    }
    failure?.let { throw IllegalStateException(it.message ?: "Could not open the camera", it) }

    videoCapture = capture
    previewUseCase = preview
  }

  /** Main thread only — [LiveData.removeObserver] demands it. */
  private fun stopWatchingState() {
    stateObserver?.let { stateSource?.removeObserver(it) }
    stateObserver = null
    stateSource = null
  }

  @SuppressLint("MissingPermission") // Permissions are gated in LoopcamRecorderModule.requestPermissions.
  override fun startClip(output: File, onFinished: (Clip) -> Unit, onError: (Throwable) -> Unit) {
    val capture = videoCapture ?: run {
      onError(IllegalStateException("Camera not prepared"))
      return
    }
    output.parentFile?.mkdirs()
    discardCurrent = false
    clipStartedAtMs = System.currentTimeMillis()

    try {
      val pending = capture.output
        .prepareRecording(context, FileOutputOptions.Builder(output).build())
        .apply { if (audioEnabled) withAudioEnabled() }

      activeRecording = pending.start(mainExecutor) { event ->
        if (event !is VideoRecordEvent.Finalize) return@start
        activeRecording = null
        // Only a fully finalized file may enter the ring buffer (§10).
        if (discardCurrent || event.hasError()) {
          output.delete()
          if (event.hasError() && !discardCurrent) {
            onError(RuntimeException(describe(event.error), event.cause))
          }
          return@start
        }
        onFinished(
          Clip(
            file = output,
            durationSec = event.recordingStats.recordedDurationNanos / 1_000_000_000.0,
            sizeBytes = event.recordingStats.numBytesRecorded,
            startedAtMs = clipStartedAtMs,
          )
        )
      }
    } catch (t: Throwable) {
      output.delete()
      onError(t)
    }
  }

  override fun stopClip(discard: Boolean) {
    val recording = activeRecording ?: return
    discardCurrent = discard
    activeRecording = null
    recording.stop()
  }

  override fun release() {
    activeRecording?.let { recording ->
      discardCurrent = true
      recording.stop()
    }
    activeRecording = null
    videoCapture = null
    previewUseCase = null
    mainExecutor.execute {
      stopWatchingState()
      CameraPreviewBus.subscribe(null)
      cameraProvider?.unbindAll()
    }
  }

  /**
   * CameraX reports finalize failures as bare ints. Translating them here is the
   * difference between "error 4" and something the user can act on.
   */
  private fun describe(error: Int): String = when (error) {
    VideoRecordEvent.Finalize.ERROR_INSUFFICIENT_STORAGE ->
      "Not enough free storage to keep recording"
    VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE ->
      "The camera stopped feeding the recorder (source inactive)"
    VideoRecordEvent.Finalize.ERROR_INVALID_OUTPUT_OPTIONS ->
      "Invalid output options for the clip"
    VideoRecordEvent.Finalize.ERROR_ENCODING_FAILED ->
      "The hardware encoder failed"
    VideoRecordEvent.Finalize.ERROR_RECORDER_ERROR ->
      "The recorder hit an unrecoverable error"
    VideoRecordEvent.Finalize.ERROR_NO_VALID_DATA ->
      "The clip contained no valid frames"
    VideoRecordEvent.Finalize.ERROR_RECORDING_GARBAGE_COLLECTED ->
      "The recording was collected before it was stopped"
    else -> "Clip finalize error $error"
  }

  private companion object {
    const val BIND_TIMEOUT_SEC = 10L
  }
}
