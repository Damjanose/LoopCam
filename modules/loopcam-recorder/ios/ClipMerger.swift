import AVFoundation
import Foundation

/// §4 — Save concatenates the frozen window into one permanent file.
///
/// `AVMutableComposition` + a passthrough export: every clip came out of the
/// same writer with identical settings, so this is a stream copy rather than a
/// re-encode — fast, and the cheapest possible CPU use while the live recording
/// keeps running (§6).
final class ClipMerger {
  private let storage: StorageManager

  init(storage: StorageManager) {
    self.storage = storage
  }

  func merge(
    clips: [Clip],
    destination: URL,
    config: RecorderConfig,
    trigger: SaveTrigger
  ) throws -> SavedClip {
    guard !clips.isEmpty else { throw RecorderError.emptyBuffer }

    try concatenate(clips: clips, destination: destination)
    if config.locationTaggingEnabled {
      writeMetadataSidecar(for: destination, clips: clips)
    }

    return SavedClip(
      id: destination.deletingPathExtension().lastPathComponent,
      url: destination,
      metadataURL: config.locationTaggingEnabled ? storage.metadataURL(for: destination) : nil,
      createdAtMs: Date().timeIntervalSince1970 * 1000,
      durationSec: clips.reduce(0) { $0 + $1.durationSec },
      sizeBytes: storage.fileSize(at: destination),
      isProtected: false,
      trigger: trigger
    )
  }

  /// TODO(phase-1): build an AVMutableComposition —
  ///   1. one video track and one audio track on the composition;
  ///   2. insertTimeRange each clip's asset at a running cursor;
  ///   3. export with AVAssetExportPresetPassthrough to `destination`;
  ///   4. fall back to FFmpeg-kit only if a device produces mismatched formats
  ///      mid-session (rotation change, resolution fallback).
  /// Throwing until then, so nothing silently reports a successful save.
  private func concatenate(clips: [Clip], destination: URL) throws {
    throw RecorderError.mergeFailed("ClipMerger.concatenate not implemented yet (Phase 1)")
  }

  /// §7.1 — GPS/speed/timestamp sidecar written next to the saved clip.
  ///
  /// TODO(phase-5): serialize the CoreLocation samples collected during the
  /// window (1 fix / 2–3 s, §6) as JSON.
  private func writeMetadataSidecar(for destination: URL, clips: [Clip]) {
    // TODO(phase-5)
  }
}
