import AVFoundation
import Foundation

/// The capture primitive the segment loop drives: "record exactly one clip to
/// this URL, tell me when it is closed and flushed."
protocol SegmentRecorder: AnyObject {
  /// Configure and start the capture session. Called once when Play is pressed.
  func prepare(config: RecorderConfig) throws

  /// Start writing a new clip. `onFinished` fires only once the file is fully
  /// flushed — only then may the clip enter the ring buffer (§10).
  func startClip(
    output: URL,
    onFinished: @escaping (Clip) -> Void,
    onError: @escaping (Error) -> Void
  )

  /// Stop the in-flight clip. When `discard` is true (STOP) the partial file is
  /// deleted and `onFinished` never fires.
  func stopClip(discard: Bool)

  /// Tear down the capture session.
  func teardown()
}

/// §4 — AVFoundation implementation.
///
/// `AVAssetWriter` rather than `AVCaptureMovieFileOutput`: the writer lets us
/// close one segment and open the next between frames, so clip boundaries cost
/// nothing and the preview never blinks. Encoding stays on the hardware
/// encoder, which is the biggest battery lever in §6.
final class AVSegmentRecorder: NSObject, SegmentRecorder {
  private let session = AVCaptureSession()
  private let sessionQueue = DispatchQueue(label: "loopcam.capture")

  private var writer: AVAssetWriter?
  private var videoInput: AVAssetWriterInput?
  private var audioInput: AVAssetWriterInput?
  private var currentOutput: URL?
  private var clipStartedAt = Date()
  private var discardCurrent = false

  private var onFinished: ((Clip) -> Void)?
  private var onError: ((Error) -> Void)?

  /// Handed to `LoopcamRecorderView` so preview and buffer share one session.
  var captureSession: AVCaptureSession { session }

  func prepare(config: RecorderConfig) throws {
    // TODO(phase-1): configure inputs/outputs —
    //   1. AVCaptureDeviceInput for the back camera (+ mic when audioEnabled);
    //   2. AVCaptureVideoDataOutput + AVCaptureAudioDataOutput onto sessionQueue;
    //   3. sessionPreset from config.videoQuality;
    //   4. an AVAudioSession category of .playAndRecord with .mixWithOthers —
    //      the active audio session is what keeps the app resident in the
    //      background (§5.2), so it is load-bearing, not incidental.
    throw RecorderError.cameraUnavailable("AVSegmentRecorder.prepare not implemented yet (Phase 1)")
  }

  func startClip(
    output: URL,
    onFinished: @escaping (Clip) -> Void,
    onError: @escaping (Error) -> Void
  ) {
    self.onFinished = onFinished
    self.onError = onError
    currentOutput = output
    discardCurrent = false
    clipStartedAt = Date()

    // TODO(phase-1): open a fresh AVAssetWriter at `output`, add video/audio
    // inputs with expectsMediaDataInRealTime = true, and start a session at the
    // next sample's presentation timestamp.
  }

  func stopClip(discard: Bool) {
    discardCurrent = discard
    // TODO(phase-1): markAsFinished() both inputs, then finishWriting. In the
    // completion handler: if `discard`, delete the partial file and stay quiet;
    // otherwise build a Clip and call onFinished — never before, or a Save
    // could merge a file that is still being written (§10).
  }

  func teardown() {
    sessionQueue.async { [weak self] in
      self?.session.stopRunning()
    }
    writer = nil
    videoInput = nil
    audioInput = nil
  }
}
