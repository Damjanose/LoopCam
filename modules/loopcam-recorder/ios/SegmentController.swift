import Foundation

protocol SegmentControllerDelegate: AnyObject {
  func segmentControllerDidChangeState(_ status: BufferStatus)
  func segmentControllerDidFinishClip(_ status: BufferStatus)
  func segmentControllerDidSave(_ clip: SavedClip)
  func segmentControllerDidError(_ code: RecorderErrorCode, _ message: String)
}

/// The §2.3 state machine and §2.4 ring buffer.
///
/// All mutation happens on one serial queue, so the buffer, the disk, and the
/// reported status can never disagree about what exists.
final class SegmentController {
  private let queue = DispatchQueue(label: "loopcam.segments")
  private let mergeQueue = DispatchQueue(label: "loopcam.merge", qos: .utility)

  private let storage: StorageManager
  private let recorder: SegmentRecorder
  private let merger: ClipMerger
  private weak var delegate: SegmentControllerDelegate?

  private var config = RecorderConfig()
  private var buffer = RingBuffer(capacity: 12)
  private var state: RecorderState = .idle
  private var sessionId: String?
  private var clipIndex = 0
  private var startedAt: Date?
  private var pendingBoundary: DispatchWorkItem?

  init(
    storage: StorageManager,
    recorder: SegmentRecorder,
    merger: ClipMerger,
    delegate: SegmentControllerDelegate
  ) {
    self.storage = storage
    self.recorder = recorder
    self.merger = merger
    self.delegate = delegate
  }

  func configure(_ newConfig: RecorderConfig) {
    queue.async {
      self.config = newConfig
      // Resizing live keeps a mid-drive settings change from restarting
      // capture; shrinking drops the oldest clips immediately (§2.1).
      for evicted in self.buffer.resize(to: newConfig.maxClips) {
        try? FileManager.default.removeItem(at: evicted.url)
      }
      self.emitState()
    }
  }

  var currentConfig: RecorderConfig {
    queue.sync { config }
  }

  var status: BufferStatus {
    queue.sync { statusLocked() }
  }

  /// PLAY — clear leftover temp clips, start the segment loop (§2.3).
  func start() {
    queue.async {
      guard self.state == .idle else { return }
      self.storage.cleanupOrphanedSessions()
      let id = UUID().uuidString.prefix(8).lowercased()
      self.sessionId = String(id)
      self.clipIndex = 0
      self.startedAt = Date()
      self.buffer = RingBuffer(capacity: self.config.maxClips)
      self.state = .recording
      self.emitState()

      do {
        try self.recorder.prepare(config: self.config)
        self.startNextClip()
      } catch {
        self.state = .idle
        self.emitState()
        self.fail(.cameraUnavailable, error)
      }
    }
  }

  /// STOP — cancel the in-flight clip, delete the entire buffer (§2.3).
  func stop() {
    queue.async {
      guard self.state != .idle else { return }
      self.state = .stopping
      self.emitState()

      self.pendingBoundary?.cancel()
      self.recorder.stopClip(discard: true)
      self.recorder.teardown()
      for clip in self.buffer.drain() {
        try? FileManager.default.removeItem(at: clip.url)
      }
      if let sessionId = self.sessionId {
        self.storage.deleteSession(sessionId)
      }
      self.sessionId = nil
      self.startedAt = nil
      self.state = .idle
      self.emitState()
    }
  }

  /// SAVE — freeze the window, merge it on a background queue, and start a
  /// fresh buffer immediately. Recording never pauses (§2.3); the in-progress
  /// clip is deliberately left out of the snapshot so a merge can never read a
  /// half-written file (§10) — it becomes the first clip of the new window.
  func save(trigger: SaveTrigger, completion: @escaping (Result<SavedClip, Error>) -> Void) {
    queue.async {
      guard self.state == .recording else {
        completion(.failure(RecorderError.notRecording))
        return
      }
      let snapshot = self.buffer.snapshot()
      guard !snapshot.isEmpty else {
        completion(.failure(RecorderError.emptyBuffer))
        return
      }
      // The new window starts here; the merge owns the snapshot's files and
      // deletes them once the merged file is on disk.
      self.buffer = RingBuffer(capacity: self.config.maxClips)
      self.emitState()

      let destination = self.storage.savedURL()
      let config = self.config
      self.mergeQueue.async {
        do {
          let saved = try self.merger.merge(
            clips: snapshot, destination: destination, config: config, trigger: trigger
          )
          for clip in snapshot {
            try? FileManager.default.removeItem(at: clip.url)
          }
          self.delegate?.segmentControllerDidSave(saved)
          completion(.success(saved))
        } catch {
          self.fail(.mergeFailed, error)
          completion(.failure(error))
        }
      }
    }
  }

  func release() {
    queue.async {
      self.pendingBoundary?.cancel()
      self.recorder.teardown()
    }
  }

  // MARK: - segment loop

  private func startNextClip() {
    guard let sessionId else { return }
    clipIndex += 1
    let output = storage.clipURL(sessionId: sessionId, index: clipIndex)

    recorder.startClip(
      output: output,
      onFinished: { [weak self] clip in
        self?.queue.async { self?.onClipFinished(clip) }
      },
      onError: { [weak self] error in
        self?.queue.async { self?.fail(.unknown, error) }
      }
    )

    // The clip boundary is scheduled, not polled — nothing wakes the CPU every
    // second just to check the buffer (§6).
    let boundary = DispatchWorkItem { [weak self] in
      guard let self, self.state == .recording else { return }
      self.recorder.stopClip(discard: false)
    }
    pendingBoundary = boundary
    queue.asyncAfter(deadline: .now() + config.clipDurationSec, execute: boundary)
  }

  /// §2.3 / §2.4 — append, evict the oldest if already full, delete the evicted
  /// file, roll straight into the next clip.
  private func onClipFinished(_ clip: Clip) {
    guard state == .recording else {
      try? FileManager.default.removeItem(at: clip.url)
      return
    }
    if let evicted = buffer.push(clip) {
      try? FileManager.default.removeItem(at: evicted.url)
    }
    delegate?.segmentControllerDidFinishClip(statusLocked())
    startNextClip()
  }

  private func statusLocked() -> BufferStatus {
    BufferStatus(
      state: state,
      clipCount: buffer.count,
      maxClips: buffer.capacity,
      bufferedSec: buffer.totalDurationSec,
      bufferedBytes: buffer.totalSizeBytes,
      elapsedSec: startedAt.map { Date().timeIntervalSince($0) } ?? 0
    )
  }

  private func emitState() {
    delegate?.segmentControllerDidChangeState(statusLocked())
  }

  private func fail(_ code: RecorderErrorCode, _ error: Error) {
    delegate?.segmentControllerDidError(code, error.localizedDescription)
  }
}
