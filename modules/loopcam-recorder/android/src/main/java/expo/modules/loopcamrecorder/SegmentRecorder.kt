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
