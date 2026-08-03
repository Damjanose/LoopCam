import { useCallback, useEffect, useMemo, useState } from 'react';

import {
  DEFAULT_CONFIG,
  LoopcamRecorder,
  maxClipsFor,
  type BufferStatus,
  type RecorderConfig,
  type SavedClip,
} from '../../modules/loopcam-recorder';

const idleStatus = (config: RecorderConfig): BufferStatus => ({
  state: 'idle',
  clipCount: 0,
  maxClips: maxClipsFor(config),
  bufferedSec: 0,
  bufferedBytes: 0,
  elapsedSec: 0,
});

/**
 * Single source of truth for the UI's view of the engine.
 *
 * The native side owns the state machine; this hook only mirrors what it emits,
 * so the buttons can never disagree with what is actually on disk.
 */
export function useRecorder() {
  const [config, setConfig] = useState<RecorderConfig>(DEFAULT_CONFIG);
  const [status, setStatus] = useState<BufferStatus>(() => idleStatus(DEFAULT_CONFIG));
  const [lastSaved, setLastSaved] = useState<SavedClip | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    const subscriptions = [
      LoopcamRecorder.addListener('onStateChange', setStatus),
      LoopcamRecorder.addListener('onClipFinished', setStatus),
      LoopcamRecorder.addListener('onSaved', setLastSaved),
      LoopcamRecorder.addListener('onError', ({ message }) => setError(message)),
    ];
    return () => subscriptions.forEach((subscription) => subscription.remove());
  }, []);

  const run = useCallback(async (action: () => Promise<unknown>) => {
    setBusy(true);
    setError(null);
    try {
      await action();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }, []);

  const play = useCallback(
    () =>
      run(async () => {
        if (!(await LoopcamRecorder.requestPermissions())) {
          throw new Error('Camera and microphone access are required to record.');
        }
        await LoopcamRecorder.configure(config);
        setStatus(await LoopcamRecorder.start());
      }),
    [config, run],
  );

  const stop = useCallback(
    () => run(async () => setStatus(await LoopcamRecorder.stop())),
    [run],
  );

  /** Save never stops the loop — the UI stays in the recording state (§2.3). */
  const save = useCallback(
    () => run(async () => setLastSaved(await LoopcamRecorder.save())),
    [run],
  );

  const applyConfig = useCallback(
    (patch: Partial<RecorderConfig>) => {
      const next = { ...config, ...patch };
      setConfig(next);
      setStatus((current) => ({ ...current, maxClips: maxClipsFor(next) }));
      void LoopcamRecorder.configure(next);
    },
    [config],
  );

  const isRecording = status.state === 'recording' || status.state === 'saving';

  /**
   * Native only emits on real events — a clip boundary, a state change — which
   * is every `clipDurationSec` seconds. Rendering `status` alone therefore
   * leaves the clock reading 00:00 for the first ten seconds of a drive, which
   * makes a running recorder look broken.
   *
   * So the seconds are advanced locally between events. Native stays the source
   * of truth: every event resets this to whatever it reports, and the tick only
   * fills the silence in between.
   */
  const [tick, setTick] = useState(0);
  useEffect(() => {
    if (!isRecording) {
      setTick(0);
      return;
    }
    const started = Date.now();
    const id = setInterval(() => setTick((Date.now() - started) / 1000), 1000);
    return () => clearInterval(id);
    // Restarting on every event is the point: each one re-bases the tick.
  }, [isRecording, status]);

  const liveStatus = useMemo<BufferStatus>(() => {
    if (!isRecording) return status;
    return {
      ...status,
      elapsedSec: status.elapsedSec + tick,
      // Footage Save would keep also includes the clip being written right now.
      bufferedSec: Math.min(
        status.bufferedSec + tick,
        config.bufferDurationSec,
      ),
    };
  }, [config.bufferDurationSec, isRecording, status, tick]);
  // Time, not clip count: the meter answers "how much footage would Save keep",
  // and counting clips makes it jump a whole segment at a time.
  const bufferFill = useMemo(
    () =>
      config.bufferDurationSec === 0
        ? 0
        : Math.min(1, liveStatus.bufferedSec / config.bufferDurationSec),
    [config.bufferDurationSec, liveStatus.bufferedSec],
  );

  return {
    config,
    applyConfig,
    status: liveStatus,
    isRecording,
    bufferFill,
    lastSaved,
    error,
    busy,
    play,
    stop,
    save,
  };
}
