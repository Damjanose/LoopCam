import Foundation

/// A finished, fully flushed segment sitting in the temp buffer.
///
/// Only closed clips ever enter the buffer — the in-progress recording stays
/// out of it, which is what keeps a Save from merging a half-written file (§10).
struct Clip {
  let url: URL
  let durationSec: Double
  let sizeBytes: Int64
  let startedAt: Date
}

/// §2.4 — the buffer is a fixed-capacity ring, not a list we re-scan. Pushing
/// into a full ring evicts the oldest clip, so delete-oldest falls out of the
/// data structure instead of manual cleanup that can drift out of sync with disk.
///
/// Not thread-safe on its own; `SegmentController` owns it from a serial queue.
final class RingBuffer {
  private(set) var capacity: Int
  private var clips: [Clip] = []

  init(capacity: Int) {
    self.capacity = max(1, capacity)
  }

  var count: Int { clips.count }
  var isFull: Bool { clips.count >= capacity }
  var totalDurationSec: Double { clips.reduce(0) { $0 + $1.durationSec } }
  var totalSizeBytes: Int64 { clips.reduce(0) { $0 + $1.sizeBytes } }

  /// Append a clip, returning the evicted one (which the caller must delete).
  @discardableResult
  func push(_ clip: Clip) -> Clip? {
    var evicted: Clip?
    if isFull, !clips.isEmpty {
      evicted = clips.removeFirst()
    }
    clips.append(clip)
    return evicted
  }

  /// Freeze the current window for a Save.
  func snapshot() -> [Clip] { clips }

  func drain() -> [Clip] {
    let all = clips
    clips.removeAll()
    return all
  }

  /// Re-capacity live, e.g. when buffer length changes mid-drive. Shrinking
  /// evicts the oldest clips immediately.
  func resize(to newCapacity: Int) -> [Clip] {
    capacity = max(1, newCapacity)
    var evicted: [Clip] = []
    while clips.count > capacity {
      evicted.append(clips.removeFirst())
    }
    return evicted
  }
}
