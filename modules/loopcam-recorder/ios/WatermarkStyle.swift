import CoreGraphics

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
}
