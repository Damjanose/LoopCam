import CoreGraphics
import Foundation

/// The one place the burned-in timestamp's look is defined.
///
/// Mirrors `android/…/WatermarkStyle.kt`, constant for constant, so the two
/// platforms cannot drift into producing visibly different footage.
///
/// Every measurement is a fraction of the *displayed* frame rather than a point
/// size: the same code runs against 720p and 4K buffers, and a fixed size would
/// be a caption on one and a smudge on the other.
enum WatermarkStyle {
  /// Fixed pattern, deliberately not a locale-aware formatter: a width that
  /// changed between frames would make the plate jitter, and a date whose
  /// meaning depends on where the phone was bought is not a property a dashcam
  /// recording can afford in a dispute.
  static let pattern = "dd/MM/yyyy HH:mm:ss"

  /// Text height, as a fraction of the displayed frame height.
  static let textHeightFraction: CGFloat = 0.035

  /// Inset from the frame edges, as a fraction of the displayed short edge.
  static let insetFraction: CGFloat = 0.025

  /// Plate padding, as a fraction of the text height.
  static let padXFraction: CGFloat = 0.5
  static let padYFraction: CGFloat = 0.32

  /// Plate corner radius, as a fraction of the plate height.
  static let cornerFraction: CGFloat = 0.28

  /// The plate is what makes the text legible over overexposed asphalt; a
  /// shadow alone disappears there.
  static let plateAlpha: CGFloat = 0.55

  /// The front camera's inset picture, in `both` mode.
  ///
  /// Top-right, so it never collides with the timestamp in the bottom-right —
  /// and drawn before it, so a later change to either cannot bury the clock.
  /// Same inset as the stamp, so the two corners read as symmetrical whatever
  /// the buffer's resolution.
  enum Pip {
    /// Width, as a fraction of the displayed frame width.
    static let widthFraction: CGFloat = 0.30

    /// Width over height — portrait, because the recorded frame is portrait and
    /// so is the front camera's natural framing. A landscape inset in a portrait
    /// frame would crop the cabin to a letterbox slot.
    ///
    /// Fixed rather than taken from the source, and the source is centre-cropped
    /// to fill it: a black bar inside an already-small picture reads as a dead
    /// feed rather than as letterboxing.
    static let aspect: CGFloat = 9.0 / 16.0

    /// Corner radius, as a fraction of the PiP height.
    static let cornerFraction: CGFloat = 0.06

    /// A hairline, so the inset separates from a bright sky behind it.
    static let borderAlpha: CGFloat = 0.40
    static let borderWidthFraction: CGFloat = 0.006

    /// How stale a front frame may be before it is dropped rather than drawn.
    ///
    /// A frozen inset burned into footage that is timestamped as live is the
    /// one failure here that would make a saved clip actively misleading —
    /// better an empty corner, which reads as "the front camera stopped".
    static let maxFrameAge: TimeInterval = 2.0

    /// The front camera's frame rate under multi-cam. The two cameras share a
    /// hardware budget, and this is the cheapest place to buy some back: the
    /// inset is a thirtieth of the frame and its staleness is not observable.
    static let frameRate: Int32 = 15

    /// The front camera's capture height under multi-cam. Roughly what the
    /// inset occupies on a 1080p frame — asking the sensor for more would spend
    /// the shared hardware budget on pixels that are scaled away.
    static let frontTargetHeight = 480
  }
}
