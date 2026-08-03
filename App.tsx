import { useState } from 'react';

import RecorderScreen from './src/screens/RecorderScreen';
import SavedClipsScreen from './src/screens/SavedClipsScreen';

/**
 * Two screens do not earn a navigator. Swapping on state keeps the entry point
 * dependency-free, and — because the rolling buffer is owned by the foreground
 * service rather than the React tree — unmounting the recorder to browse saved
 * clips does not interrupt a recording.
 *
 * TODO(phase-3): promote this to a real navigator once settings lands.
 */
export default function App() {
  const [screen, setScreen] = useState<'recorder' | 'saved'>('recorder');

  return screen === 'recorder' ? (
    <RecorderScreen onOpenSaved={() => setScreen('saved')} />
  ) : (
    <SavedClipsScreen onBack={() => setScreen('recorder')} />
  );
}
