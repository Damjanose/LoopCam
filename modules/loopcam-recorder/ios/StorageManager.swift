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
