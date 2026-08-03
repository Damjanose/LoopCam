import ActivityKit
import Foundation

/// Owns the Live Activity on the app's side: requests it on Play, pushes new
/// buffer figures on every clip boundary, and ends it on Stop.
///
/// Lives in `Shared/` so the file is one source of truth, but only ever does
/// anything in the app target — `register()` is called from `AppDelegate`
/// (injected by `plugins/withLoopCamLiveActivity.js`). The copy compiled into
/// the widget extension is inert: nothing there calls `register()`.
///
/// The recorder is inside the `LoopcamRecorder` pod, which this target cannot
/// import, so both directions cross that boundary as notifications. See
/// `modules/loopcam-recorder/ios/LiveActivityBridge.swift` for the other end —
/// **the name strings below are the contract.**
@MainActor
final class LoopCamLiveActivityHost {

  static let shared = LoopCamLiveActivityHost()

  private var activity: Activity<LoopCamActivityAttributes>?
  private var observers: [NSObjectProtocol] = []

  private init() {}

  // --- notification names (keep in sync with LiveActivityBridge) -----------

  private enum Name {
    static let start = Notification.Name("loopcam.liveActivity.start")
    static let update = Notification.Name("loopcam.liveActivity.update")
    static let end = Notification.Name("loopcam.liveActivity.end")
    static let saveRequested = Notification.Name("loopcam.liveActivity.saveRequested")
    static let stopRequested = Notification.Name("loopcam.liveActivity.stopRequested")
  }

  // MARK: - wiring

  /// Called once from `application(_:didFinishLaunchingWithOptions:)`. Safe to
  /// call again; the second call is a no-op.
  func register() {
    guard observers.isEmpty else { return }
    let center = NotificationCenter.default

    observers = [
      center.addObserver(forName: Name.start, object: nil, queue: .main) { note in
        // Read the payload here, on the posting thread, so only a Sendable
        // value crosses into the actor hop below.
        let state = ContentState(userInfo: note.userInfo)
        Task { @MainActor in LoopCamLiveActivityHost.shared.startActivity(state) }
      },
      center.addObserver(forName: Name.update, object: nil, queue: .main) { note in
        let state = ContentState(userInfo: note.userInfo)
        Task { @MainActor in LoopCamLiveActivityHost.shared.updateActivity(state) }
      },
      center.addObserver(forName: Name.end, object: nil, queue: .main) { _ in
        Task { @MainActor in LoopCamLiveActivityHost.shared.endActivity() }
      },
    ]
  }

  // MARK: - called by the Lock Screen buttons

  /// Both intents run in *this* process (that is what `LiveActivityIntent`
  /// buys us), so the tap only has to be forwarded to the recorder. The card
  /// is not repainted here: the recorder answers with an `update` carrying the
  /// banner, which keeps "what the card says" and "what the engine did" from
  /// drifting apart.
  func requestSave() {
    NotificationCenter.default.post(name: Name.saveRequested, object: nil)
  }

  func requestStop() {
    NotificationCenter.default.post(name: Name.stopRequested, object: nil)
  }

  // MARK: - activity lifecycle

  private func startActivity(_ state: LoopCamActivityAttributes.ContentState) {
    // The user can switch Live Activities off per-app in Settings; that is a
    // refusal, not an error, and recording carries on without a card.
    guard ActivityAuthorizationInfo().areActivitiesEnabled else { return }

    // A Play with a card already up (a JS reload mid-session, say) should
    // adopt it rather than stack a second one.
    if let existing = activity ?? adoptedActivity() {
      activity = existing
      updateActivity(state)
      return
    }

    let attributes = LoopCamActivityAttributes(startedAt: Date())
    activity = try? Activity.request(
      attributes: attributes,
      content: ActivityContent(state: state, staleDate: nil),
      pushType: nil
    )
  }

  private func updateActivity(_ state: LoopCamActivityAttributes.ContentState) {
    guard let activity = activity ?? adoptedActivity() else { return }
    self.activity = activity
    Task {
      await activity.update(ActivityContent(state: state, staleDate: nil))
    }
  }

  private func endActivity() {
    guard let activity = activity ?? adoptedActivity() else { return }
    self.activity = nil
    Task {
      // Immediate: an ended session has nothing left to show, and a card that
      // lingers on the Lock Screen reads as "still recording".
      await activity.end(nil, dismissalPolicy: .immediate)
    }
  }

  /// After a process restart our reference is gone but the system's activity
  /// may not be — pick it back up instead of orphaning a card the user can
  /// still see and tap.
  private func adoptedActivity() -> Activity<LoopCamActivityAttributes>? {
    Activity<LoopCamActivityAttributes>.activities.first
  }
}

// MARK: - payload decoding

private typealias ContentState = LoopCamActivityAttributes.ContentState

extension LoopCamActivityAttributes.ContentState {
  /// Rebuilds the state from a `LiveActivityBridge` payload. Missing keys fall
  /// back to a plausible idle-ish card rather than dropping the update — a
  /// stale number beats a card that stops responding.
  init(userInfo: [AnyHashable: Any]?) {
    self.init(
      state: userInfo?["state"] as? String ?? "recording",
      bufferedSec: userInfo?["bufferedSec"] as? Double ?? 0,
      clipCount: userInfo?["clipCount"] as? Int ?? 0,
      maxClips: userInfo?["maxClips"] as? Int ?? 0,
      banner: userInfo?["banner"] as? String
    )
  }
}
