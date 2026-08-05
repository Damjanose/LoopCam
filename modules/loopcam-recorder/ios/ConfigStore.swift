import Foundation

/// Where the settings live between launches.
///
/// Natively rather than in JS, to match Android and for the same reason: the
/// config is already owned by the native controller, and a Play can be issued
/// from the Lock Screen before any JavaScript has run. A JS-side store would
/// leave that Play recording with the type's defaults.
///
/// Fields are stored individually, not as one encoded blob: a config written by
/// a newer build and then read by an older one gains the fields it knows and
/// ignores the rest, instead of failing to decode and silently resetting
/// everything the user chose.
enum ConfigStore {

  private static let prefix = "loopcam.config."
  private static var defaults: UserDefaults { .standard }

  static func load() -> RecorderConfig {
    var config = RecorderConfig()
    let stored = defaults

    if let value = stored.object(forKey: prefix + "clipDurationSec") as? Double {
      config.clipDurationSec = value
    }
    if let value = stored.object(forKey: prefix + "bufferDurationSec") as? Double {
      config.bufferDurationSec = value
    }
    // Round-tripped through the enums, so a tier or a mode this build does not
    // know — written by a newer one, then downgraded — lands on the default
    // rather than reaching the capture session as an unresolvable string.
    if let value = stored.string(forKey: prefix + "quality"),
      let quality = VideoQuality(rawValue: value)
    {
      config.quality = quality.rawValue
    }
    if let value = stored.string(forKey: prefix + "cameraMode"),
      let mode = CameraMode(rawValue: value)
    {
      config.cameraMode = mode.rawValue
    }
    if let value = stored.object(forKey: prefix + "audioEnabled") as? Bool {
      config.audioEnabled = value
    }
    if let value = stored.object(forKey: prefix + "locationTaggingEnabled") as? Bool {
      config.locationTaggingEnabled = value
    }
    if let value = stored.object(forKey: prefix + "impactDetectionEnabled") as? Bool {
      config.impactDetectionEnabled = value
    }
    if let value = stored.object(forKey: prefix + "autoStopBatteryPercent") as? Int {
      config.autoStopBatteryPercent = value
    }
    return config
  }

  static func save(_ config: RecorderConfig) {
    let stored = defaults
    stored.set(config.clipDurationSec, forKey: prefix + "clipDurationSec")
    stored.set(config.bufferDurationSec, forKey: prefix + "bufferDurationSec")
    stored.set(config.quality, forKey: prefix + "quality")
    stored.set(config.cameraMode, forKey: prefix + "cameraMode")
    stored.set(config.audioEnabled, forKey: prefix + "audioEnabled")
    stored.set(config.locationTaggingEnabled, forKey: prefix + "locationTaggingEnabled")
    stored.set(config.impactDetectionEnabled, forKey: prefix + "impactDetectionEnabled")
    stored.set(config.autoStopBatteryPercent, forKey: prefix + "autoStopBatteryPercent")
  }
}
