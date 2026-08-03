import { requireNativeView } from 'expo';
import type { ViewProps } from 'react-native';

export interface LoopcamRecorderViewProps extends ViewProps {
  /** Which physical camera feeds the buffer. */
  lens?: 'back' | 'front';
  /** Fill vs. fit the preview surface. */
  resizeMode?: 'cover' | 'contain';
}

/**
 * Live camera preview. This is a view onto the same capture session the ring
 * buffer records from — mounting/unmounting it never starts or stops recording.
 */
const NativeView = requireNativeView<LoopcamRecorderViewProps>('LoopcamRecorder');

export default function LoopcamRecorderView(props: LoopcamRecorderViewProps) {
  return <NativeView {...props} />;
}
