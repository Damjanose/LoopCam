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
      writeMetadataSidecar(for: destination, clips: clips, durationSec: durationSec)
    }

    // Only advertise a sidecar that is actually on disk. The writer swallows
    // its own failures rather than losing the merged clip over them, so this
    // check is the one thing standing between a JSON file that never got
    // written and a URI pointing at nothing.
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
  /// Mirrors `android/…/ClipMerger.kt` byte for byte: a sidecar that described
  /// the same drive differently depending on the phone it was recorded on would
  /// be worth very little as evidence. Note that `Clip.startedAt` is a `Date`
  /// here and epoch millis on Android — the conversion is what keeps the two
  /// files identical.
  ///
  /// Failures are swallowed rather than propagated. The merged MP4 is already
  /// on disk and is the thing the user pressed Save for; losing it because a
  /// few kilobytes of JSON would not write would be the wrong trade.
  private func writeMetadataSidecar(for destination: URL, clips: [Clip], durationSec: Double) {
    guard let first = clips.first, let last = clips.last else { return }

    // The merged window's own time range. Samples outside it belong to footage
    // that was evicted from the ring and must not appear — a sidecar is a
    // description of *this* file, not of the drive around it.
    let startedAtMs = first.startedAt.timeIntervalSince1970 * 1000
    let endedAtMs = last.startedAt.timeIntervalSince1970 * 1000 + last.durationSec * 1000

    let document = SidecarDocument(
      clipId: destination.deletingPathExtension().lastPathComponent,
      startedAtMs: startedAtMs,
      durationSec: durationSec,
      samples: LocationTracker.shared
        .samplesBetween(fromMs: startedAtMs, toMs: endedAtMs)
        .map(SidecarSample.init)
    )

    do {
      let encoder = JSONEncoder()
      // Deterministic output, so a diff between an Android and an iOS sidecar
      // of the same drive is a difference in the data and not in the encoder.
      encoder.outputFormatting = [.sortedKeys]
      try encoder.encode(document).write(to: storage.metadataURL(for: destination))
    } catch {
      NSLog(
        "LoopCam/Merge: could not write the metadata sidecar for "
          + "\(destination.lastPathComponent) — \(error.localizedDescription)"
      )
    }
  }
}

/// The sidecar's schema. Field names are the wire format and are matched
/// exactly by the Kotlin writer.
private struct SidecarDocument: Encodable {
  /// Bumped when a field changes meaning, so a reader handed a file from a
  /// future build can tell rather than guess.
  let version = 1
  let clipId: String
  let startedAtMs: Double
  let durationSec: Double
  /// Always SI in the file, whatever the watermark displays. A sidecar is data;
  /// the display unit is a preference, and baking a preference into stored
  /// evidence means a file that can be misread later.
  let speedUnit = "mps"
  let samples: [SidecarSample]
}

private struct SidecarSample: Encodable {
  let t: Double
  let lat: Double
  let lon: Double
  let speed: Double?
  let acc: Double
  let derived: Bool

  init(_ sample: SpeedSample) {
    t = sample.timestampMs
    lat = sample.latitude
    lon = sample.longitude
    speed = sample.speedMps
    acc = sample.accuracyM
    derived = sample.derived
  }

  private enum CodingKeys: String, CodingKey {
    case t, lat, lon, speed, acc, derived
  }

  /// Hand-written because the synthesized `encode(to:)` uses `encodeIfPresent`
  /// for an optional and would *omit* `speed` when it is nil. The spec is
  /// explicit that it must be `null`: a gap in the array and a known-unknown
  /// sample are different facts about the drive.
  func encode(to encoder: Encoder) throws {
    var container = encoder.container(keyedBy: CodingKeys.self)
    try container.encode(t, forKey: .t)
    try container.encode(lat, forKey: .lat)
    try container.encode(lon, forKey: .lon)
    try container.encode(speed, forKey: .speed)
    try container.encode(acc, forKey: .acc)
    // Absent rather than false on a measured reading, matching Android: the key
    // exists to flag the exception, not to annotate every ordinary sample.
    if derived { try container.encode(true, forKey: .derived) }
  }
}
