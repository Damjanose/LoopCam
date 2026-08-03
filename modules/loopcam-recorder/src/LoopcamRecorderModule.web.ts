import { registerWebModule, NativeModule } from 'expo';

import { DEFAULT_CONFIG, maxClipsFor } from './config';
import type {
  BufferStatus,
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

  async requestPermissions(): Promise<boolean> {
    return false;
  }

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
