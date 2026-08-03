import ExpoModulesCore
import Foundation

/// §2.3 state machine. Mirrors `RecorderState` in LoopcamRecorder.types.ts.
enum RecorderState: String {
  case idle
  case recording
  case saving
  case stopping
}

enum VideoQuality: String {
  case hd720 = "720p"
  case hd1080 = "1080p"
  case uhd4k = "4k"

  var bitrate: Int {
    switch self {
    case .hd720: return 2_500_000
    case .hd1080: return 5_000_000
    case .uhd4k: return 20_000_000
    }
  }
}

/// §2.1 — the two independent settings, plus the v1 feature toggles.
struct RecorderConfig: Record {
  @Field var clipDurationSec: Double = 10
  @Field var bufferDurationSec: Double = 120
  @Field var quality: String = "1080p"
  @Field var audioEnabled: Bool = true
  @Field var locationTaggingEnabled: Bool = true
  @Field var impactDetectionEnabled: Bool = true
  @Field var autoStopBatteryPercent: Int = 15

  /// §2.2 — max_clips = ceil(buffer_duration / clip_duration).
  var maxClips: Int {
    max(1, Int(ceil(bufferDurationSec / clipDurationSec)))
  }

  var videoQuality: VideoQuality {
    VideoQuality(rawValue: quality) ?? .hd1080
  }

  func asDictionary() -> [String: Any] {
    [
      "clipDurationSec": clipDurationSec,
      "bufferDurationSec": bufferDurationSec,
      "quality": quality,
      "audioEnabled": audioEnabled,
      "locationTaggingEnabled": locationTaggingEnabled,
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
