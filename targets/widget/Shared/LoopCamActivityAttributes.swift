import ActivityKit
import Foundation

/// The data contract between the app and the Lock Screen card.
///
/// Compiled into **both** the app target and the widget extension — ActivityKit
/// matches an `Activity` to its presentation by the attributes type's name, so
/// the two copies have to stay identical. Everything in `Shared/` is linked into
/// the app target by `plugins/withLoopCamLiveActivity.js`; the widget extension
/// picks it up because `@bacons/apple-targets` links the whole target folder.
struct LoopCamActivityAttributes: ActivityAttributes {

  /// The part that changes while the session runs. Mirrors the fields of
  /// `BufferStatus` (§2.4) that are worth showing on a locked screen.
  struct ContentState: Codable, Hashable {
    /// §2.3 state machine: idle | recording | saving | stopping.
    var state: String
    /// Footage that pressing Save would commit right now.
    var bufferedSec: Double
    /// Clips held, and the ring buffer's capacity — the meter's numerator and
    /// denominator.
    var clipCount: Int
    var maxClips: Int
    /// Transient line under the title: "Saving…", "Clip saved", an error.
    var banner: String?

    var isBusy: Bool { state == "saving" || state == "stopping" }

    /// mm:ss. The figure only moves on a clip boundary (§6) — a per-second
    /// counter would mean a Live Activity update per second, which the system
    /// budgets against us and which would misreport the buffer besides: nothing
    /// is recoverable until the clip it lands in closes.
    var bufferedLabel: String {
      let total = Int(bufferedSec.rounded())
      return String(format: "%02d:%02d", total / 60, total % 60)
    }

    /// 0…1 fill for the buffer meter.
    var fill: Double {
      guard maxClips > 0 else { return 0 }
      return min(1, Double(clipCount) / Double(maxClips))
    }
  }

  /// When Play was pressed, so the card can show session length without an
  /// update per second (SwiftUI's `Text(_:style:)` ticks on its own).
  var startedAt: Date
}
