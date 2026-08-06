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

  /**
   * Text height, as a fraction of the displayed frame's *short* edge — the same
   * edge [INSET_FRACTION] is measured against.
   *
   * Not the height. The stamp is a single wide line running along the frame's
   * width, and the app records portrait: measured against the long edge, the
   * plate came out around three quarters of the frame wide and ran off the
   * side of the picture.
   */
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

  /**
   * The front camera's inset picture, in `both` mode.
   *
   * Top-right, so it never collides with the timestamp in the bottom-right —
   * and drawn before it, so a later change to either cannot bury the clock.
   * Fractions again, and the same inset as the stamp, so the two corners are
   * visibly symmetrical whatever the buffer's resolution.
   */
  object Pip {
    /** Width, as a fraction of the displayed frame width. */
    const val WIDTH_FRACTION = 0.30f

    /**
     * Width over height — portrait, because the recorded frame is portrait and
     * so is the front camera's natural framing. A landscape inset in a portrait
     * frame would crop the cabin to a letterbox slot.
     *
     * Fixed rather than taken from the source, and the source is centre-cropped
     * to fill it: a black bar inside an already-small picture reads as a dead
     * feed rather than as letterboxing.
     */
    const val ASPECT = 9f / 16f

    /** Corner radius, as a fraction of the PiP height. */
    const val CORNER_FRACTION = 0.06f

    /** A hairline, so the inset separates from a bright sky behind it. */
    const val BORDER_COLOR = 0x66FFFFFF.toInt() // white at 40%
    const val BORDER_WIDTH_FRACTION = 0.006f

    /**
     * How stale a front frame may be before it is dropped rather than drawn.
     *
     * A frozen inset burned into footage that is timestamped as live is the one
     * failure here that would make a saved clip actively misleading — better an
     * empty corner, which reads as "the front camera stopped".
     */
    const val MAX_FRAME_AGE_MS = 2_000L
  }
}
