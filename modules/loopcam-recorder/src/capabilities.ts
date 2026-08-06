import type { CameraCapabilities, CameraMode, VideoQuality } from './LoopcamRecorder.types';

/**
 * Tier order, cheapest first. Everything that has to reason about "the next one
 * down" — clamping a 4K pick when the mode cannot reach it, ordering the
 * Settings list — reads it from here rather than hardcoding the sequence again.
 */
export const QUALITY_LADDER: readonly VideoQuality[] = ['360p', '480p', '720p', '1080p', '4k'];

export const CAMERA_MODE_LABELS: Record<CameraMode, string> = {
  back: 'Back',
  front: 'Front',
  both: 'Both — front in the corner',
};

/** Shown under a mode the hardware cannot run, in place of hiding it. */
export const CAMERA_MODE_UNAVAILABLE: Record<CameraMode, string> = {
  back: "This phone's back camera is unavailable.",
  front: "This phone has no front camera.",
  both: "This phone can't run both cameras at once.",
};

/** Back-only, full single-camera ladder. What web reports, and the last resort. */
export const FALLBACK_CAPABILITIES: CameraCapabilities = {
  modes: ['back'],
  qualities: {
    back: [...QUALITY_LADDER],
    front: [],
    both: [],
  },
};

/**
 * The tier to record at, given what the mode can actually reach.
 *
 * Switching to `both` with 4K selected must not record 4K — no device runs two
 * cameras at that tier — and must not fail either. It clamps *down* to the best
 * the mode allows, because a mode change should cost quality at worst, never
 * footage. Returns the requested tier untouched whenever it is reachable.
 */
export function resolveQuality(
  requested: VideoQuality,
  mode: CameraMode,
  capabilities: CameraCapabilities,
): VideoQuality {
  const allowed = capabilities.qualities[mode];
  if (allowed.length === 0 || allowed.includes(requested)) return requested;

  const ceiling = allowed.reduce((best, tier) =>
    QUALITY_LADDER.indexOf(tier) > QUALITY_LADDER.indexOf(best) ? tier : best,
  );
  return QUALITY_LADDER.indexOf(requested) > QUALITY_LADDER.indexOf(ceiling) ? ceiling : allowed[0];
}
