package expo.modules.loopcamrecorder

/**
 * The one place the burned-in timestamp's look is defined.
 *
 * Mirrored by `ios/WatermarkStyle.swift`, file for file, so the two platforms
 * cannot drift into producing visibly different footage.
 *
 * Every measurement is a fraction of the *displayed* frame rather than a pixel
 * count: the same session can hand this code a 720p or a 4K buffer, and a fixed
 * point size would be a caption on one and a smudge on the other.
 */
internal object WatermarkStyle {
  /**
   * Fixed pattern, deliberately not a locale-aware formatter: a width that
   * changed between frames would make the plate jitter, and "05/08" meaning two
   * different days depending on where the phone was bought is not a property a
   * dashcam recording can afford in a dispute.
   */
  const val PATTERN = "dd/MM/yyyy HH:mm:ss"

  /** Text height, as a fraction of the displayed frame height. */
  const val TEXT_HEIGHT_FRACTION = 0.035f

  /** Inset from the frame edges, as a fraction of the displayed short edge. */
  const val INSET_FRACTION = 0.025f

  /** Plate padding, as a fraction of the text height. */
  const val PAD_X_FRACTION = 0.5f
  const val PAD_Y_FRACTION = 0.32f

  /** Plate corner radius, as a fraction of the plate height. */
  const val CORNER_FRACTION = 0.28f

  /**
   * The plate is what makes the text legible over overexposed asphalt; a shadow
   * alone disappears there.
   */
  const val PLATE_COLOR = 0x8C000000.toInt() // black at 55%
  const val TEXT_COLOR = 0xFFFFFFFF.toInt()
}
