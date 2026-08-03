package expo.modules.loopcamrecorder

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import androidx.camera.core.CameraSelector
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
import java.io.File
import java.util.concurrent.Executor

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
class CameraXSegmentRecorder(
  private val context: Context,
  private val lifecycleOwner: LifecycleOwner,
) : SegmentRecorder {

  private val mainExecutor: Executor = ContextCompat.getMainExecutor(context)

  private var cameraProvider: ProcessCameraProvider? = null
  private var videoCapture: VideoCapture<Recorder>? = null
  private var activeRecording: Recording? = null
  private var activeOutput: File? = null
  private var clipStartedAtMs = 0L
  private var discardCurrent = false
  private var audioEnabled = true

  /** Set by [LoopcamRecorderView] so the same session feeds the on-screen preview. */
  var preview: Preview? = null

  override fun prepare(config: RecorderConfig) {
    audioEnabled = config.audioEnabled
    val provider = ProcessCameraProvider.getInstance(context).get()
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
    videoCapture = capture

    // TODO(phase-1): bind on the main thread and surface CameraX bind failures
    // (device in use, no back camera) as RecorderErrorCode.CAMERA_UNAVAILABLE.
    mainExecutor.execute {
      runCatching {
        provider.unbindAll()
        val useCases = listOfNotNull(capture, preview).toTypedArray()
        provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, *useCases)
      }.onFailure { Log.e(TAG, "Failed to bind camera use cases", it) }
    }
  }

  @SuppressLint("MissingPermission") // Permissions are gated in LoopcamRecorderModule.requestPermissions.
  override fun startClip(output: File, onFinished: (Clip) -> Unit, onError: (Throwable) -> Unit) {
    val capture = videoCapture ?: run {
      onError(IllegalStateException("Camera not prepared"))
      return
    }
    output.parentFile?.mkdirs()
    activeOutput = output
    discardCurrent = false
    clipStartedAtMs = System.currentTimeMillis()

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
          onError(RuntimeException("Clip finalize error ${event.error}"))
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
  }

  override fun stopClip(discard: Boolean) {
    discardCurrent = discard
    activeRecording?.stop()
    activeRecording = null
  }

  override fun release() {
    activeRecording?.stop()
    activeRecording = null
    mainExecutor.execute { cameraProvider?.unbindAll() }
    videoCapture = null
  }

  private companion object {
    const val TAG = "LoopCam/CameraX"
  }
}
