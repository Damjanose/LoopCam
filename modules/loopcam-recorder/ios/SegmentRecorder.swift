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
///
/// Everything past the public methods runs on `sessionQueue` — the sample
/// callbacks included — so the writer is never touched from two threads.
final class AVSegmentRecorder: NSObject, SegmentRecorder {
  private let session = AVCaptureSession()
  private let sessionQueue = DispatchQueue(label: "loopcam.capture")

  private let videoOutput = AVCaptureVideoDataOutput()
  private let audioOutput = AVCaptureAudioDataOutput()

  private var quality: VideoQuality = .hd1080
  private var audioEnabled = true

  /// The clip being written right now. `sessionQueue` only.
  private var active: ClipWriter?

  /// Handed to `LoopcamRecorderView` so preview and buffer share one session.
  var captureSession: AVCaptureSession { session }

  func prepare(config: RecorderConfig) throws {
    try sessionQueue.sync { try configureSession(config) }

    // `startRunning` blocks for the best part of a second. Kicking it off
    // asynchronously keeps Play responsive; the writer opened right afterwards
    // simply waits for its first sample, which is what it does at every clip
    // boundary anyway.
    sessionQueue.async { [weak self] in
      guard let self, !self.session.isRunning else { return }
      self.session.startRunning()
    }

    CameraPreviewBus.shared.publish(session)
  }

  func startClip(
    output: URL,
    onFinished: @escaping (Clip) -> Void,
    onError: @escaping (Error) -> Void
  ) {
    sessionQueue.async { [weak self] in
      guard let self else { return }
      do {
        self.active = try ClipWriter(
          output: output,
          videoSettings: self.videoSettings(),
          audioSettings: self.audioEnabled ? self.audioSettings() : nil,
          onFinished: onFinished,
          onError: onError
        )
      } catch {
        self.active = nil
        onError(error)
      }
    }
  }

  func stopClip(discard: Bool) {
    sessionQueue.async { [weak self] in
      guard let self, let clip = self.active else { return }
      // Detached before it closes, so late samples land nowhere rather than in a
      // file that is already being flushed.
      self.active = nil
      clip.finish(discard: discard)
    }
  }

  func teardown() {
    CameraPreviewBus.shared.publish(nil)
    sessionQueue.async { [weak self] in
      guard let self else { return }
      self.active?.finish(discard: true)
      self.active = nil
      if self.session.isRunning {
        self.session.stopRunning()
      }
      try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }
  }

  // MARK: - session configuration

  private func configureSession(_ config: RecorderConfig) throws {
    quality = config.videoQuality
    audioEnabled = config.audioEnabled

    guard AVCaptureDevice.authorizationStatus(for: .video) == .authorized else {
      throw RecorderError.cameraUnavailable("camera access has not been granted")
    }

    // We own the audio session (§5.2), so AVFoundation must not reset it.
    session.automaticallyConfiguresApplicationAudioSession = false

    session.beginConfiguration()
    defer { session.commitConfiguration() }

    // Play can be pressed again after Stop; rebuilding from empty is what keeps
    // a second run from stacking a duplicate camera input onto the session.
    for input in session.inputs { session.removeInput(input) }
    for output in session.outputs { session.removeOutput(output) }

    session.sessionPreset = preset(for: quality)

    guard
      let camera = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back),
      let cameraInput = try? AVCaptureDeviceInput(device: camera),
      session.canAddInput(cameraInput)
    else {
      throw RecorderError.cameraUnavailable("no usable back camera on this device")
    }
    session.addInput(cameraInput)

    videoOutput.alwaysDiscardsLateVideoFrames = true
    videoOutput.setSampleBufferDelegate(self, queue: sessionQueue)
    guard session.canAddOutput(videoOutput) else {
      throw RecorderError.cameraUnavailable("the capture session rejected the video output")
    }
    session.addOutput(videoOutput)

    if config.audioEnabled {
      // An active .playAndRecord session is what buys background time (§5.2), so
      // this is load-bearing rather than incidental.
      try AVAudioSession.sharedInstance().setCategory(
        .playAndRecord, mode: .videoRecording, options: [.mixWithOthers, .defaultToSpeaker]
      )
      try AVAudioSession.sharedInstance().setActive(true)

      if
        let mic = AVCaptureDevice.default(for: .audio),
        let micInput = try? AVCaptureDeviceInput(device: mic),
        session.canAddInput(micInput),
        session.canAddOutput(audioOutput)
      {
        session.addInput(micInput)
        audioOutput.setSampleBufferDelegate(self, queue: sessionQueue)
        session.addOutput(audioOutput)
      } else {
        // A missing mic is no reason to refuse to record the road. Clips go out
        // video-only and *every* segment agrees on that, which is the part the
        // passthrough merge actually cares about.
        audioEnabled = false
      }
    }
  }

  private func preset(for quality: VideoQuality) -> AVCaptureSession.Preset {
    switch quality {
    case .hd720: return .hd1280x720
    case .hd1080: return .hd1920x1080
    case .uhd4k: return session.canSetSessionPreset(.hd4K3840x2160) ? .hd4K3840x2160 : .hd1920x1080
    }
  }

  /// The output's own recommendation with our bitrate laid on top: it already
  /// knows the active format's dimensions, so nothing here has to guess them.
  private func videoSettings() -> [String: Any] {
    var settings = videoOutput.recommendedVideoSettingsForAssetWriter(writingTo: .mp4)
      ?? fallbackVideoSettings()
    var compression = settings[AVVideoCompressionPropertiesKey] as? [String: Any] ?? [:]
    compression[AVVideoAverageBitRateKey] = quality.bitrate
    settings[AVVideoCompressionPropertiesKey] = compression
    return settings
  }

  private func fallbackVideoSettings() -> [String: Any] {
    let size: (width: Int, height: Int)
    switch quality {
    case .hd720: size = (1280, 720)
    case .hd1080: size = (1920, 1080)
    case .uhd4k: size = (3840, 2160)
    }
    return [
      AVVideoCodecKey: AVVideoCodecType.h264,
      AVVideoWidthKey: size.width,
      AVVideoHeightKey: size.height,
    ]
  }

  private func audioSettings() -> [String: Any] {
    audioOutput.recommendedAudioSettingsForAssetWriter(writingTo: .mp4) ?? [
      AVFormatIDKey: kAudioFormatMPEG4AAC,
      AVNumberOfChannelsKey: 1,
      AVSampleRateKey: 44_100,
      AVEncoderBitRateKey: 64_000,
    ]
  }
}

// MARK: - sample delivery

extension AVSegmentRecorder: AVCaptureVideoDataOutputSampleBufferDelegate,
  AVCaptureAudioDataOutputSampleBufferDelegate
{
  func captureOutput(
    _ output: AVCaptureOutput,
    didOutput sampleBuffer: CMSampleBuffer,
    from connection: AVCaptureConnection
  ) {
    // Already on `sessionQueue` — both outputs were handed that queue.
    active?.append(sampleBuffer, isVideo: output === videoOutput)
  }
}

// MARK: - one clip

/// One segment's writer: created when a clip starts, thrown away when it closes.
/// There is no reset path, so a half-written clip can never be mistaken for a
/// fresh one.
private final class ClipWriter {
  private let output: URL
  private let startedAt = Date()
  private let writer: AVAssetWriter
  private let videoInput: AVAssetWriterInput
  private let audioInput: AVAssetWriterInput?
  private let onFinished: (Clip) -> Void
  private let onError: (Error) -> Void

  private var sessionStartPTS: CMTime?
  private var lastPTS: CMTime?
  private var isFinishing = false

  init(
    output: URL,
    videoSettings: [String: Any],
    audioSettings: [String: Any]?,
    onFinished: @escaping (Clip) -> Void,
    onError: @escaping (Error) -> Void
  ) throws {
    self.output = output
    self.onFinished = onFinished
    self.onError = onError

    try? FileManager.default.removeItem(at: output)
    writer = try AVAssetWriter(outputURL: output, fileType: .mp4)

    videoInput = AVAssetWriterInput(mediaType: .video, outputSettings: videoSettings)
    videoInput.expectsMediaDataInRealTime = true
    // Portrait as metadata rather than rotated pixels: the app is portrait
    // locked, and a transform is free where rotating every frame is not (§6).
    videoInput.transform = CGAffineTransform(rotationAngle: .pi / 2)
    guard writer.canAdd(videoInput) else {
      throw RecorderError.cameraUnavailable("the writer rejected the video input")
    }
    writer.add(videoInput)

    if let audioSettings {
      let input = AVAssetWriterInput(mediaType: .audio, outputSettings: audioSettings)
      input.expectsMediaDataInRealTime = true
      if writer.canAdd(input) {
        writer.add(input)
        audioInput = input
      } else {
        audioInput = nil
      }
    } else {
      audioInput = nil
    }

    guard writer.startWriting() else {
      throw writer.error ?? RecorderError.cameraUnavailable("the writer refused to start")
    }
  }

  func append(_ sampleBuffer: CMSampleBuffer, isVideo: Bool) {
    guard !isFinishing, writer.status == .writing else { return }

    let pts = CMSampleBufferGetPresentationTimeStamp(sampleBuffer)
    if sessionStartPTS == nil {
      // Always open on a video sample: a clip that starts on audio begins with a
      // stretch of no picture, which reads as a black gap once merged.
      guard isVideo else { return }
      writer.startSession(atSourceTime: pts)
      sessionStartPTS = pts
    }

    guard let input = isVideo ? videoInput : audioInput, input.isReadyForMoreMediaData else {
      return
    }
    input.append(sampleBuffer)

    if isVideo {
      let duration = CMSampleBufferGetDuration(sampleBuffer)
      lastPTS = duration.isNumeric ? pts + duration : pts
    }
  }

  /// Closes the file. `onFinished` fires from the writer's completion handler and
  /// never before — a Save that merged a file still being flushed would hand the
  /// user a truncated clip (§10).
  func finish(discard: Bool) {
    guard !isFinishing else { return }
    isFinishing = true

    guard writer.status == .writing, let start = sessionStartPTS else {
      writer.cancelWriting()
      try? FileManager.default.removeItem(at: output)
      if !discard {
        onError(RecorderError.cameraUnavailable("the camera delivered no frames for this clip"))
      }
      return
    }

    let end = lastPTS ?? start
    let durationSec = max(0, (end - start).seconds)
    videoInput.markAsFinished()
    audioInput?.markAsFinished()
    writer.endSession(atSourceTime: end)

    writer.finishWriting { [self] in
      if discard {
        try? FileManager.default.removeItem(at: output)
        return
      }
      guard writer.status == .completed else {
        try? FileManager.default.removeItem(at: output)
        onError(writer.error ?? RecorderError.cameraUnavailable("the clip failed to finish writing"))
        return
      }
      let size = (try? FileManager.default.attributesOfItem(atPath: output.path)[.size])
        .flatMap { ($0 as? NSNumber)?.int64Value } ?? 0
      onFinished(Clip(url: output, durationSec: durationSec, sizeBytes: size, startedAt: startedAt))
    }
  }
}
