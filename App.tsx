import { useState } from 'react';
import { NavigationBar } from 'expo-navigation-bar';

import { usePermissionGate } from './src/hooks/usePermissionGate';
import RecorderScreen from './src/screens/RecorderScreen';
import SavedClipsScreen from './src/screens/SavedClipsScreen';
import SettingsScreen from './src/screens/SettingsScreen';
import WelcomeScreen from './src/screens/WelcomeScreen';

/**
 * A flat set of screens off the recorder does not earn a navigator. Swapping on state keeps the entry point
 * dependency-free, and — because the rolling buffer is owned by the foreground
 * service rather than the React tree — unmounting the recorder to browse saved
 * clips does not interrupt a recording.
 *
 * TODO(phase-3): promote this to a real navigator once settings lands.
 */
export default function App() {
  const [screen, setScreen] = useState<'recorder' | 'saved' | 'settings'>('recorder');
  const { granted, denied, busy, request, openSettings } = usePermissionGate();

  return (
    <>
      {/* Android only: immersive-sticky, so a swipe reveals the bar transiently
          and it re-hides itself. No-op on iOS. */}
      <NavigationBar hidden style="light" />
      {/* Ahead of the switch rather than a fourth case: none of the screens
          behind it can do anything without a camera, so this is a gate on the
          whole tree and not a place in the navigation. There is no persisted
          "seen it" flag — holding the permission *is* the flag, which also
          means a driver who revokes camera access lands back here instead of on
          a viewfinder that silently refuses to record. */}
      {!granted && (
        <WelcomeScreen
          denied={denied}
          busy={busy}
          onRequest={request}
          onOpenSettings={openSettings}
        />
      )}
      {granted && screen === 'recorder' && (
        <RecorderScreen
          onOpenSaved={() => setScreen('saved')}
          onOpenSettings={() => setScreen('settings')}
        />
      )}
      {granted && screen === 'saved' && <SavedClipsScreen onBack={() => setScreen('recorder')} />}
      {granted && screen === 'settings' && <SettingsScreen onBack={() => setScreen('recorder')} />}
    </>
  );
}
