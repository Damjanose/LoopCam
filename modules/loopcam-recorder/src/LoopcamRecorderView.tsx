import { requireNativeView } from 'expo';
import type { ViewProps } from 'react-native';

export interface LoopcamRecorderViewProps extends ViewProps {
  /** Fill vs. fit the preview surface. */
  resizeMode?: 'cover' | 'contain';
}

/**
 * Live camera preview. This is a view onto the same capture session the ring
 * buffer records from — mounting/unmounting it never starts or stops recording.
 *
 * It has no say in *which* camera: that is `RecorderConfig.cameraMode`, owned by
 * the controller. A view prop that also selected the camera could only ever be
 * the second, disagreeing source of truth.
 */
const NativeView = requireNativeView<LoopcamRecorderViewProps>('LoopcamRecorder');

export default function LoopcamRecorderView(props: LoopcamRecorderViewProps) {
  return <NativeView {...props} />;
}
