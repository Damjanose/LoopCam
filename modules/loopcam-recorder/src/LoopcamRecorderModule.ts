import { NativeModule, requireNativeModule } from 'expo';

import type {
  BufferStatus,
  CameraCapabilities,
  LoopcamRecorderEvents,
  RecorderConfig,
  SavedClip,
  StorageStatus,
} from './LoopcamRecorder.types';

/**
 * Native surface of the recording engine. The ring-buffer loop lives entirely
 * on the native side (§3.1) — JS only issues Play/Stop/Save and listens for
 * state.
 */
declare class LoopcamRecorderModule extends NativeModule<LoopcamRecorderEvents> {
  /**
   * Apply the config and persist it. Safe to call while recording, but
   * `cameraMode` and `quality` only take effect when the session is next built
   * — rebinding the capture session mid-recording would drop the clip in
   * flight. The UI disables both while recording rather than surprising anyone
   * with a setting that appears to have done nothing.
   */
  configure(config: RecorderConfig): Promise<void>;
  getConfig(): RecorderConfig;

  /**
   * What this device's cameras can do. Synchronous because Settings renders
   * from it on first paint, and cheap because both platforms answer from a
   * static hardware query, cached after the first call.
   */
  getCapabilities(): CameraCapabilities;

  /** Camera + mic (+ location, when tagging is on). Resolves to granted. */
  requestPermissions(): Promise<boolean>;
  /**
   * Whether recording could start right now without a prompt. Synchronous and
   * side-effect free — asking must never raise a system dialog.
   */
  hasPermissions(): boolean;

  /**
   * Light the viewfinder without recording: no service, no clips, nothing on
   * disk. Safe to call while recording, where it is a no-op — the session's own
   * preview is already feeding the view.
   */
  startPreview(): Promise<void>;
  /** Drop the standby picture. Never disturbs a live recording's preview. */
  stopPreview(): Promise<void>;

  /** PLAY — clear leftover temp clips and start the segment loop. */
  start(): Promise<BufferStatus>;
  /** STOP — cancel the in-flight clip and delete the whole temp buffer. */
  stop(): Promise<BufferStatus>;
  /**
   * SAVE — snapshot the current window, merge it on a background queue, and
   * keep recording into a fresh buffer. Resolves once the merged file is on
   * disk; `onSaved` fires with the same clip.
   */
  save(): Promise<SavedClip>;

  getStatus(): BufferStatus;

  /** Saved-clips gallery, newest first. */
  listSavedClips(): Promise<SavedClip[]>;
  deleteSavedClip(id: string): Promise<void>;
  setClipProtected(id: string, isProtected: boolean): Promise<void>;

  getStorageStatus(): Promise<StorageStatus>;
  /** Delete orphaned temp sessions left behind by a crash (§7.2). */
  cleanupOrphanedClips(): Promise<number>;
}

export default requireNativeModule<LoopcamRecorderModule>('LoopcamRecorder');
