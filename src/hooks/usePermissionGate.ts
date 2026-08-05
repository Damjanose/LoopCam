import { useCallback, useEffect, useState } from 'react';
import { AppState, Linking } from 'react-native';

import { LoopcamRecorder } from '../../modules/loopcam-recorder';

/**
 * Whether the app is usable at all, and the one action that can change it.
 *
 * Camera and microphone are not a feature of this app, they are the app — every
 * screen behind them is dead without them — so the answer gates the entire
 * tree rather than an individual button.
 */
export function usePermissionGate() {
  // Synchronous on both platforms, so a returning driver who has already
  // granted goes straight to the viewfinder. Seeding from `false` and resolving
  // in an effect would flash the welcome screen on every cold start.
  const [granted, setGranted] = useState(() => LoopcamRecorder.hasPermissions());
  const [denied, setDenied] = useState(false);
  const [busy, setBusy] = useState(false);

  const request = useCallback(async () => {
    setBusy(true);
    try {
      // Resolves true only if camera *and* mic were granted; location and
      // notifications ride along in the same sequence and a refusal of either
      // is not a refusal of the app.
      const ok = await LoopcamRecorder.requestPermissions();
      setGranted(ok);
      setDenied(!ok);
    } finally {
      setBusy(false);
    }
  }, []);

  const openSettings = useCallback(() => {
    void Linking.openSettings();
  }, []);

  /**
   * Granting in the OS settings page happens outside this process, and nothing
   * tells the app about it. Without this, someone who followed our own "Open
   * Settings" button, granted, and came back would be looking at a screen still
   * insisting they had refused — with no way out but killing the app.
   *
   * Only ever unlocks. A permission revoked while the app was backgrounded
   * restarts the process anyway, so there is no state here worth taking away.
   */
  useEffect(() => {
    if (granted) return;
    const subscription = AppState.addEventListener('change', (state) => {
      if (state !== 'active') return;
      if (LoopcamRecorder.hasPermissions()) setGranted(true);
    });
    return () => subscription.remove();
  }, [granted]);

  return { granted, denied, busy, request, openSettings };
}
