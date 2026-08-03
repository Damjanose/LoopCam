import AVFoundation
import ExpoModulesCore
import UIKit

/// JS-facing surface of the recording engine (§3).
///
/// Nothing here does timing-sensitive work: every call hands off to
/// `SegmentController`, which owns the loop on its own queue. The bridge is a
/// control channel, not part of the recording path (§3.1).
public class LoopcamRecorderModule: Module, SegmentControllerDelegate {
  private let storage = StorageManager()
  private lazy var recorder = AVSegmentRecorder()
  private lazy var controller = SegmentController(
    storage: storage,
    recorder: recorder,
    merger: ClipMerger(storage: storage),
    delegate: self
  )
  private var protectedIds = Set<String>()
  /// Live Activity button taps. Held so `OnDestroy` can drop them — a reload
  /// otherwise leaves a dead module answering the Lock Screen.
  private var liveActivityObservers: [NSObjectProtocol] = []

  public func definition() -> ModuleDefinition {
    Name("LoopcamRecorder")

    Events("onStateChange", "onClipFinished", "onSaved", "onStorageWarning", "onError")

    OnCreate {
      // §7.2 — a temp session that survived a crash is orphaned by definition.
      self.storage.cleanupOrphanedSessions()
      // §6 — needed for the low-battery auto-save-and-stop threshold.
      UIDevice.current.isBatteryMonitoringEnabled = true
      self.observeLiveActivity()
    }

    OnDestroy {
      self.liveActivityObservers.forEach(NotificationCenter.default.removeObserver)
      self.liveActivityObservers = []
      // The session dies with the module, so the card has to go with it rather
      // than sit on the Lock Screen offering buttons that no longer land.
      LiveActivityBridge.postEnd()
      self.controller.release()
    }

    AsyncFunction("configure") { (config: RecorderConfig) in
      self.controller.configure(config)
    }

    Function("getConfig") {
      self.controller.currentConfig.asDictionary()
    }

    AsyncFunction("requestPermissions") { (promise: Promise) in
      // TODO(phase-2): also request CoreLocation "when in use" if GPS tagging
      // is on, and surface the §5.2 "keep the screen on while driving" copy.
      AVCaptureDevice.requestAccess(for: .video) { videoGranted in
        AVCaptureDevice.requestAccess(for: .audio) { audioGranted in
          promise.resolve(videoGranted && audioGranted)
        }
      }
    }

    /// PLAY.
    AsyncFunction("start") { () -> [String: Any] in
      // §5.2 — iOS has no foreground-service equivalent. Keeping the screen
      // awake is the honest mechanism behind "driving mode"; an active audio
      // session (configured in AVSegmentRecorder) is what buys background time.
      DispatchQueue.main.async { UIApplication.shared.isIdleTimerDisabled = true }
      self.controller.start()
      // The Lock Screen card is the only control surface once the phone locks.
      LiveActivityBridge.post(LiveActivityBridge.start, self.controller.status)
      return self.controller.status.asDictionary()
    }

    /// STOP.
    AsyncFunction("stop") { () -> [String: Any] in
      self.controller.stop()
      DispatchQueue.main.async { UIApplication.shared.isIdleTimerDisabled = false }
      LiveActivityBridge.postEnd()
      return self.controller.status.asDictionary()
    }

    /// SAVE — resolves once the merged file is on disk; recording never pauses.
    AsyncFunction("save") { (promise: Promise) in
      self.controller.save(trigger: .manual) { result in
        switch result {
        case .success(let clip):
          promise.resolve(clip.asDictionary())
        case .failure(let error):
          promise.reject("ERR_SAVE_FAILED", error.localizedDescription)
        }
      }
    }

    Function("getStatus") {
      self.controller.status.asDictionary()
    }

    AsyncFunction("listSavedClips") { () -> [[String: Any?]] in
      self.savedClips().map { $0.asDictionary() }
    }

    AsyncFunction("deleteSavedClip") { (id: String) in
      guard let clip = self.savedClips().first(where: { $0.id == id }) else { return }
      try? FileManager.default.removeItem(at: clip.url)
      if let metadataURL = clip.metadataURL {
        try? FileManager.default.removeItem(at: metadataURL)
      }
    }

    AsyncFunction("setClipProtected") { (id: String, isProtected: Bool) in
      // TODO(phase-4): persist alongside the sidecar so it survives a restart.
      if isProtected {
        self.protectedIds.insert(id)
      } else {
        self.protectedIds.remove(id)
      }
    }

    AsyncFunction("getStorageStatus") { () -> [String: Any] in
      let clips = self.savedClips()
      return self.storage.storageStatus(
        savedClipCount: clips.count,
        savedBytes: clips.reduce(0) { $0 + $1.sizeBytes }
      ).asDictionary()
    }

    AsyncFunction("cleanupOrphanedClips") { () -> Int in
      self.storage.cleanupOrphanedSessions()
    }

    View(LoopcamRecorderView.self) {
      Prop("lens") { (view: LoopcamRecorderView, lens: String) in
        view.setLens(lens)
      }
      Prop("resizeMode") { (view: LoopcamRecorderView, mode: String) in
        view.setResizeMode(mode)
      }
    }
  }

  // MARK: - SegmentControllerDelegate

  func segmentControllerDidChangeState(_ status: BufferStatus) {
    sendEvent("onStateChange", status.asDictionary())
    if status.state != .idle {
      LiveActivityBridge.post(LiveActivityBridge.update, status)
    }
  }

  /// The card is repainted here rather than on a timer: the buffer figure only
  /// changes when a clip closes (§2.4), and a Live Activity update per second
  /// would burn the system's update budget for a number that had not moved.
  func segmentControllerDidFinishClip(_ status: BufferStatus) {
    sendEvent("onClipFinished", status.asDictionary())
    LiveActivityBridge.post(LiveActivityBridge.update, status)
  }

  func segmentControllerDidSave(_ clip: SavedClip) {
    sendEvent("onSaved", clip.asDictionary() as [String: Any])
    LiveActivityBridge.post(LiveActivityBridge.update, controller.status, banner: "Clip saved")
  }

  func segmentControllerDidError(_ code: RecorderErrorCode, _ message: String) {
    sendEvent("onError", ["code": code.rawValue, "message": message])
    LiveActivityBridge.post(LiveActivityBridge.update, controller.status, banner: message)
  }

  // MARK: - Lock Screen controls

  /// Both handlers mirror the Android notification actions: they run the *same*
  /// engine calls as the JS buttons, so a Save from the Lock Screen is
  /// indistinguishable downstream — JS still hears `onSaved` (§3.1).
  private func observeLiveActivity() {
    let center = NotificationCenter.default
    liveActivityObservers = [
      center.addObserver(forName: LiveActivityBridge.saveRequested, object: nil, queue: nil) { [weak self] _ in
        self?.saveFromLiveActivity()
      },
      center.addObserver(forName: LiveActivityBridge.stopRequested, object: nil, queue: nil) { [weak self] _ in
        self?.stopFromLiveActivity()
      },
    ]
  }

  /// Save cuts the in-flight clip and waits for the merge (§2.3), which can take
  /// until the next clip boundary. With the phone locked the card is the only
  /// feedback there is, so it has to say something immediately — otherwise a
  /// working Save is indistinguishable from a dead button.
  private func saveFromLiveActivity() {
    LiveActivityBridge.post(LiveActivityBridge.update, controller.status, banner: "Saving…")
    controller.save(trigger: .manual) { [weak self] result in
      guard let self else { return }
      switch result {
      case .success:
        // `segmentControllerDidSave` already repainted the card with the
        // "Clip saved" banner, and JS was told. Nothing left to do here.
        break
      case .failure(let error):
        LiveActivityBridge.post(
          LiveActivityBridge.update,
          self.controller.status,
          banner: error.localizedDescription
        )
      }
    }
  }

  private func stopFromLiveActivity() {
    LiveActivityBridge.post(LiveActivityBridge.update, controller.status, banner: "Stopping…")
    controller.stop()
    DispatchQueue.main.async { UIApplication.shared.isIdleTimerDisabled = false }
    LiveActivityBridge.postEnd()
  }

  // MARK: - saved clips

  /// §7.1 — the saved directory *is* the index: one .mp4 plus an optional .json
  /// sidecar per incident. No database to fall out of sync with disk.
  private func savedClips() -> [SavedClip] {
    let fm = FileManager.default
    guard let urls = try? fm.contentsOfDirectory(
      at: storage.savedRoot,
      includingPropertiesForKeys: [.contentModificationDateKey]
    ) else { return [] }

    return urls
      .filter { $0.pathExtension == "mp4" }
      .sorted { lhs, rhs in modifiedAt(lhs) > modifiedAt(rhs) }
      .map { url in
        let sidecar = storage.metadataURL(for: url)
        let id = url.deletingPathExtension().lastPathComponent
        return SavedClip(
          id: id,
          url: url,
          metadataURL: fm.fileExists(atPath: sidecar.path) ? sidecar : nil,
          createdAtMs: modifiedAt(url).timeIntervalSince1970 * 1000,
          // TODO(phase-3): read the real duration from AVAsset.
          durationSec: 0,
          sizeBytes: storage.fileSize(at: url),
          isProtected: protectedIds.contains(id),
          trigger: .manual
        )
      }
  }

  private func modifiedAt(_ url: URL) -> Date {
    (try? url.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate)
      ?? Date.distantPast
  }
}
