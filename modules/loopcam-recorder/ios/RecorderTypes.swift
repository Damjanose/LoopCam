import ExpoModulesCore
import Foundation

/// §2.3 state machine. Mirrors `RecorderState` in LoopcamRecorder.types.ts.
enum RecorderState: String {
  case idle
  case recording
  case saving
  case stopping
}

enum VideoQuality: String, CaseIterable {
  case sd360 = "360p"
  case sd480 = "480p"
  case hd720 = "720p"
  case hd1080 = "1080p"
  case uhd4k = "4k"

  var bitrate: Int {
    switch self {
    case .sd360: return 800_000
    case .sd480: return 1_200_000
    case .hd720: return 2_500_000
    case .hd1080: return 5_000_000
    case .uhd4k: return 20_000_000
    }
  }

  /// Target height. Multi-cam cannot use session presets, so formats are picked
  /// against this directly; the single-camera path still uses presets.
  var targetHeight: Int {
    switch self {
    case .sd360: return 360
    case .sd480: return 480
    case .hd720: return 720
    case .hd1080: return 1080
    case .uhd4k: return 2160
    }
  }
}

/// Which camera(s) feed the buffer. Mirrors `CameraMode` on the JS side.
///
/// `both` is a composite, not two recordings: the front camera is drawn into
/// the top-right of the back camera's frame before it reaches the writer, so
/// one file comes out and every type downstream is untouched by the choice.
enum CameraMode: String, CaseIterable {
  case back
  case front
  case both
}

/// Unit for the burned-in speed. Mirrors `SpeedUnit` on the JS side.
///
/// A display preference only — the sidecar is always SI regardless (§Part 3).
/// Baking a preference into stored evidence means a file that can be misread
/// later; baking it into the picture is unavoidable and is why the unit is
/// always drawn next to the number.
enum SpeedUnit: String, CaseIterable {
  case kmh
  case mph

  var label: String {
    switch self {
    case .kmh: return "km/h"
    case .mph: return "mph"
    }
  }

  /// Multiplier from metres per second into this unit.
  var perMps: Double {
    switch self {
    case .kmh: return 3.6
    case .mph: return 2.236_936_292_054_402
    }
  }
}

/// §2.1 — the two independent settings, plus the v1 feature toggles.
struct RecorderConfig: Record {
  @Field var clipDurationSec: Double = 10
  @Field var bufferDurationSec: Double = 120
  @Field var quality: String = "1080p"
  @Field var cameraMode: String = "back"
  @Field var audioEnabled: Bool = true
  @Field var locationTaggingEnabled: Bool = true
  @Field var speedUnit: String = "kmh"
  @Field var impactDetectionEnabled: Bool = true
  @Field var autoStopBatteryPercent: Int = 15

  /// §2.2 — max_clips = ceil(buffer_duration / clip_duration).
  var maxClips: Int {
    max(1, Int(ceil(bufferDurationSec / clipDurationSec)))
  }

  var videoQuality: VideoQuality {
    VideoQuality(rawValue: quality) ?? .hd1080
  }

  /// Anything unrecognised — a value written by a newer build — falls back.
  var camera: CameraMode {
    CameraMode(rawValue: cameraMode) ?? .back
  }

  var speed: SpeedUnit {
    SpeedUnit(rawValue: speedUnit) ?? .kmh
  }

  func asDictionary() -> [String: Any] {
    [
      "clipDurationSec": clipDurationSec,
      "bufferDurationSec": bufferDurationSec,
      "quality": quality,
      "cameraMode": cameraMode,
      "audioEnabled": audioEnabled,
      "locationTaggingEnabled": locationTaggingEnabled,
      "speedUnit": speedUnit,
      "impactDetectionEnabled": impactDetectionEnabled,
      "autoStopBatteryPercent": autoStopBatteryPercent,
    ]
  }
}

/// Snapshot of the ring buffer, emitted on every clip boundary.
struct BufferStatus {
  let state: RecorderState
  let clipCount: Int
  let maxClips: Int
  let bufferedSec: Double
  let bufferedBytes: Int64
  let elapsedSec: Double

  func asDictionary() -> [String: Any] {
    [
      "state": state.rawValue,
      "clipCount": clipCount,
      "maxClips": maxClips,
      "bufferedSec": bufferedSec,
      "bufferedBytes": bufferedBytes,
      "elapsedSec": elapsedSec,
    ]
  }
}

enum SaveTrigger: String {
  case manual
  case impact
  case lowBattery
}

struct SavedClip {
  let id: String
  let url: URL
  let metadataURL: URL?
  let createdAtMs: Double
  let durationSec: Double
  let sizeBytes: Int64
  let isProtected: Bool
  let trigger: SaveTrigger

  func asDictionary() -> [String: Any?] {
    [
      "id": id,
      "uri": url.absoluteString,
      "metadataUri": metadataURL?.absoluteString,
      "createdAtMs": createdAtMs,
      "durationSec": durationSec,
      "sizeBytes": sizeBytes,
      "protected": isProtected,
      "trigger": trigger.rawValue,
    ]
  }
}

struct StorageStatus {
  let freeBytes: Int64
  let savedBytes: Int64
  let savedClipCount: Int
  let lowSpaceWarning: Bool

  func asDictionary() -> [String: Any] {
    [
      "freeBytes": freeBytes,
      "savedBytes": savedBytes,
      "savedClipCount": savedClipCount,
      "lowSpaceWarning": lowSpaceWarning,
    ]
  }
}

enum RecorderErrorCode: String {
  case permissionDenied
  case cameraUnavailable
  case storageFull
  case mergeFailed
  case serviceKilled
  case unknown
}

enum RecorderError: Error, LocalizedError {
  case notRecording
  case emptyBuffer
  case cameraUnavailable(String)
  case mergeFailed(String)

  var errorDescription: String? {
    switch self {
    case .notRecording: return "Not recording"
    case .emptyBuffer: return "Buffer is empty"
    case .cameraUnavailable(let reason): return "Camera unavailable: \(reason)"
    case .mergeFailed(let reason): return "Merge failed: \(reason)"
    }
  }
}
