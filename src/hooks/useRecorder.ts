import { useCallback, useEffect, useMemo, useRef, useState } from 'react';

import {
  LoopcamRecorder,
  maxClipsFor,
  type BufferStatus,
  type RecorderConfig,
  type SavedClip,
} from '../../modules/loopcam-recorder';

/**
 * A native status plus the wall-clock instant it arrived. The timestamp is what
 * lets the readouts be extrapolated between events without guessing how stale
 * the numbers are.
 */
type Snapshot = { status: BufferStatus; at: number };

const snapshotOf = (status: BufferStatus): Snapshot => ({ status, at: Date.now() });

/** How often the extrapolated readouts re-render. */
const TICK_MS = 250;

/**
 * How far the clock may disagree with native before it re-syncs instead of
 * riding its own anchor. Wide enough to ignore event-delivery lag, tight enough
 * that a restarted session is picked up within a clip.
 */
const ANCHOR_DRIFT_MS = 2000;

/**
 * Single source of truth for the UI's view of the engine.
 *
 * The native side owns the state machine; this hook only mirrors what it emits,
 * so the buttons can never disagree with what is actually on disk.
 */
export function useRecorder() {
  // Seeded from native, never from the defaults: the engine outlives this hook
  // (App.tsx unmounts the recorder screen to browse saved clips) and only emits
  // at clip boundaries, so starting from a hardcoded idle status would show
  // Standby, an armed Play button and a 00:00 clock over a live recording until
  // the next boundary landed.
  const [config, setConfig] = useState<RecorderConfig>(() => LoopcamRecorder.getConfig());
  const [snapshot, setSnapshot] = useState<Snapshot>(() =>
    snapshotOf(LoopcamRecorder.getStatus()),
  );
  const status = snapshot.status;
  const setStatus = useCallback((next: BufferStatus) => setSnapshot(snapshotOf(next)), []);
  const [lastSaved, setLastSaved] = useState<SavedClip | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [storageWarning, setStorageWarning] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    const subscriptions = [
      LoopcamRecorder.addListener('onStateChange', setStatus),
      LoopcamRecorder.addListener('onClipFinished', setStatus),
      LoopcamRecorder.addListener('onSaved', setLastSaved),
      LoopcamRecorder.addListener('onError', ({ message }) => setError(message)),
      // The sweep has already deleted by the time this lands (§7.2), so this is
      // a notice, not a prompt — but it has to be said, or clips disappear with
      // no explanation.
      LoopcamRecorder.addListener('onStorageWarning', ({ deletedClipIds, lowSpaceWarning }) => {
        if (deletedClipIds.length > 0) {
          const plural = deletedClipIds.length === 1 ? '' : 's';
          setStorageWarning(
            `Storage full — removed ${deletedClipIds.length} old clip${plural}. Lock a clip to keep it.`,
          );
        } else if (lowSpaceWarning) {
          setStorageWarning('Running low on storage.');
        }
      }),
    ];
    return () => subscriptions.forEach((subscription) => subscription.remove());
  }, [setStatus]);

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
      // Capacity only — the buffer's age is unchanged, so the arrival timestamp
      // must survive or the extrapolated readouts would jump back.
      setSnapshot((current) => ({
        ...current,
        status: { ...current.status, maxClips: maxClipsFor(next) },
      }));
      void LoopcamRecorder.configure(next);
    },
    [config],
  );

  const isRecording = status.state === 'recording' || status.state === 'saving';

  /**
   * Native only emits on real events — a clip boundary, a state change — which
   * is every `clipDurationSec` seconds. Rendering `status` alone therefore
   * leaves the clock reading 00:00 for the first ten seconds of a drive, which
   * makes a running recorder look broken. So the readouts are extrapolated from
   * the last snapshot, re-rendered on a ticker.
   *
   * Sub-second so the seconds digit flips within a quarter second of the truth;
   * a 1 s interval drifts and visibly skips a second every so often.
   */
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    if (!isRecording) return;
    setNow(Date.now());
    const id = setInterval(() => setNow(Date.now()), TICK_MS);
    return () => clearInterval(id);
    // Deliberately not keyed on `status`: re-basing the ticker on every event
    // is what used to make the clock lurch forward and then fall back.
  }, [isRecording]);

  /**
   * The clock runs off the instant Play was pressed, reconstructed once from a
   * snapshot, rather than off each event's `elapsedSec`. Native measures that
   * from the same wall clock, so every event agrees with the anchor to within
   * its delivery lag — but re-reading it each time would feed those tens of
   * milliseconds of jitter straight into a display that rounds to seconds, and
   * the clock would step backwards across a boundary. Anchoring makes it
   * monotonic; a disagreement past `ANCHOR_DRIFT_MS` is a real restart rather
   * than lag, and re-anchors.
   */
  const anchorRef = useRef<number | null>(null);
  const reportedAnchor = snapshot.at - status.elapsedSec * 1000;
  if (!isRecording) {
    anchorRef.current = null;
  } else if (
    anchorRef.current === null ||
    Math.abs(anchorRef.current - reportedAnchor) > ANCHOR_DRIFT_MS
  ) {
    anchorRef.current = reportedAnchor;
  }
  const anchor = anchorRef.current;

  const liveStatus = useMemo<BufferStatus>(() => {
    if (!isRecording || anchor === null) return status;
    // Age of the numbers in hand. Clamped: a snapshot can land a hair after the
    // last tick, and negative growth would read as the buffer shrinking.
    const since = Math.max(0, (now - snapshot.at) / 1000);
    return {
      ...status,
      elapsedSec: Math.max(status.elapsedSec, (now - anchor) / 1000),
      // Footage Save would keep also includes the clip being written right now.
      bufferedSec: Math.min(status.bufferedSec + since, config.bufferDurationSec),
    };
  }, [anchor, config.bufferDurationSec, isRecording, now, snapshot.at, status]);
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
    storageWarning,
    dismissStorageWarning: useCallback(() => setStorageWarning(null), []),
    busy,
    play,
    stop,
    save,
  };
}
