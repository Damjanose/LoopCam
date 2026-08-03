import AppIntents

/// The two Lock Screen buttons.
///
/// `LiveActivityIntent` is the reason these are worth the plumbing: the system
/// performs them **in the app's process**, waking it if it is suspended, rather
/// than in the widget extension. That is what lets a tap on a locked screen
/// reach the recording engine at all.
///
/// For that to hold, the types must be compiled into the app target as well as
/// the extension — hence `Shared/`. Both are `isDiscoverable = false`: they are
/// meaningless outside a running session, so they have no business showing up
/// in Shortcuts or Spotlight.
@available(iOS 17.0, *)
struct LoopCamSaveIntent: LiveActivityIntent {
  static var title: LocalizedStringResource = "Save clip"
  static var description = IntentDescription("Commit the buffered footage to a saved clip.")
  static var isDiscoverable: Bool = false

  init() {}

  func perform() async throws -> some IntentResult {
    await LoopCamLiveActivityHost.shared.requestSave()
    return .result()
  }
}

@available(iOS 17.0, *)
struct LoopCamStopIntent: LiveActivityIntent {
  static var title: LocalizedStringResource = "Stop recording"
  static var description = IntentDescription("End the session and discard the buffer.")
  static var isDiscoverable: Bool = false

  init() {}

  func perform() async throws -> some IntentResult {
    await LoopCamLiveActivityHost.shared.requestStop()
    return .result()
  }
}
