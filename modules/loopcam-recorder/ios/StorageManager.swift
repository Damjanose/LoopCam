import AVFoundation
import Foundation

/// §7.1 file naming & layout, inside the app sandbox.
///
///   Documents/LoopCam/tmp/session_<sessionId>/clip_0001.mp4   (rolling buffer)
///   Documents/LoopCam/saved/Incident_<yyyy-MM-dd_HH-mm-ss>.mp4
///   Documents/LoopCam/saved/Incident_<yyyy-MM-dd_HH-mm-ss>.json
final class StorageManager {
  private let fm = FileManager.default

  private var root: URL {
    let documents = fm.urls(for: .documentDirectory, in: .userDomainMask)[0]
    return documents.appendingPathComponent("LoopCam", isDirectory: true)
  }

  var tmpRoot: URL { ensure(root.appendingPathComponent("tmp", isDirectory: true)) }
  var savedRoot: URL { ensure(root.appendingPathComponent("saved", isDirectory: true)) }

  func sessionDir(_ sessionId: String) -> URL {
    ensure(tmpRoot.appendingPathComponent("session_\(sessionId)", isDirectory: true))
  }

  func clipURL(sessionId: String, index: Int) -> URL {
    sessionDir(sessionId).appendingPathComponent(String(format: "clip_%04d.mp4", index))
  }

  func savedURL(at date: Date = Date()) -> URL {
    savedRoot.appendingPathComponent("Incident_\(Self.timestampFormatter.string(from: date)).mp4")
  }

  func metadataURL(for video: URL) -> URL {
    video.deletingPathExtension().appendingPathExtension("json")
  }

  /// §7.2 — protection is a marker file rather than app state: the saved
  /// directory is the index, and a flag that lives only in memory silently
  /// un-protects every clip on process death, which is exactly when the budget
  /// sweep is most likely to run.
  func protectionMarkerURL(for video: URL) -> URL {
    video.deletingPathExtension().appendingPathExtension("protected")
  }

  func isProtected(_ video: URL) -> Bool {
    fm.fileExists(atPath: protectionMarkerURL(for: video).path)
  }

  func setProtected(_ video: URL, _ isProtected: Bool) {
    let marker = protectionMarkerURL(for: video)
    if isProtected {
      if !fm.fileExists(atPath: marker.path) {
        fm.createFile(atPath: marker.path, contents: nil)
      }
    } else {
      try? fm.removeItem(at: marker)
    }
  }

  /// STOP wipes the whole session directory (§7.1).
  func deleteSession(_ sessionId: String) {
    try? fm.removeItem(at: sessionDir(sessionId))
  }

  /// §7.2 — the temp buffer must never survive a crash. Any session directory
  /// left behind is orphaned, since only one session is live at a time.
  @discardableResult
  func cleanupOrphanedSessions(activeSessionId: String? = nil) -> Int {
    guard let contents = try? fm.contentsOfDirectory(
      at: tmpRoot, includingPropertiesForKeys: nil
    ) else { return 0 }

    var removed = 0
    for dir in contents where dir.lastPathComponent.hasPrefix("session_") {
      if let active = activeSessionId, dir.lastPathComponent == "session_\(active)" { continue }
      if (try? fm.removeItem(at: dir)) != nil { removed += 1 }
    }
    return removed
  }

  /// §7.2 — the budget sweep. Oldest-first among unprotected clips until both
  /// the byte budget and the count limit are met. Returns the ids removed, so
  /// the caller can tell JS which rows just vanished from under it.
  ///
  /// Protected clips are counted against the budget but never deleted: a user
  /// who locks 5 GB of footage has told us to stop reclaiming space, and
  /// quietly overriding that would be worse than running out.
  func enforceBudget() -> [String] {
    guard let urls = try? fm.contentsOfDirectory(
      at: savedRoot,
      includingPropertiesForKeys: [.contentModificationDateKey, .fileSizeKey]
    ) else { return [] }

    let clips = urls.filter { $0.pathExtension == "mp4" }
    var totalBytes = clips.reduce(Int64(0)) { $0 + fileSize(at: $1) }
    var count = clips.count
    var removed: [String] = []

    let candidates = clips
      .filter { !isProtected($0) }
      .sorted { modifiedAt($0) < modifiedAt($1) }

    for url in candidates {
      if totalBytes <= Self.savedStorageBudgetBytes, count <= Self.savedClipCountLimit { break }
      let size = fileSize(at: url)
      guard (try? fm.removeItem(at: url)) != nil else { continue }
      try? fm.removeItem(at: metadataURL(for: url))
      try? fm.removeItem(at: protectionMarkerURL(for: url))
      totalBytes -= size
      count -= 1
      removed.append(url.deletingPathExtension().lastPathComponent)
    }
    return removed
  }

  /// Count and bytes of the saved directory, without opening a single file.
  ///
  /// The gallery's clip list needs a duration per clip, which costs an AVAsset
  /// load each; the storage figures do not. Reusing the clip list here would
  /// put ~50 container reads on the save path, every save, while the buffer is
  /// still recording.
  func savedFootprint() -> (count: Int, bytes: Int64) {
    guard let urls = try? fm.contentsOfDirectory(
      at: savedRoot, includingPropertiesForKeys: [.fileSizeKey]
    ) else { return (0, 0) }
    let clips = urls.filter { $0.pathExtension == "mp4" }
    return (clips.count, clips.reduce(Int64(0)) { $0 + fileSize(at: $1) })
  }

  func storageStatus(savedClipCount: Int, savedBytes: Int64) -> StorageStatus {
    let values = try? root.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey])
    let free = Int64(values?.volumeAvailableCapacityForImportantUsage ?? 0)
    return StorageStatus(
      freeBytes: free,
      savedBytes: savedBytes,
      savedClipCount: savedClipCount,
      lowSpaceWarning: free < Self.lowSpaceWarningBytes
    )
  }

  func fileSize(at url: URL) -> Int64 {
    let attrs = try? fm.attributesOfItem(atPath: url.path)
    return (attrs?[.size] as? NSNumber)?.int64Value ?? 0
  }

  /// The container is the only honest source for how long a saved clip runs:
  /// the merge concatenates a variable number of segments and the last one is
  /// cut short by the Save itself, so no arithmetic over the config predicts
  /// it. A file that cannot be read reports 0 rather than guessing.
  ///
  /// AVFoundation's property loading is async-only from iOS 16 on, while the
  /// callers here are synchronous. Blocking is safe: both run off the main
  /// thread, on the Expo module's own async queue.
  func durationSec(at url: URL) -> Double {
    let asset = AVURLAsset(url: url)
    let semaphore = DispatchSemaphore(value: 0)
    var seconds = 0.0
    Task {
      if let duration = try? await asset.load(.duration) {
        let value = CMTimeGetSeconds(duration)
        if value.isFinite, value > 0 { seconds = value }
      }
      semaphore.signal()
    }
    semaphore.wait()
    return seconds
  }

  func modifiedAt(_ url: URL) -> Date {
    (try? url.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate)
      ?? Date.distantPast
  }

  @discardableResult
  private func ensure(_ url: URL) -> URL {
    if !fm.fileExists(atPath: url.path) {
      try? fm.createDirectory(at: url, withIntermediateDirectories: true)
    }
    return url
  }

  /// Warn the user below ~1 GB free (§7.2).
  static let lowSpaceWarningBytes: Int64 = 1_000_000_000
  /// Auto-delete the oldest unprotected saved clip past this budget (§7.2).
  static let savedStorageBudgetBytes: Int64 = 5_000_000_000
  static let savedClipCountLimit = 50

  private static let timestampFormatter: DateFormatter = {
    let formatter = DateFormatter()
    formatter.dateFormat = "yyyy-MM-dd_HH-mm-ss"
    formatter.locale = Locale(identifier: "en_US_POSIX")
    return formatter
  }()
}
