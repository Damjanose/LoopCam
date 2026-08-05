package expo.modules.loopcamrecorder

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.util.Rational
import android.util.Size
import android.view.Surface
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ConcurrentCamera
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
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
import java.util.concurrent.Executors
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

  /**
   * The standby preview: the camera bound to the screen and to nothing else.
   *
   * Held separately from [previewUseCase] because the two answer to different
   * owners. This one belongs to the Activity, so it dies when the app is
   * backgrounded — a picture with no recording behind it has no business
   * holding the camera open off-screen, and doing so would burn battery and
   * trip Android's foreground-camera rules for no gain. [previewUseCase] is the
   * session's own, owned by the service, and must survive exactly that.
   */
  private var standbyPreview: Preview? = null

  /**
   * The burned-in clock. The stamp is not shown on any live viewfinder — only
   * the encoder's copy carries it — so a live session runs two overlays: one on
   * the encoder ([sessionVideoWatermark], stamped) and one on the screen
   * ([sessionPreviewWatermark], not). Standby has only the screen. Closed with
   * the binding it belongs to, because an effect outliving its use cases holds
   * a GPU surface and a thread for a picture nobody is watching.
   */
  private var sessionVideoWatermark: WatermarkOverlay? = null
  private var sessionPreviewWatermark: WatermarkOverlay? = null
  private var standbyWatermark: WatermarkOverlay? = null

  /**
   * The front camera's use case in `both` mode, one per binding for the same
   * reason the watermarks are. Null in the single-camera modes, and null on a
   * device where the concurrent bind was refused — in which case the overlay
   * simply finds no frame to draw and the corner stays empty.
   */
  private var sessionFrontAnalysis: ImageAnalysis? = null
  private var standbyFrontAnalysis: ImageAnalysis? = null

  /**
   * One thread for the YUV→bitmap conversion, kept for the process's life
   * rather than created per session: it is idle between bindings, and churning
   * an executor at every Play would be a thread created on the critical path of
   * starting to record.
   */
  private val analysisExecutor = Executors.newSingleThreadExecutor { runnable ->
    Thread(runnable, "loopcam-front").apply { isDaemon = true }
  }
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
      .setQualitySelector(QualitySelector.fromOrderedList(qualityLadder(config.videoQuality)))
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
    val mode = config.camera
    // The front camera exists only to be drawn into the corner, so it is an
    // analysis stream feeding the overlay rather than a second recording.
    val frontFeed = if (mode == CameraMode.BOTH) FrontCameraFeed() else null
    val frontAnalysis = frontFeed?.let { newFrontAnalysis(it) }
    // Two overlays, not one: the stamp belongs on the file, not on the screen a
    // driver is glancing at while moving. Both draw the same inset off the same
    // [frontFeed], so the corner still matches between the two.
    val videoWatermark = WatermarkOverlay(WatermarkOverlay.VIDEO_TARGETS, frontFeed, showStamp = true)
    val previewWatermark =
      WatermarkOverlay(WatermarkOverlay.PREVIEW_TARGETS, frontFeed, showStamp = false)

    val latch = CountDownLatch(1)
    var failure: Throwable? = null
    mainExecutor.execute {
      try {
        provider.unbindAll()
        // The standby bind went with it. Forgetting that here would leave a
        // stale use case that `stopPreview` later unbinds — taking the live
        // session's picture down with it.
        standbyPreview = null
        standbyFrontAnalysis = null
        standbyWatermark?.close()
        standbyWatermark = null
        // One group rather than loose use cases, because an effect can only be
        // attached to a group. The inset reaches the encoder and the screen from
        // the same composite, so the corner is WYSIWYG; the stamp only reaches
        // the encoder, via its own effect on the same group.
        val group = UseCaseGroup.Builder()
          .setViewPort(recordingViewPort())
          .addUseCase(preview)
          .addUseCase(capture)
          .addEffect(videoWatermark.effect)
          .addEffect(previewWatermark.effect)
          .build()
        val camera = bind(provider, owner, mode, group, frontAnalysis)
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
      videoWatermark.close()
      previewWatermark.close()
      throw IllegalStateException("Timed out waiting for the camera to open")
    }
    failure?.let {
      videoWatermark.close()
      previewWatermark.close()
      throw IllegalStateException(it.message ?: "Could not open the camera", it)
    }

    videoCapture = capture
    previewUseCase = preview
    sessionVideoWatermark = videoWatermark
    sessionPreviewWatermark = previewWatermark
    sessionFrontAnalysis = frontAnalysis
  }

  /**
   * Which camera(s) the group binds to.
   *
   * `both` is CameraX's *concurrent camera*: two independent bindings, one per
   * physical camera, submitted together. It is not universally supported, which
   * is why [CameraProbe] gates the mode before the user can ever select it — but
   * a bind can still be refused at runtime by a device that reported support,
   * and a dashcam that will not record the road because the selfie camera was
   * unavailable would be a far worse failure than a missing inset. So a refusal
   * falls back to the back camera alone: the overlay finds no front frame and
   * leaves the corner empty.
   */
  private fun bind(
    provider: ProcessCameraProvider,
    owner: LifecycleOwner,
    mode: CameraMode,
    group: UseCaseGroup,
    frontAnalysis: ImageAnalysis?,
  ): Camera {
    if (mode == CameraMode.BOTH && frontAnalysis != null) {
      val front = UseCaseGroup.Builder().addUseCase(frontAnalysis).build()
      runCatching {
        provider.bindToLifecycle(
          listOf(
            ConcurrentCamera.SingleCameraConfig(CameraSelector.DEFAULT_BACK_CAMERA, group, owner),
            ConcurrentCamera.SingleCameraConfig(CameraSelector.DEFAULT_FRONT_CAMERA, front, owner),
          )
        )
      }
        .onSuccess { return it.cameras.first() }
        .onFailure { Log.w(TAG, "Concurrent camera bind refused; recording back only", it) }
      // The refused bind may have left the group half-attached.
      provider.unbindAll()
    }
    return provider.bindToLifecycle(owner, selectorFor(mode), group)
  }

  /**
   * The one crop rect every use case in the group shares.
   *
   * Load-bearing, not a nicety. Without a view port each use case crops the
   * sensor buffer independently: the overlay effect is handed the *whole*
   * buffer and told via `Frame.cropRect` that all of it is visible, while
   * VideoCapture quietly crops it to the recording aspect afterwards. On a
   * camera delivering 4:3 (1280x960 on the emulator, and plenty of real
   * hardware) that is 12.5% shaved off each side *after* the timestamp has been
   * positioned against the full width — so the plate was laid out to end 2.5%
   * from the right edge of a frame 25% wider than the one that reached the
   * file, and four characters of the clock were cut off the side of every
   * recording.
   *
   * With a view port, `Frame.cropRect` describes what will actually be encoded,
   * and the preview is cropped to match — which is what makes the viewfinder
   * show the framing the file will have, rather than a wider one.
   *
   * The ratio is the app's own: portrait-locked 16:9. FILL_CENTER because the
   * alternative letterboxes the road to preserve sensor area nobody asked for.
   */
  private fun recordingViewPort(): ViewPort =
    ViewPort.Builder(Rational(9, 16), Surface.ROTATION_0)
      .setScaleType(ViewPort.FILL_CENTER)
      .build()

  private fun selectorFor(mode: CameraMode): CameraSelector = when (mode) {
    // `both` records *into* the back camera's frame, so the back camera is the
    // one that carries the video capture; the front is the second binding.
    CameraMode.BACK, CameraMode.BOTH -> CameraSelector.DEFAULT_BACK_CAMERA
    CameraMode.FRONT -> CameraSelector.DEFAULT_FRONT_CAMERA
  }

  /**
   * The front camera's stream: small, latest-only, and analysed rather than
   * previewed.
   *
   * The requested size is roughly what the inset occupies on a 1080p frame.
   * Asking for the front camera's full resolution would mean converting several
   * megapixels per frame on the CPU to draw a picture a third of an inch wide.
   */
  private fun newFrontAnalysis(feed: FrontCameraFeed): ImageAnalysis =
    ImageAnalysis.Builder()
      // Latest-only: a queued front frame is a stale one by the time it is
      // drawn, and the overlay only ever wants the newest.
      .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
      .setResolutionSelector(
        ResolutionSelector.Builder()
          .setResolutionStrategy(
            ResolutionStrategy(PIP_ANALYSIS_SIZE, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER)
          )
          .build()
      )
      .build()
      .also { it.setAnalyzer(analysisExecutor, feed) }

  /**
   * Every quality the session will accept, best first.
   *
   * A [FallbackStrategy] cannot do this job: its rules search *strictly* below
   * and then strictly above their anchor, so a camera whose only supported tier
   * is the anchor itself resolves to nothing and `bindToLifecycle` dies with
   * "Unable to find supported quality by QualitySelector". That is not a corner
   * case — every emulator and every 720p-only device lands there, and the whole
   * app is unusable because Play can never bind.
   *
   * An exhaustive ordered list cannot miss: whatever the camera supports is
   * somewhere in it. The order steps *down* from the requested tier before
   * stepping up, so a device that cannot honour the request records a cheaper
   * buffer rather than silently filling the disk with a larger one.
   */
  private fun qualityLadder(requested: VideoQuality): List<Quality> {
    val preferred = when (requested) {
      // Both land on SD: CameraX has no 360p tier, and `Quality.LOWEST` means
      // 176x144 on some hardware — useless as evidence. See [VideoQuality].
      VideoQuality.SD_360, VideoQuality.SD_480 -> Quality.SD
      VideoQuality.HD_720 -> Quality.HD
      VideoQuality.HD_1080 -> Quality.FHD
      VideoQuality.UHD_4K -> Quality.UHD
    }
    val index = DESCENDING_QUALITIES.indexOf(preferred)
    return DESCENDING_QUALITIES.drop(index) + DESCENDING_QUALITIES.take(index).reversed()
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

  /**
   * Light the preview without recording anything, so the viewfinder is a
   * viewfinder from the moment the screen opens rather than a black rectangle
   * that only becomes a camera once Play is pressed.
   *
   * Deliberately not blocking, unlike [prepare]: nothing downstream depends on
   * the camera being open by the time this returns — if it never opens, the
   * screen simply stays dark, which is what it would have been anyway. A
   * failure here is therefore swallowed rather than surfaced; Play is where a
   * broken camera has to be reported, because that is where it costs footage.
   */
  fun startPreview(owner: LifecycleOwner, mode: CameraMode) {
    val future = ProcessCameraProvider.getInstance(context)
    future.addListener({
      val provider = runCatching { future.get() }.getOrNull() ?: return@addListener
      cameraProvider = provider
      // A live session already owns the camera and is already feeding the bus.
      // Binding a second preview over it would fight for the same surface.
      if (videoCapture != null) return@addListener
      standbyPreview?.let { provider.unbind(it) }
      standbyFrontAnalysis?.let { provider.unbind(it) }
      standbyFrontAnalysis = null
      standbyWatermark?.close()
      standbyWatermark = null
      val preview = Preview.Builder().build()
      // Standby shows the inset, for the same reason the live session's screen
      // does: the viewfinder's job is to show the framing a recording would
      // have, and discovering the layout only after pressing Play defeats it.
      // The stamp itself is never shown on a viewfinder — see [showStamp] — so
      // standby does not draw it either.
      val frontFeed = if (mode == CameraMode.BOTH) FrontCameraFeed() else null
      val frontAnalysis = frontFeed?.let { newFrontAnalysis(it) }
      val watermark =
        WatermarkOverlay(WatermarkOverlay.PREVIEW_TARGETS, frontFeed, showStamp = false)
      val bound = runCatching {
        val group = UseCaseGroup.Builder()
          .setViewPort(recordingViewPort())
          .addUseCase(preview)
          .addEffect(watermark.effect)
          .build()
        bind(provider, owner, mode, group, frontAnalysis)
        CameraPreviewBus.subscribe { surfaceProvider -> preview.setSurfaceProvider(surfaceProvider) }
        standbyPreview = preview
        standbyFrontAnalysis = frontAnalysis
        standbyWatermark = watermark
      }
      if (bound.isFailure) {
        frontAnalysis?.clearAnalyzer()
        watermark.close()
      }
    }, mainExecutor)
  }

  /** Drop the standby picture. Never touches a live session's own preview. */
  fun stopPreview() {
    mainExecutor.execute {
      val preview = standbyPreview ?: return@execute
      standbyPreview = null
      if (videoCapture == null) CameraPreviewBus.subscribe(null)
      cameraProvider?.unbind(preview)
      standbyFrontAnalysis?.let {
        // Detached before the unbind: an analyzer left attached keeps
        // converting frames onto a feed nothing will read.
        it.clearAnalyzer()
        cameraProvider?.unbind(it)
      }
      standbyFrontAnalysis = null
      standbyWatermark?.close()
      standbyWatermark = null
    }
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
      standbyPreview = null
      // Analyzers off before the unbind, so no conversion is in flight while
      // the pipeline is being pulled apart.
      sessionFrontAnalysis?.clearAnalyzer()
      standbyFrontAnalysis?.clearAnalyzer()
      sessionFrontAnalysis = null
      standbyFrontAnalysis = null
      cameraProvider?.unbindAll()
      // After the unbind, never before: an effect closed while its use cases
      // are still bound pulls the surface out from under the pipeline.
      sessionVideoWatermark?.close()
      sessionVideoWatermark = null
      sessionPreviewWatermark?.close()
      sessionPreviewWatermark = null
      standbyWatermark?.close()
      standbyWatermark = null
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
    private const val TAG = "LoopCam/CameraX"

    const val BIND_TIMEOUT_SEC = 10L

    /** Roughly what the inset occupies on a 1080p frame. See [newFrontAnalysis]. */
    val PIP_ANALYSIS_SIZE = Size(640, 360)

    /** Largest first — [qualityLadder] slices this both ways. */
    val DESCENDING_QUALITIES = listOf(Quality.UHD, Quality.FHD, Quality.HD, Quality.SD)
  }
}
