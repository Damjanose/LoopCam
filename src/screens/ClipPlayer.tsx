import { useVideoPlayer, VideoView } from 'expo-video';
import { Pressable, SafeAreaView, StyleSheet, Text, View } from 'react-native';

import type { SavedClip } from '../../modules/loopcam-recorder';
import { STATUS_BAR_INSET } from './RecorderScreen';

/**
 * Full-screen playback of one saved clip.
 *
 * Saved clips live in app-private storage and are addressed by `file://` URI
 * (§7.1), which expo-video plays directly — no MediaStore entry and no
 * FileProvider needed as long as playback stays inside the app.
 */
export default function ClipPlayer({ clip, onBack }: { clip: SavedClip; onBack: () => void }) {
  const player = useVideoPlayer(clip.uri, (instance) => {
    // Opening a clip is an explicit request to watch it, so don't make the user
    // press play twice.
    instance.play();
  });

  return (
    <SafeAreaView style={styles.root}>
      <View style={styles.header}>
        <Pressable accessibilityRole="button" onPress={onBack} style={styles.back}>
          <Text style={styles.backLabel}>‹ Saved</Text>
        </Pressable>
        <Text style={styles.title} numberOfLines={1}>
          {clip.id}
        </Text>
      </View>

      <VideoView style={styles.video} player={player} nativeControls contentFit="contain" />
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: '#000' },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 12,
    padding: 16,
    paddingTop: STATUS_BAR_INSET + 16,
  },
  back: { paddingVertical: 8, paddingRight: 8 },
  backLabel: { color: '#0a84ff', fontSize: 20, fontWeight: '600' },
  title: { color: 'rgba(255,255,255,0.6)', fontSize: 15, flex: 1 },
  video: { flex: 1, backgroundColor: '#000' },
});
