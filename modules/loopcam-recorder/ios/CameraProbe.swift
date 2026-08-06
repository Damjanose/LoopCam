import AVFoundation
import Foundation

/// What this device's cameras can actually do.
///
/// Settings renders from this instead of offering every mode and finding out at
/// Play that the hardware refuses. On a dash mount a toast is never read, and a
/// recorder that quietly records something other than what was asked for is
/// worse than one that never offered.
///
/// Everything here is a static hardware query — no session, no permission
/// prompt, nothing to wait for — so unlike Android's probe this needs no
/// background thread. The result is cached anyway, since it cannot change while
/// the process lives.
enum CameraProbe {

  private static var cached: [String: Any]?

  static func capabilities() -> [String: Any] {
    if let cached { return cached }
    let result = probe()
    cached = result
    return result
  }

  private static func probe() -> [String: Any] {
    let hasFront =
      AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .front) != nil
    let hasBack =
      AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back) != nil

    let singleTiers = VideoQuality.allCases.map(\.rawValue)

    // Multi-cam needs an A12 or newer, *and* both cameras must offer a format
    // they can hold simultaneously — the flag alone is not enough on every
    // device, and a mode offered but unbindable is the failure this exists to
    // prevent.
    let dualSupported =
      AVCaptureMultiCamSession.isMultiCamSupported && hasFront && hasBack
      && hasMultiCamFormats(.front) && hasMultiCamFormats(.back)

    // 4K is absent under `both` on every device that exists: no iPhone runs two
    // cameras at that tier, and offering it would be a promise the session
    // cannot keep.
    let dualTiers =
      dualSupported ? VideoQuality.allCases.filter { $0 != .uhd4k }.map(\.rawValue) : []

    var modes = [CameraMode.back.rawValue]
    if hasFront { modes.append(CameraMode.front.rawValue) }
    if !dualTiers.isEmpty { modes.append(CameraMode.both.rawValue) }

    return [
      "modes": modes,
      "qualities": [
        CameraMode.back.rawValue: hasBack ? singleTiers : [],
        CameraMode.front.rawValue: hasFront ? singleTiers : [],
        CameraMode.both.rawValue: dualTiers,
      ],
    ]
  }

  private static func hasMultiCamFormats(_ position: AVCaptureDevice.Position) -> Bool {
    guard
      let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: position)
    else { return false }
    return device.formats.contains(where: \.isMultiCamSupported)
  }
}
