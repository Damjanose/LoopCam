import AVFoundation
import ExpoModulesCore

/// Live preview surface. It is a *window onto* the capture session, never the
/// owner of it — mounting or unmounting must not start or stop the rolling
/// buffer, which is what lets the UI navigate away while recording continues.
class LoopcamRecorderView: ExpoView {
  private var previewLayer: AVCaptureVideoPreviewLayer?

  required init(appContext: AppContext? = nil) {
    super.init(appContext: appContext)
    clipsToBounds = true
    // TODO(phase-1): pull the shared AVCaptureSession off the module's
    // AVSegmentRecorder and attach it here.
  }

  func attach(session: AVCaptureSession) {
    previewLayer?.removeFromSuperlayer()
    let layer = AVCaptureVideoPreviewLayer(session: session)
    layer.videoGravity = .resizeAspectFill
    self.layer.addSublayer(layer)
    previewLayer = layer
    setNeedsLayout()
  }

  func setLens(_ lens: String) {
    // TODO(phase-1): swap the AVCaptureDeviceInput on the live session;
    // front-facing only matters once multi-camera (§8, v2) lands.
  }

  func setResizeMode(_ mode: String) {
    previewLayer?.videoGravity = mode == "contain" ? .resizeAspect : .resizeAspectFill
  }

  override func layoutSubviews() {
    super.layoutSubviews()
    previewLayer?.frame = bounds
  }
}
