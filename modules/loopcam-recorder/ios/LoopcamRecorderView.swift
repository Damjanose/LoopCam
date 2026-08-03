import AVFoundation
import ExpoModulesCore

/// Live preview surface. It is a *window onto* the capture session, never the
/// owner of it — mounting or unmounting must not start or stop the rolling
/// buffer, which is what lets the UI navigate away while recording continues.
///
/// All it does is subscribe to `CameraPreviewBus`; whether anything is recording
/// is none of its business.
class LoopcamRecorderView: ExpoView {
  private var previewLayer: AVCaptureVideoPreviewLayer?
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

    guard let session else { return }
    let layer = AVCaptureVideoPreviewLayer(session: session)
    layer.videoGravity = videoGravity
    layer.frame = bounds
    self.layer.addSublayer(layer)
    previewLayer = layer
    setNeedsLayout()
  }

  func setLens(_ lens: String) {
    // TODO(phase-1): swap the AVCaptureDeviceInput on the live session;
    // front-facing only matters once multi-camera (§8, v2) lands.
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
  }
}
