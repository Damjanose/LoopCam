import { NativeModule, requireNativeModule } from 'expo';

import type {
  BufferStatus,
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
  /** Apply clip/buffer durations and quality. Safe to call while recording. */
  configure(config: RecorderConfig): Promise<void>;
  getConfig(): RecorderConfig;

  /** Camera + mic (+ location, when tagging is on). Resolves to granted. */
  requestPermissions(): Promise<boolean>;

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
