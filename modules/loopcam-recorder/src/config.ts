import type { RecorderConfig, VideoQuality } from './LoopcamRecorder.types';

/**
 * §2.2 core formula. Every downstream number — clips retained, eviction point,
 * saved-file length — derives from this, which is what lets the same engine run
 * a 30 s buffer or a 10 min one unchanged.
 */
export function maxClipsFor(config: Pick<RecorderConfig, 'clipDurationSec' | 'bufferDurationSec'>): number {
  return Math.max(1, Math.ceil(config.bufferDurationSec / config.clipDurationSec));
}

/** Approximate encoder bitrates used for storage projections (§7.2). */
export const BITRATE_MBPS: Record<VideoQuality, number> = {
  '360p': 0.8,
  '480p': 1.2,
  '720p': 2.5,
  '1080p': 5,
  '4k': 20,
};

/**
 * Rough on-disk size of a full buffer at the given config, in bytes.
 *
 * `cameraMode` does not enter into it: `both` composites the front camera into
 * the back camera's frame, so it is still one stream at one tier. Recording the
 * two cameras to two files would have doubled this — which is a large part of
 * why it does not.
 */
export function estimatedBufferBytes(config: RecorderConfig): number {
  const seconds = maxClipsFor(config) * config.clipDurationSec;
  return (BITRATE_MBPS[config.quality] * 1_000_000 * seconds) / 8;
}

export const DEFAULT_CONFIG: RecorderConfig = {
  clipDurationSec: 10,
  bufferDurationSec: 120,
  quality: '1080p',
  // The one mode every device supports, and the one a dashcam is for.
  cameraMode: 'back',
  audioEnabled: true,
  locationTaggingEnabled: true,
  speedUnit: 'kmh',
  impactDetectionEnabled: true,
  autoStopBatteryPercent: 15,
};

/**
 * The loop lengths offered in Settings.
 *
 * Coarse at the top end on purpose: the difference between 30 s and a minute of
 * retained footage decides whether an incident survives the fumble for the Save
 * button, while the difference between 5 and 7 minutes decides nothing. Ends at
 * ten minutes because that is already 1.5 GB at 4K.
 */
export const BUFFER_DURATION_OPTIONS: readonly { sec: number; label: string }[] = [
  { sec: 30, label: '30 seconds' },
  { sec: 60, label: '1 minute' },
  { sec: 120, label: '2 minutes' },
  { sec: 300, label: '5 minutes' },
  { sec: 600, label: '10 minutes' },
];

/**
 * The offered length closest to `sec`.
 *
 * Settings marks the selected row by comparing against this rather than against
 * the stored value directly. Every config written to date holds one of the five,
 * but an exact match would render a group with *nothing* selected the moment
 * that stops being true — an older build's value, a preset, a future option
 * removed from the list — and a radio group with no dot reads as broken.
 */
export function nearestBufferDuration(sec: number): number {
  return BUFFER_DURATION_OPTIONS.reduce((best, option) =>
    Math.abs(option.sec - sec) < Math.abs(best.sec - sec) ? option : best
  ).sec;
}

/** §2.2 example presets. */
export const PRESETS = {
  quickClip: { clipDurationSec: 5, bufferDurationSec: 30 },
  default: { clipDurationSec: 10, bufferDurationSec: 120 },
  longTrip: { clipDurationSec: 15, bufferDurationSec: 600 },
} as const satisfies Record<string, Pick<RecorderConfig, 'clipDurationSec' | 'bufferDurationSec'>>;

export type PresetName = keyof typeof PRESETS;

/** Warn the user below this much free space (§7.2). */
export const LOW_SPACE_WARNING_BYTES = 1_000_000_000;

/** Auto-delete the oldest unprotected saved clip past this budget (§7.2). */
export const SAVED_STORAGE_BUDGET_BYTES = 5_000_000_000;
export const SAVED_CLIP_COUNT_LIMIT = 50;
