import { Pressable, SafeAreaView, StyleSheet, Text, View } from 'react-native';

import { colors, legend, radius } from '../theme';
import { STATUS_BAR_INSET } from './RecorderScreen';

/**
 * Settings. A shell for now — the header and the way back are the whole screen,
 * so the controls can be dropped into the body as they are specified.
 */
export default function SettingsScreen({ onBack }: { onBack: () => void }) {
  return (
    <SafeAreaView style={styles.root}>
      <View style={styles.header}>
        <Pressable
          accessibilityRole="button"
          accessibilityLabel="Back to camera"
          onPress={onBack}
          style={({ pressed }) => [styles.back, pressed && styles.pressed]}>
          <Text style={styles.backGlyph}>‹</Text>
        </Pressable>
        <View style={styles.headerText}>
          <Text style={styles.eyebrow}>Configure</Text>
          <Text style={styles.title}>Settings</Text>
        </View>
      </View>

      <View style={styles.body}>
        <Text style={styles.empty}>Nothing to configure yet.</Text>
      </View>
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
    paddingBottom: 20,
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
  headerText: { gap: 2 },
  eyebrow: { ...legend, color: colors.textFaint },
  title: { color: colors.text, fontSize: 30, fontWeight: '700', letterSpacing: -0.8 },
  body: { flex: 1, paddingHorizontal: 16 },
  empty: { color: colors.textDim, fontSize: 15, textAlign: 'center', marginTop: 96 },
  pressed: { opacity: 0.55 },
});
