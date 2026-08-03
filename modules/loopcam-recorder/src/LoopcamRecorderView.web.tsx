import { View } from 'react-native';

import type { LoopcamRecorderViewProps } from './LoopcamRecorderView';

/** No capture session on web — render an inert placeholder. */
export default function LoopcamRecorderView(props: LoopcamRecorderViewProps) {
  return <View {...props} />;
}
