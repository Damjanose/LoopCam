import AVFoundation
import CoreImage
import Foundation

/// The capture primitive the segment loop drives: "record exactly one clip to
/// this URL, tell me when it is closed and flushed."
protocol SegmentRecorder: AnyObject {
  /// Configure and start the capture session. Called once when Play is pressed.
  func prepare(config: RecorderConfig) throws

  /// Apply the part of `config` that changes only what the next frame draws.
  ///
  /// Most settings need a session rebuild — `cameraMode` reconfigures the
  /// inputs, `quality` re-picks the format — which is why they are documented
  /// as taking effect at the next Play. The speed unit is not one of those: it
  /// changes a string, so a mid-drive switch between km/h and mph applies to
  /// the next frame rather than waiting for the drive to end.
  ///
  /// Called from the controller's queue, so implementations must hop onto their
  /// own queue rather than touching compositor state directly.
  func applyLiveConfig(_ config: RecorderConfig)

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
  /// Swapped for an `AVCaptureMultiCamSession` in `both` mode and back again
  /// afterwards, rather than always using the multi-cam class: multi-cam
  /// forbids session presets, costs more power even with one camera running,
  /// and is unsupported outright before the A12.
  private var session = AVCaptureSession()
  private let sessionQueue = DispatchQueue(label: "loopcam.capture")

  private let videoOutput = AVCaptureVideoDataOutput()
  /// The front camera in `both` mode. Never written to a file of its own — it
  /// exists to be composited into the back camera's frame.
  private let frontOutput = AVCaptureVideoDataOutput()
  private let audioOutput = AVCaptureAudioDataOutput()

  private var quality: VideoQuality = .hd1080
  private var audioEnabled = true
  private var cameraMode: CameraMode = .back

  /// The stamp's speed settings. Both are read on `sessionQueue` at composite
  /// time and written there by `applyLiveConfig`, so they need no atomics.
  private var showSpeed = true
  private var speedUnit: SpeedUnit = .kmh

  /// Pushes formats taller than requested to the back of the ranking without
  /// excluding them: overshooting is a last resort, not a disqualification.
  private let OVERSHOOT_PENALTY = 10_000

  /// How many times the front camera steps down before the pair is declared
  /// unfittable. Bounded so a device that never converges fails to back-only
  /// rather than looping.
  private let MAX_COST_RETRIES = 3

  /// The clip being written right now. `sessionQueue` only.
  private var active: ClipWriter?

  /// The newest front-camera frame, with the moment it arrived. `sessionQueue`
  /// only — both outputs deliver on that queue, which is also where the writer
  /// reads it, so no lock is involved.
  private var latestFront: (image: CIImage, at: Date)?

  /// Handed to `LoopcamRecorderView` so preview and buffer share one session.
  var captureSession: AVCaptureSession { session }

  func prepare(config: RecorderConfig) throws {
    try sessionQueue.sync {
      do {
        try configureSession(config)
      } catch {
        // A dual-camera setup that will not come up — a format the pair cannot
        // hold, a hardware budget it cannot fit — must not cost the drive. The
        // road is recorded alone and the inset is simply absent.
        guard config.camera == .both else { throw error }
        var fallback = config
        fallback.cameraMode = CameraMode.back.rawValue
        try configureSession(fallback)
      }
    }

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

  /// The speed unit, onto the live composite. Assigning two fields on the
  /// capture queue is the whole operation — no reconfiguration, no dropped
  /// clip, and the next frame drawn already says mph.
  func applyLiveConfig(_ config: RecorderConfig) {
    sessionQueue.async { [weak self] in
      self?.showSpeed = config.locationTaggingEnabled
      self?.speedUnit = config.speed
    }
  }

  /// The formatted speed field for the frame being composited, or "" when the
  /// stamp carries no speed.
  ///
  /// Empty rather than a blank slot when location tagging is off: a reserved
  /// but empty field reads as a receiver that never got a fix, which is a
  /// different claim from "the driver did not ask for this".
  ///
  /// Runs on `sessionQueue`, inside the per-frame composite. `currentSpeed` is
  /// a lock and a struct copy, which is the only reason that is acceptable
  /// here.
  private func currentSpeedField() -> String {
    guard showSpeed else { return "" }
    let sample = LocationTracker.shared.currentSpeed()
    return Self.speedGap
      + SpeedStyle.format(mps: sample?.speedMps, unit: speedUnit, derived: sample?.derived ?? false)
  }

  /// Separates the clock from the speed on the same plate. Wide enough that the
  /// two read as two facts rather than one long number. Mirrors
  /// `WatermarkOverlay.SPEED_GAP`.
  private static let speedGap = "   "

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
          // A closure rather than a stored frame: the writer outlives many
          // front frames, and reading through to the recorder at composite time
          // is what keeps it looking at the newest one.
          frontFrame: { [weak self] in self?.currentFrontFrame() },
          // Likewise a closure: the unit can change mid-clip and the reading
          // changes every second, so the field is built at composite time.
          speedField: { [weak self] in self?.currentSpeedField() ?? "" },
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
      // Or the next session's first frames would composite a picture from the
      // last one, timestamped as if it were live.
      self.latestFront = nil
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
    cameraMode = config.camera
    showSpeed = config.locationTaggingEnabled
    speedUnit = config.speed
    latestFront = nil

    guard AVCaptureDevice.authorizationStatus(for: .video) == .authorized else {
      throw RecorderError.cameraUnavailable("camera access has not been granted")
    }

    // `both` needs a different session class, and it is not a class we want in
    // the single-camera modes: it forbids presets and costs power even running
    // one camera. A device that cannot do multi-cam records the road alone,
    // which is what `getCapabilities` should have stopped anyone asking for.
    let dual = cameraMode == .both && AVCaptureMultiCamSession.isMultiCamSupported
    if dual, !(session is AVCaptureMultiCamSession) {
      session = AVCaptureMultiCamSession()
    } else if !dual, session is AVCaptureMultiCamSession {
      session = AVCaptureSession()
    }

    // We own the audio session (§5.2), so AVFoundation must not reset it.
    session.automaticallyConfiguresApplicationAudioSession = false

    session.beginConfiguration()
    defer { session.commitConfiguration() }

    // Play can be pressed again after Stop; rebuilding from empty is what keeps
    // a second run from stacking a duplicate camera input onto the session.
    for input in session.inputs { session.removeInput(input) }
    for output in session.outputs { session.removeOutput(output) }
    for connection in session.connections { session.removeConnection(connection) }

    if dual {
      try configureDualCameras()
    } else {
      try configureSingleCamera(position: cameraMode == .front ? .front : .back)
    }

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

  /// One camera, wired the ordinary way: a preset, an implicit connection, and
  /// nothing else to go wrong.
  private func configureSingleCamera(position: AVCaptureDevice.Position) throws {
    session.sessionPreset = preset(for: quality)

    guard
      let camera = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: position),
      let cameraInput = try? AVCaptureDeviceInput(device: camera),
      session.canAddInput(cameraInput)
    else {
      throw RecorderError.cameraUnavailable("no usable camera on this device")
    }
    session.addInput(cameraInput)

    videoOutput.alwaysDiscardsLateVideoFrames = true
    videoOutput.setSampleBufferDelegate(self, queue: sessionQueue)
    guard session.canAddOutput(videoOutput) else {
      throw RecorderError.cameraUnavailable("the capture session rejected the video output")
    }
    session.addOutput(videoOutput)
    // Front-facing footage is written mirrored, so the file matches the
    // viewfinder it was framed against; the back camera never is.
    //
    // Set explicitly rather than left to `automaticallyAdjustsVideoMirroring`,
    // which mirrors the *preview* convention onto a data output only sometimes.
    // Clearing it first is mandatory: assigning `isVideoMirrored` while it is
    // on raises an exception rather than returning an error.
    if let connection = videoOutput.connection(with: .video),
      connection.isVideoMirroringSupported
    {
      connection.automaticallyAdjustsVideoMirroring = false
      connection.isVideoMirrored = position == .front
    }
  }

  /// Both cameras at once.
  ///
  /// Multi-cam forms no implicit connections, so every input and output is
  /// added with none and wired by hand. It also refuses `sessionPreset`, so the
  /// tier is applied by choosing each device's `activeFormat` directly.
  private func configureDualCameras() throws {
    session.sessionPreset = .inputPriority

    guard
      let back = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back),
      let front = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .front)
    else {
      throw RecorderError.cameraUnavailable("this device does not have both cameras")
    }

    try attachCamera(back, output: videoOutput, isFront: false)
    try attachCamera(front, output: frontOutput, isFront: true)

    // The two cameras share a fixed hardware budget. Over it, the session will
    // simply refuse to run — so the front camera, whose picture ends up a
    // thirtieth of the frame, gives up resolution until the pair fits.
    try fitHardwareCost(front: front)
  }

  /// Adds one camera and wires it to its own output.
  @discardableResult
  private func attachCamera(
    _ device: AVCaptureDevice,
    output: AVCaptureVideoDataOutput,
    isFront: Bool
  ) throws -> AVCaptureInput.Port {
    // A smaller format for the front camera: it is only ever drawn into the
    // corner, and decoding it at the back camera's tier would spend the shared
    // budget on pixels nobody sees.
    try applyFormat(
      to: device,
      targetHeight: isFront ? WatermarkStyle.Pip.frontTargetHeight : quality.targetHeight
    )

    guard
      let input = try? AVCaptureDeviceInput(device: device),
      session.canAddInput(input)
    else {
      throw RecorderError.cameraUnavailable("the session rejected the \(isFront ? "front" : "back") camera")
    }
    session.addInputWithNoConnections(input)

    guard
      let port = input.ports(
        for: .video,
        sourceDeviceType: device.deviceType,
        sourceDevicePosition: device.position
      ).first
    else {
      throw RecorderError.cameraUnavailable("the \(isFront ? "front" : "back") camera exposed no video port")
    }

    output.alwaysDiscardsLateVideoFrames = true
    output.setSampleBufferDelegate(self, queue: sessionQueue)
    guard session.canAddOutput(output) else {
      throw RecorderError.cameraUnavailable("the session rejected a video output")
    }
    session.addOutputWithNoConnections(output)

    let connection = AVCaptureConnection(inputPorts: [port], output: output)
    guard session.canAddConnection(connection) else {
      throw RecorderError.cameraUnavailable("the session rejected a video connection")
    }
    // Same rule as the single-camera path: the front stream is mirrored, and
    // here that reaches the file through the corner inset the writer composites.
    if connection.isVideoMirroringSupported {
      connection.automaticallyAdjustsVideoMirroring = false
      connection.isVideoMirrored = isFront
    }
    session.addConnection(connection)
    return port
  }

  /// Picks the multi-cam-capable format closest to `targetHeight`, preferring
  /// one no taller than asked for.
  ///
  /// `isMultiCamSupported` is the load-bearing filter: a format the sensor
  /// offers in single-camera mode is often unavailable when both are running,
  /// and setting one is what makes `commitConfiguration` fail outright.
  private func applyFormat(to device: AVCaptureDevice, targetHeight: Int) throws {
    let candidates = device.formats.filter(\.isMultiCamSupported)
    guard !candidates.isEmpty else {
      throw RecorderError.cameraUnavailable("no multi-camera format on this device")
    }
    let best =
      candidates
      .min {
        heightDistance($0, targetHeight) < heightDistance($1, targetHeight)
      }
    guard let best else { return }

    try device.lockForConfiguration()
    defer { device.unlockForConfiguration() }
    device.activeFormat = best
    if device.position == .front {
      let duration = CMTime(value: 1, timescale: WatermarkStyle.Pip.frameRate)
      // Clamped to what the chosen format will actually accept, or this throws
      // and takes the whole session down for the sake of a frame rate.
      if let range = best.videoSupportedFrameRateRanges.first,
        duration >= range.minFrameDuration, duration <= range.maxFrameDuration
      {
        device.activeVideoMinFrameDuration = duration
        device.activeVideoMaxFrameDuration = duration
      }
    }
  }

  /// Distance from a format's height to the target, with anything *taller* than
  /// asked for pushed to the back: overshooting costs the shared budget for
  /// resolution nobody requested.
  private func heightDistance(_ format: AVCaptureDevice.Format, _ target: Int) -> Int {
    let height = Int(CMVideoFormatDescriptionGetDimensions(format.formatDescription).height)
    let distance = abs(height - target)
    return height > target ? distance + OVERSHOOT_PENALTY : distance
  }

  /// Steps the front camera down until the pair fits the hardware budget.
  ///
  /// A cost over 1 means the session will not run at all. Rather than fail
  /// Play, the front camera gives up format after format — and if it still will
  /// not fit, the caller records back-only. A dashcam that refuses to record the
  /// road because the selfie camera would not fit is the worst outcome here.
  private func fitHardwareCost(front: AVCaptureDevice) throws {
    guard let multiCam = session as? AVCaptureMultiCamSession else { return }

    let height = { (format: AVCaptureDevice.Format) in
      CMVideoFormatDescriptionGetDimensions(format.formatDescription).height
    }
    // Strictly smaller than what is set now, largest first: the front camera
    // gives up as little resolution as will fit, one step at a time, rather
    // than collapsing to the smallest format the moment the budget is tight.
    var candidates =
      front.formats
      .filter { $0.isMultiCamSupported && height($0) < height(front.activeFormat) }
      .sorted { height($0) > height($1) }

    var attempts = 0
    while multiCam.hardwareCost > 1 || multiCam.systemPressureCost > 1, attempts < MAX_COST_RETRIES {
      attempts += 1
      guard let smaller = candidates.first else { break }
      candidates.removeFirst()
      // Only touch the format if the lock was actually taken: assigning
      // `activeFormat` unlocked is undefined behaviour, not a no-op.
      guard (try? front.lockForConfiguration()) != nil else { break }
      front.activeFormat = smaller
      front.unlockForConfiguration()
    }

    if multiCam.hardwareCost > 1 || multiCam.systemPressureCost > 1 {
      throw RecorderError.cameraUnavailable(
        "this device cannot run both cameras at the selected quality")
    }
  }

  private func preset(for quality: VideoQuality) -> AVCaptureSession.Preset {
    switch quality {
    case .sd360: return session.canSetSessionPreset(.cif352x288) ? .cif352x288 : .vga640x480
    case .sd480: return .vga640x480
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
    case .sd360: size = (640, 360)
    case .sd480: size = (640, 480)
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

  /// The front frame to draw, or nil if the front camera has gone quiet.
  ///
  /// Staleness is checked here, at composite time: a camera that stops
  /// delivering never calls back to retract anything, and a frozen inset burned
  /// into footage that is timestamped as live is the one failure that would
  /// make a saved clip actively misleading. An empty corner reads, correctly,
  /// as "the front camera stopped".
  private func currentFrontFrame() -> CIImage? {
    guard let latest = latestFront else { return nil }
    guard Date().timeIntervalSince(latest.at) <= WatermarkStyle.Pip.maxFrameAge else { return nil }
    return latest.image
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
    // Already on `sessionQueue` — every output was handed that queue.
    if output === frontOutput {
      // The front camera is never written to a file; it is held for the writer
      // to composite into the next back-camera frame.
      guard let pixels = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
      latestFront = (CIImage(cvPixelBuffer: pixels), Date())
      return
    }
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
  /// Video goes through the adaptor rather than the input directly: the
  /// timestamp is composited onto every frame, so what reaches the writer is a
  /// pixel buffer we drew, not the one the camera handed over.
  private let videoAdaptor: AVAssetWriterInputPixelBufferAdaptor
  private let audioInput: AVAssetWriterInput?
  /// The front camera's newest frame in `both` mode; nil-returning otherwise.
  private let frontFrame: () -> CIImage?
  /// The already-formatted speed field for the frame being drawn, or "" when
  /// the stamp carries no speed. A closure rather than a value because the unit
  /// can change mid-clip and the reading changes every second.
  private let speedField: () -> String
  private let onFinished: (Clip) -> Void
  private let onError: (Error) -> Void

  private var sessionStartPTS: CMTime?
  private var lastPTS: CMTime?
  private var isFinishing = false

  init(
    output: URL,
    videoSettings: [String: Any],
    audioSettings: [String: Any]?,
    frontFrame: @escaping () -> CIImage?,
    speedField: @escaping () -> String,
    onFinished: @escaping (Clip) -> Void,
    onError: @escaping (Error) -> Void
  ) throws {
    self.output = output
    self.frontFrame = frontFrame
    self.speedField = speedField
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

    // 32BGRA because that is what CoreImage renders into without a conversion
    // pass. Dimensions come from the settings the output itself recommended, so
    // the pool matches the frames the camera is actually delivering.
    var attributes: [String: Any] = [
      kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA
    ]
    if let width = videoSettings[AVVideoWidthKey] as? Int,
      let height = videoSettings[AVVideoHeightKey] as? Int
    {
      attributes[kCVPixelBufferWidthKey as String] = width
      attributes[kCVPixelBufferHeightKey as String] = height
    }
    videoAdaptor = AVAssetWriterInputPixelBufferAdaptor(
      assetWriterInput: videoInput,
      sourcePixelBufferAttributes: attributes
    )

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

    if isVideo {
      appendVideo(sampleBuffer, at: pts)
      let duration = CMSampleBufferGetDuration(sampleBuffer)
      lastPTS = duration.isNumeric ? pts + duration : pts
    } else {
      input.append(sampleBuffer)
    }
  }

  /// Composites the front-camera inset and the timestamp onto the frame on its
  /// way to the writer.
  ///
  /// Every failure here falls back to appending the untouched frame: footage
  /// without a stamp is worth incomparably more than a hole in the buffer, and
  /// the recorder must not stop recording because a plate would not rasterise.
  private func appendVideo(_ sampleBuffer: CMSampleBuffer, at pts: CMTime) {
    guard let source = CMSampleBufferGetImageBuffer(sampleBuffer) else {
      videoInput.append(sampleBuffer)
      return
    }
    let size = CGSize(
      width: CVPixelBufferGetWidth(source),
      height: CVPixelBufferGetHeight(source)
    )
    let renderer = WatermarkRenderer.shared
    guard
      // The pool only exists once writing has started, which `init` does last.
      let pool = videoAdaptor.pixelBufferPool,
      // Wall clock read here rather than derived from the PTS: the two agree to
      // within the pipeline's own latency, and the frame's own clock is on a
      // timebase with no defined relationship to the calendar.
      let overlay = renderer.overlay(for: Date(), speed: speedField(), pixelSize: size)
    else {
      videoInput.append(sampleBuffer)
      return
    }

    var destination: CVPixelBuffer?
    guard
      CVPixelBufferPoolCreatePixelBuffer(nil, pool, &destination) == kCVReturnSuccess,
      let destination
    else {
      videoInput.append(sampleBuffer)
      return
    }

    // Inset first, clock on top. They sit in opposite corners and cannot
    // overlap, but fixing the order means a later change to either one's
    // geometry cannot end up burying the timestamp. Everything is stacked
    // before a single render pass, not rendered twice.
    var composed = CIImage(cvPixelBuffer: source)
    if let front = frontFrame(), let pip = renderer.pip(for: front, pixelSize: size) {
      composed = pip.composited(over: composed)
    }
    renderer.context.render(overlay.composited(over: composed), to: destination)
    if !videoAdaptor.append(destination, withPresentationTime: pts) {
      videoInput.append(sampleBuffer)
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
