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

    let durationSec = try concatenate(clips: clips, destination: destination)
    if config.locationTaggingEnabled {
      writeMetadataSidecar(for: destination, clips: clips)
    }

    // Only advertise a sidecar that is actually on disk: the phase-5 writer is
    // still a no-op, and a URI pointing at nothing is worse than none at all.
    let sidecar = storage.metadataURL(for: destination)
    let hasSidecar = FileManager.default.fileExists(atPath: sidecar.path)

    return SavedClip(
      id: destination.deletingPathExtension().lastPathComponent,
      url: destination,
      metadataURL: hasSidecar ? sidecar : nil,
      createdAtMs: Date().timeIntervalSince1970 * 1000,
      // The composition's own length, not the sum of the buffer's bookkeeping:
      // a clip that failed to load was skipped, and the saved file has to
      // describe itself.
      durationSec: durationSec,
      sizeBytes: storage.fileSize(at: destination),
      isProtected: false,
      trigger: trigger
    )
  }

  /// Stream-copy every clip into one MP4, returning the merged duration.
  ///
  /// The video track's time range drives the cursor rather than the asset's
  /// duration: audio usually runs a few milliseconds past the last frame, and
  /// letting that set the seam would stack a growing A/V drift across a window
  /// of a dozen clips.
  private func concatenate(clips: [Clip], destination: URL) throws -> Double {
    let composition = AVMutableComposition()
    guard
      let videoTrack = composition.addMutableTrack(
        withMediaType: .video, preferredTrackID: kCMPersistentTrackID_Invalid
      )
    else {
      throw RecorderError.mergeFailed("could not create the composition video track")
    }
    var audioTrack: AVMutableCompositionTrack?
    var cursor = CMTime.zero

    for clip in clips {
      let asset = AVURLAsset(url: clip.url)
      // A clip that will not open is one bad segment, not a failed save — the
      // rest of the window is still the footage the user asked for.
      guard
        let source = try? loadSync({ try await asset.loadTracks(withMediaType: .video) }).first,
        let videoRange = try? loadSync({ try await source.load(.timeRange) }),
        videoRange.duration.isNumeric
      else { continue }

      guard (try? videoTrack.insertTimeRange(videoRange, of: source, at: cursor)) != nil else {
        continue
      }
      if
        cursor == .zero,
        let transform = try? loadSync({ try await source.load(.preferredTransform) })
      {
        // Rotation lives on the source track and inserting does not carry it
        // over; without this the saved file plays on its side.
        videoTrack.preferredTransform = transform
      }

      if
        let audioSource = try? loadSync({ try await asset.loadTracks(withMediaType: .audio) }).first,
        let audioRange = try? loadSync({ try await audioSource.load(.timeRange) })
      {
        if audioTrack == nil {
          audioTrack = composition.addMutableTrack(
            withMediaType: .audio, preferredTrackID: kCMPersistentTrackID_Invalid
          )
        }
        let clamped = CMTimeRange(
          start: audioRange.start,
          duration: min(audioRange.duration, videoRange.duration)
        )
        try? audioTrack?.insertTimeRange(clamped, of: audioSource, at: cursor)
      }

      cursor = cursor + videoRange.duration
    }

    guard cursor > .zero else {
      throw RecorderError.mergeFailed("none of the buffered clips could be read")
    }

    try export(composition, to: destination)
    return cursor.seconds
  }

  private func export(_ composition: AVComposition, to destination: URL) throws {
    guard
      let session = AVAssetExportSession(
        asset: composition, presetName: AVAssetExportPresetPassthrough
      )
    else {
      throw RecorderError.mergeFailed("could not create the export session")
    }
    try? FileManager.default.removeItem(at: destination)
    session.outputURL = destination
    session.outputFileType = .mp4
    session.shouldOptimizeForNetworkUse = true

    // Save runs on its own background queue and its caller waits on the merged
    // file, so blocking here is the honest shape rather than a hidden cost.
    let semaphore = DispatchSemaphore(value: 0)
    session.exportAsynchronously { semaphore.signal() }
    semaphore.wait()

    guard session.status == .completed else {
      try? FileManager.default.removeItem(at: destination)
      throw RecorderError.mergeFailed(
        session.error?.localizedDescription ?? "the export did not complete"
      )
    }
  }

  /// AVFoundation's property loading is async-only from iOS 16 on, while the
  /// merge is a synchronous step on a background queue. One bridge here beats
  /// scattering the deprecated synchronous accessors through the file.
  private func loadSync<T>(_ operation: @escaping () async throws -> T) throws -> T {
    let semaphore = DispatchSemaphore(value: 0)
    var result: Result<T, Error>?
    Task {
      do {
        result = .success(try await operation())
      } catch {
        result = .failure(error)
      }
      semaphore.signal()
    }
    semaphore.wait()
    guard let result else {
      throw RecorderError.mergeFailed("a track property never loaded")
    }
    return try result.get()
  }

  /// §7.1 — GPS/speed/timestamp sidecar written next to the saved clip.
  ///
  /// TODO(phase-5): serialize the CoreLocation samples collected during the
  /// window (1 fix / 2–3 s, §6) as JSON.
  private func writeMetadataSidecar(for destination: URL, clips: [Clip]) {
    // TODO(phase-5)
  }
}
