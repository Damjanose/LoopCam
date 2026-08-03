import { useVideoPlayer, VideoView } from 'expo-video';
import { Pressable, SafeAreaView, StyleSheet, Text, View } from 'react-native';

import type { SavedClip } from '../../modules/loopcam-recorder';
import { colors, legend, mono, radius } from '../theme';
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
        <Pressable
          accessibilityRole="button"
          accessibilityLabel="Back to saved clips"
          onPress={onBack}
          style={({ pressed }) => [styles.back, pressed && styles.pressed]}>
          <Text style={styles.backGlyph}>‹</Text>
        </Pressable>
        <View style={styles.headerText}>
          <Text style={styles.eyebrow}>Recording</Text>
          <Text style={styles.title} numberOfLines={1}>
            {clip.id}
          </Text>
        </View>
      </View>

      <VideoView style={styles.video} player={player} nativeControls contentFit="contain" />
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: colors.void },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 12,
    paddingHorizontal: 16,
    paddingBottom: 16,
    paddingTop: STATUS_BAR_INSET + 12,
  },
  back: {
    width: 40,
    height: 40,
    alignItems: 'center',
    justifyContent: 'center',
    borderRadius: radius.pill,
    borderWidth: StyleSheet.hairlineWidth,
    borderColor: colors.hairlineStrong,
  },
  backGlyph: { color: colors.text, fontSize: 24, lineHeight: 26, marginTop: -3, marginLeft: -2 },
  pressed: { opacity: 0.55 },
  headerText: { flex: 1, gap: 2 },
  eyebrow: { ...legend, color: colors.textFaint },
  title: { color: colors.text, fontFamily: mono, fontSize: 14 },
  video: { flex: 1, backgroundColor: colors.void },
});
