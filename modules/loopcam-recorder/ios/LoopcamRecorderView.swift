import AVFoundation
import ExpoModulesCore
import UIKit

/// Live preview surface. It is a *window onto* the capture session, never the
/// owner of it — mounting or unmounting must not start or stop the rolling
/// buffer, which is what lets the UI navigate away while recording continues.
///
/// All it does is subscribe to `CameraPreviewBus`; whether anything is recording
/// is none of its business.
class LoopcamRecorderView: ExpoView {
  private var previewLayer: AVCaptureVideoPreviewLayer?
  /// The front camera's inset in `both` mode; nil in the single-camera modes.
  private var frontLayer: AVCaptureVideoPreviewLayer?
  private var videoGravity: AVLayerVideoGravity = .resizeAspectFill
  private var busKey: ObjectIdentifier?

  /// The preview's copy of the burned-in clock.
  ///
  /// iOS feeds this layer straight from `AVCaptureSession`, so it never sees
  /// the composite the writer applies — the viewfinder would otherwise be the
  /// one place the timestamp is missing. This is a best-effort match for
  /// framing purposes; the file is the source of truth. (Android needs no
  /// equivalent: its overlay effect targets the preview and the encoder at
  /// once.)
  private let plateLayer = CALayer()
  private let stampLayer = CATextLayer()
  private var stampTimer: Timer?
  private let stampFormatter: DateFormatter = {
    let formatter = DateFormatter()
    formatter.locale = Locale(identifier: "en_US_POSIX")
    formatter.dateFormat = WatermarkStyle.pattern
    return formatter
  }()

  required init(appContext: AppContext? = nil) {
    super.init(appContext: appContext)
    clipsToBounds = true
    setUpStamp()

    let key = ObjectIdentifier(self)
    busKey = key
    // Fires immediately with whatever is current, so a preview mounted after
    // Play still lights up.
    CameraPreviewBus.shared.subscribe(key: key) { [weak self] session in
      self?.attach(session: session)
    }
  }

  deinit {
    stampTimer?.invalidate()
    if let busKey {
      CameraPreviewBus.shared.unsubscribe(key: busKey)
    }
  }

  private func setUpStamp() {
    // Plate and text as two layers rather than a CATextLayer with a background:
    // CATextLayer draws its line from the top of its own box, so a single layer
    // would sit the text against the plate's top edge instead of centred in it.
    plateLayer.backgroundColor = UIColor.black.withAlphaComponent(WatermarkStyle.plateAlpha).cgColor
    stampLayer.foregroundColor = UIColor.white.cgColor
    stampLayer.alignmentMode = .center
    stampLayer.contentsScale = UIScreen.main.scale
    plateLayer.addSublayer(stampLayer)
    layer.addSublayer(plateLayer)
    tickStamp()

    // One second, matching the burn-in's own resolution. A faster timer would
    // redraw the same string; a slower one would let the preview fall behind
    // the file it is previewing.
    let timer = Timer(timeInterval: 1, repeats: true) { [weak self] _ in self?.tickStamp() }
    // .common, or the clock freezes for as long as a finger is on the screen.
    RunLoop.main.add(timer, forMode: .common)
    stampTimer = timer
  }

  private func tickStamp() {
    stampLayer.string = stampFormatter.string(from: Date())
    // Fixed-width digits keep the box a constant size, so this is only a
    // no-op re-layout — but the first tick is what gives it a size at all.
    layoutStamp()
  }

  /// Bottom-right, sized from the same fractions the burn-in uses, so the
  /// viewfinder's stamp sits where the recorded one will.
  private func layoutStamp() {
    guard bounds.height > 0 else { return }
    let textHeight = bounds.height * WatermarkStyle.textHeightFraction
    let font = UIFont.monospacedDigitSystemFont(ofSize: textHeight, weight: .medium)
    stampLayer.font = font
    stampLayer.fontSize = textHeight

    let text = (stampLayer.string as? String) ?? ""
    let textSize = (text as NSString).size(withAttributes: [.font: font])
    let padX = textHeight * WatermarkStyle.padXFraction
    let padY = textHeight * WatermarkStyle.padYFraction
    let width = textSize.width + padX * 2
    let height = textSize.height + padY * 2
    let inset = min(bounds.width, bounds.height) * WatermarkStyle.insetFraction

    // The layers are positioned, not animated into place; without this the
    // plate slides across the screen on every rotation and remount.
    CATransaction.begin()
    CATransaction.setDisableActions(true)
    plateLayer.cornerRadius = height * WatermarkStyle.cornerFraction
    plateLayer.frame = CGRect(
      x: bounds.maxX - inset - width,
      y: bounds.maxY - inset - height,
      width: width,
      height: height
    )
    stampLayer.frame = CGRect(x: 0, y: padY, width: width, height: textSize.height)
    CATransaction.commit()
  }

  private func attach(session: AVCaptureSession?) {
    previewLayer?.removeFromSuperlayer()
    previewLayer = nil
    frontLayer?.removeFromSuperlayer()
    frontLayer = nil

    guard let session else { return }
    let layer = AVCaptureVideoPreviewLayer(session: session)
    layer.videoGravity = videoGravity
    layer.frame = bounds
    // Underneath, or a session attaching later would bury the timestamp.
    self.layer.insertSublayer(layer, at: 0)
    previewLayer = layer
    attachFront(session: session)
    setNeedsLayout()
  }

  /// The front camera's inset, when the session is running both.
  ///
  /// The same problem as the timestamp, with the same answer: iOS feeds a
  /// preview layer straight from the session, so it never sees the composite
  /// the writer applies, and the viewfinder would otherwise be the one place
  /// the inset is missing. A second layer wired explicitly to the front port
  /// reconstructs it.
  ///
  /// The view is not told which mode is active and does not need to be: the
  /// presence of a front video port *is* the answer, which keeps this a window
  /// onto the session rather than a second reader of `RecorderConfig`.
  private func attachFront(session: AVCaptureSession) {
    let frontPort = session.inputs
      .compactMap { $0 as? AVCaptureDeviceInput }
      .first { $0.device.position == .front }
      .flatMap { $0.ports(for: .video, sourceDeviceType: nil, sourceDevicePosition: .front).first }
    guard let frontPort else { return }

    // No implicit connection: the session already owns this port for the
    // recording path, and a second automatic connection would fight it.
    let layer = AVCaptureVideoPreviewLayer(sessionWithNoConnection: session)
    layer.videoGravity = .resizeAspectFill
    layer.masksToBounds = true
    layer.borderColor = UIColor.white.withAlphaComponent(WatermarkStyle.Pip.borderAlpha).cgColor

    let connection = AVCaptureConnection(inputPort: frontPort, videoPreviewLayer: layer)
    // Unmirrored, matching the file. A viewfinder that disagreed with the
    // footage would defeat the point of compositing at capture.
    if connection.isVideoMirroringSupported {
      connection.automaticallyAdjustsVideoMirroring = false
      connection.isVideoMirrored = false
    }
    guard session.canAddConnection(connection) else { return }
    session.addConnection(connection)

    // Above the feed, below the stamp — the same stacking the burned-in
    // composite uses.
    self.layer.insertSublayer(layer, below: plateLayer)
    frontLayer = layer
  }

  /// Top-right, sized from the same fractions the burn-in uses, so the
  /// viewfinder's inset sits where the recorded one will.
  private func layoutFront() {
    guard let frontLayer, bounds.width > 0 else { return }
    let width = bounds.width * WatermarkStyle.Pip.widthFraction
    let height = width / WatermarkStyle.Pip.aspect
    let inset = min(bounds.width, bounds.height) * WatermarkStyle.insetFraction

    CATransaction.begin()
    CATransaction.setDisableActions(true)
    frontLayer.frame = CGRect(
      x: bounds.maxX - inset - width,
      y: bounds.minY + inset,
      width: width,
      height: height
    )
    frontLayer.cornerRadius = height * WatermarkStyle.Pip.cornerFraction
    frontLayer.borderWidth = min(bounds.width, bounds.height)
      * WatermarkStyle.Pip.borderWidthFraction
    CATransaction.commit()
  }

  func setResizeMode(_ mode: String) {
    // Held as state, not just pushed at the layer: the prop can arrive before
    // the session does, and the layer built later has to honour it.
    videoGravity = mode == "contain" ? .resizeAspect : .resizeAspectFill
    previewLayer?.videoGravity = videoGravity
  }

  override func layoutSubviews() {
    super.layoutSubviews()
    previewLayer?.frame = bounds
    layoutFront()
    layoutStamp()
  }
}
