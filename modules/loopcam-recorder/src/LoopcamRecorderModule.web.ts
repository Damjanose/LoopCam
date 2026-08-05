import { registerWebModule, NativeModule } from 'expo';

import { FALLBACK_CAPABILITIES } from './capabilities';
import { DEFAULT_CONFIG, maxClipsFor } from './config';
import type {
  BufferStatus,
  CameraCapabilities,
  LoopcamRecorderEvents,
  RecorderConfig,
  SavedClip,
  StorageStatus,
} from './LoopcamRecorder.types';

const unsupported = (): never => {
  throw new Error('LoopCam recording is only available on Android and iOS.');
};

/**
 * Web exists so `expo start --web` can render the settings/gallery shell during
 * development. There is no rolling buffer here — recording calls throw.
 */
class LoopcamRecorderModule extends NativeModule<LoopcamRecorderEvents> {
  private config: RecorderConfig = DEFAULT_CONFIG;

  async configure(config: RecorderConfig): Promise<void> {
    this.config = config;
  }

  getConfig(): RecorderConfig {
    return this.config;
  }

  // Back-only, like the stubs above: web renders the settings shell so the
  // rows can be laid out, and there is no capture session to probe.
  getCapabilities(): CameraCapabilities {
    return FALLBACK_CAPABILITIES;
  }

  async requestPermissions(): Promise<boolean> {
    return false;
  }

  hasPermissions(): boolean {
    return false;
  }

  // Not `unsupported()`: the preview is decoration, and a screen that merely
  // has nothing to show must still render on web.
  async startPreview(): Promise<void> {}

  async stopPreview(): Promise<void> {}

  async start(): Promise<BufferStatus> {
    return unsupported();
  }

  async stop(): Promise<BufferStatus> {
    return unsupported();
  }

  async save(): Promise<SavedClip> {
    return unsupported();
  }

  getStatus(): BufferStatus {
    return {
      state: 'idle',
      clipCount: 0,
      maxClips: maxClipsFor(this.config),
      bufferedSec: 0,
      bufferedBytes: 0,
      elapsedSec: 0,
    };
  }

  async listSavedClips(): Promise<SavedClip[]> {
    return [];
  }

  async deleteSavedClip(): Promise<void> {}

  async setClipProtected(): Promise<void> {}

  async getStorageStatus(): Promise<StorageStatus> {
    return {
      freeBytes: 0,
      savedBytes: 0,
      savedClipCount: 0,
      lowSpaceWarning: false,
    };
  }

  async cleanupOrphanedClips(): Promise<number> {
    return 0;
  }
}

export default registerWebModule(LoopcamRecorderModule, 'LoopcamRecorderModule');
