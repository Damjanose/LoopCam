/**
 * Shared types for the LoopCam rolling-buffer recorder.
 *
 * See docs/LoopCam_Development_Plan.pdf §2 (recording & buffer logic) and §4
 * (technology stack). Everything here is platform-agnostic: the Android
 * (CameraX) and iOS (AVFoundation) engines both speak this vocabulary.
 */

/** §2.3 state machine. */
export type RecorderState = 'idle' | 'recording' | 'saving' | 'stopping';

export type VideoQuality = '720p' | '1080p' | '4k';

/**
 * The two independent settings from §2.1. Everything downstream — how many
 * clips are kept, when the oldest is evicted, how big a saved file is — is
 * derived from these, so the same engine covers a 30 s buffer and a 10 min one
 * with no code change.
 */
export interface RecorderConfig {
  /** Length of each individual segment. Typical range 5–30 s. */
  clipDurationSec: number;
  /** Total footage retained before Save. Typical range 30 s – 15 min. */
  bufferDurationSec: number;
  quality: VideoQuality;
  /** Record an audio track alongside video. */
  audioEnabled: boolean;
  /** Write a GPS/speed/timestamp sidecar next to each saved clip (§7.1). */
  locationTaggingEnabled: boolean;
  /** Accelerometer spike triggers an automatic Save (§8). */
  impactDetectionEnabled: boolean;
  /** Auto-save the buffer and stop below this battery level (§6). 0 disables. */
  autoStopBatteryPercent: number;
}

/** Live view of the ring buffer, pushed to JS on every clip boundary. */
export interface BufferStatus {
  state: RecorderState;
  /** Clips currently held on disk. */
  clipCount: number;
  /** Capacity: ceil(bufferDurationSec / clipDurationSec). */
  maxClips: number;
  /** Footage currently recoverable by pressing Save. */
  bufferedSec: number;
  /** Bytes occupied by the temp buffer. */
  bufferedBytes: number;
  /** Seconds since Play was pressed. */
  elapsedSec: number;
}

/** A clip committed to permanent storage by Save (§7.1). */
export interface SavedClip {
  id: string;
  uri: string;
  /** Sidecar JSON with GPS/speed/timestamp, when location tagging is on. */
  metadataUri: string | null;
  createdAtMs: number;
  durationSec: number;
  sizeBytes: number;
  /** Protected clips are exempt from the storage-budget auto-delete (§7.2). */
  protected: boolean;
  /** Whether this save was triggered by impact detection rather than the user. */
  trigger: 'manual' | 'impact' | 'lowBattery';
}

export interface StorageStatus {
  freeBytes: number;
  savedBytes: number;
  savedClipCount: number;
  /** True once free space drops under the warning threshold (§7.2, ~1 GB). */
  lowSpaceWarning: boolean;
}

export type RecorderErrorCode =
  | 'permissionDenied'
  | 'cameraUnavailable'
  | 'storageFull'
  | 'mergeFailed'
  | 'serviceKilled'
  | 'unknown';

/** Events the native engine emits back to JS (§3, "Event emitter back to JS"). */
export type LoopcamRecorderEvents = {
  onStateChange: (payload: BufferStatus) => void;
  onClipFinished: (payload: BufferStatus) => void;
  onSaved: (payload: SavedClip) => void;
  /**
   * Fired after a save when the budget sweep removed clips (§7.2) or free space
   * fell under the warning threshold. `deletedClipIds` is what the gallery has
   * to drop — the files are already gone by the time this arrives.
   */
  onStorageWarning: (payload: StorageStatus & { deletedClipIds: string[] }) => void;
  onError: (payload: { code: RecorderErrorCode; message: string }) => void;
};
