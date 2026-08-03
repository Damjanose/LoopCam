import AVFoundation
import Foundation

/// The one seam between the mounted preview view and the live capture session.
///
/// The two have independent lifetimes on purpose (§3.1): the session belongs to
/// the recorder and the view comes and goes with the React tree, so neither can
/// hold a direct reference to the other. Whichever arrives second finds the
/// other already published here, which is why a preview mounted after Play still
/// lights up — and why unmounting it drops the picture without touching the
/// recording.
///
/// Main thread only, matching the Android `CameraPreviewBus`: layer work has to
/// happen there anyway, and confining the whole object to one thread is cheaper
/// than a lock.
final class CameraPreviewBus {
  static let shared = CameraPreviewBus()

  private var session: AVCaptureSession?
  private var sinks: [ObjectIdentifier: (AVCaptureSession?) -> Void] = [:]

  private init() {}

  /// Called by the recorder once its capture session is configured (non-nil)
  /// and on teardown (nil).
  func publish(_ newSession: AVCaptureSession?) {
    onMain {
      self.session = newSession
      for sink in self.sinks.values {
        sink(newSession)
      }
    }
  }

  /// Called by a view as it mounts. The sink fires immediately with whatever is
  /// current, so subscribing after Play is not a missed event.
  func subscribe(key: ObjectIdentifier, sink: @escaping (AVCaptureSession?) -> Void) {
    onMain {
      self.sinks[key] = sink
      sink(self.session)
    }
  }

  func unsubscribe(key: ObjectIdentifier) {
    onMain { self.sinks[key] = nil }
  }

  private func onMain(_ work: @escaping () -> Void) {
    if Thread.isMainThread {
      work()
    } else {
      DispatchQueue.main.async(execute: work)
    }
  }
}
