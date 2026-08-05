package expo.modules.loopcamrecorder

import java.io.File

/**
 * The capture primitive the segment loop drives: "record exactly one clip to
 * this file, tell me when it is closed and flushed."
 *
 * Keeping this an interface lets [SegmentController]'s ring-buffer logic be
 * exercised on the JVM against a fake recorder, without a camera or a device.
 */
interface SegmentRecorder {
  /** Bind the capture session. Called once when Play is pressed. */
  fun prepare(config: RecorderConfig)

  /**
   * Apply the part of [config] that changes only what the next frame draws.
   *
   * Most settings need a session rebuild — `cameraMode` rebinds the cameras,
   * `quality` re-creates the encoder — which is why they are documented as
   * taking effect at the next Play. The speed unit is not one of those: it
   * changes a string, so a mid-drive switch between km/h and mph applies to the
   * next frame rather than waiting for the drive to end.
   *
   * Called on the controller's executor, so implementations must hand the value
   * to their compositor rather than touching it directly.
   */
  fun applyLiveConfig(config: RecorderConfig)

  /**
   * Start writing a new clip into [output]. [onFinished] fires once the file is
   * fully flushed — only then may the clip enter the ring buffer (§10).
   */
  fun startClip(output: File, onFinished: (Clip) -> Unit, onError: (Throwable) -> Unit)

  /**
   * Stop the in-flight clip. When [discard] is true (STOP) the partial file is
   * deleted and `onFinished` never fires; otherwise it is finalized normally.
   */
  fun stopClip(discard: Boolean)

  /** Tear down the capture session. */
  fun release()
}
