import Foundation

/// Recorder-side half of the Lock Screen Live Activity (§5.2).
///
/// The ActivityKit half cannot live in this pod. A `LiveActivityIntent` is only
/// performed in the app's own process if the intent type is compiled into the
/// *app target*, where Xcode's App Intents metadata extraction can see it — a
/// static library's sources are not scanned. The widget extension needs the same
/// types to draw its buttons. Neither of those targets can `import` this pod.
///
/// So the two halves never share a Swift type: they agree on notification names.
/// The other end is `targets/widget/Shared/LoopCamLiveActivityHost.swift`, copied
/// into the app target by `plugins/withLoopCamLiveActivity.js`. **The string
/// values below are the contract — change them in both places or not at all.**
enum LiveActivityBridge {

  // --- recorder → Lock Screen ---------------------------------------------

  /// Play was pressed; show the card. `userInfo` is a status payload.
  static let start = Notification.Name("loopcam.liveActivity.start")
  /// New buffer figures, or a new banner. `userInfo` is a status payload.
  static let update = Notification.Name("loopcam.liveActivity.update")
  /// The session is over; take the card down.
  static let end = Notification.Name("loopcam.liveActivity.end")

  // --- Lock Screen → recorder ---------------------------------------------

  /// The Save button was tapped.
  static let saveRequested = Notification.Name("loopcam.liveActivity.saveRequested")
  /// The Stop button was tapped.
  static let stopRequested = Notification.Name("loopcam.liveActivity.stopRequested")

  // --- payload -------------------------------------------------------------

  enum Key {
    static let state = "state"
    static let bufferedSec = "bufferedSec"
    static let clipCount = "clipCount"
    static let maxClips = "maxClips"
    static let banner = "banner"
  }

  /// `banner` is the transient line under the title — "Saving…", "Clip saved",
  /// an error. Passing nil clears whatever was there.
  static func post(_ name: Notification.Name, _ status: BufferStatus, banner: String? = nil) {
    var userInfo: [String: Any] = [
      Key.state: status.state.rawValue,
      Key.bufferedSec: status.bufferedSec,
      Key.clipCount: status.clipCount,
      Key.maxClips: status.maxClips,
    ]
    if let banner { userInfo[Key.banner] = banner }
    NotificationCenter.default.post(name: name, object: nil, userInfo: userInfo)
  }

  static func postEnd() {
    NotificationCenter.default.post(name: end, object: nil)
  }
}
