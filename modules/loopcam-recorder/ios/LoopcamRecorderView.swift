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

  required init(appContext: AppContext? = nil) {
    super.init(appContext: appContext)
    clipsToBounds = true

    let key = ObjectIdentifier(self)
    busKey = key
    // Fires immediately with whatever is current, so a preview mounted after
    // Play still lights up.
    CameraPreviewBus.shared.subscribe(key: key) { [weak self] session in
      self?.attach(session: session)
    }
  }

  deinit {
    if let busKey {
      CameraPreviewBus.shared.unsubscribe(key: busKey)
    }
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
    // Mirroring is left to AVFoundation's own default for the connected lens:
    // the front viewfinder reads as a mirror, which is what every camera on the
    // phone does and what a driver checking their framing expects. Forcing it
    // off is what left the front preview reversed left-to-right. The file is
    // still written unmirrored (`SegmentRecorder`) — evidence, not a selfie.
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
    // Mirrored, matching the inset the writer burns in. A viewfinder that
    // disagreed with the footage would defeat the point of compositing at
    // capture.
    if connection.isVideoMirroringSupported {
      connection.automaticallyAdjustsVideoMirroring = false
      connection.isVideoMirrored = true
    }
    guard session.canAddConnection(connection) else { return }
    session.addConnection(connection)

    self.layer.addSublayer(layer)
    frontLayer = layer
  }

  /// Top-right, sized from the same fractions the burn-in uses, so the
  /// viewfinder's inset sits where the recorded one will.
  ///
  /// Measured against the *picture*, not the view. The frame is 16:9 and the
  /// screen is taller, so under either gravity the two rectangles differ:
  /// `contain` letterboxes the picture inside the view, `cover` runs it off the
  /// sides. An inset pinned to the view's own corner would drift out of the
  /// recorded frame in the first case and be clipped in the second — which is
  /// exactly what it did.
  private func layoutFront() {
    guard let frontLayer, let previewLayer, bounds.width > 0 else { return }
    // Metadata coordinates are the normalised picture, so the unit rect maps to
    // whatever part of the view the picture actually occupies. Zero until the
    // connection knows its source dimensions; the view's own bounds are the
    // closest thing to an answer until the next layout pass corrects it.
    let picture = previewLayer.layerRectConverted(
      fromMetadataOutputRect: CGRect(x: 0, y: 0, width: 1, height: 1)
    )
    let frame = picture.isNull || picture.isEmpty ? bounds : picture

    let width = frame.width * WatermarkStyle.Pip.widthFraction
    let height = width / WatermarkStyle.Pip.aspect
    let inset = min(frame.width, frame.height) * WatermarkStyle.insetFraction

    CATransaction.begin()
    CATransaction.setDisableActions(true)
    frontLayer.frame = CGRect(
      x: frame.maxX - inset - width,
      y: frame.minY + inset,
      width: width,
      height: height
    )
    frontLayer.cornerRadius = height * WatermarkStyle.Pip.cornerFraction
    frontLayer.borderWidth = min(frame.width, frame.height)
      * WatermarkStyle.Pip.borderWidthFraction
    CATransaction.commit()
  }

  func setResizeMode(_ mode: String) {
    // Held as state, not just pushed at the layer: the prop can arrive before
    // the session does, and the layer built later has to honour it.
    videoGravity = mode == "contain" ? .resizeAspect : .resizeAspectFill
    previewLayer?.videoGravity = videoGravity
    // The gravity is what decides where the picture sits inside the view, and
    // the inset is positioned against the picture — so it has to be re-placed.
    setNeedsLayout()
  }

  override func layoutSubviews() {
    super.layoutSubviews()
    previewLayer?.frame = bounds
    layoutFront()
  }
}
