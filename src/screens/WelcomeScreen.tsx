import { ActivityIndicator, Pressable, SafeAreaView, StyleSheet, Text, View } from 'react-native';
import { StatusBar } from 'expo-status-bar';

import { colors, legend, radius } from '../theme';
import { STATUS_BAR_INSET } from './RecorderScreen';

/**
 * What one press of Get started will raise, in the order the OS raises it.
 *
 * The list is a promise about the next thirty seconds, not a menu: the native
 * side asks for all of these in a single batched sequence, so there is nothing
 * to tick here. Saying which are optional up front is what earns the tap —
 * a location dialog nobody was warned about is the one most likely to be
 * refused out of suspicion.
 */
const PERMISSIONS = [
  {
    label: 'Camera and microphone',
    note: 'To record the road ahead, with sound.',
    optional: false,
  },
  {
    label: 'Location',
    note: 'Stamps your speed and position onto saved clips.',
    optional: true,
  },
  {
    label: 'Notifications',
    note: 'Shows that recording is still running in the background.',
    optional: true,
  },
] as const;

/**
 * The first screen, and the only one anybody sees before granting.
 *
 * Deliberately not a carousel. Whoever just installed a dashcam wants to mount
 * the phone and drive, so this screen exists to explain the system dialogs that
 * are about to appear and then get out of the way — it is never seen again once
 * camera and mic are held.
 */
export default function WelcomeScreen({
  denied,
  busy,
  onRequest,
  onOpenSettings,
}: {
  denied: boolean;
  busy: boolean;
  onRequest: () => void;
  onOpenSettings: () => void;
}) {
  return (
    <SafeAreaView style={styles.root}>
      <StatusBar style="light" />
      <View style={styles.body}>
        <View style={styles.intro}>
          <Text style={styles.eyebrow}>Welcome</Text>
          <Text style={styles.title}>DashCam</Text>
          <Text style={styles.lede}>
            Always recording, never filling up your phone. Tap Save and the last minute of
            driving is kept — everything else is written over.
          </Text>
        </View>

        <View style={styles.group}>
          {PERMISSIONS.map(({ label, note, optional }, index) => (
            <View key={label} style={[styles.row, index > 0 && styles.rowDivided]}>
              <View style={styles.rowHead}>
                <Text style={styles.rowLabel}>{label}</Text>
                {optional ? <Text style={styles.optional}>Optional</Text> : null}
              </View>
              <Text style={styles.rowNote}>{note}</Text>
            </View>
          ))}
        </View>

        <Text style={styles.footnote}>
          Footage stays on your phone. Nothing is uploaded.
        </Text>
      </View>

      <View style={styles.footer}>
        {/* Only shown once a dialog has actually been refused. Explaining what
            a denial would cost before anyone has denied anything reads as a
            threat, and this screen is asking for trust. */}
        {denied ? (
          <Text style={styles.denied}>
            Camera and microphone access is off. DashCam cannot record without it — you can
            turn it back on in your phone&apos;s settings.
          </Text>
        ) : null}
        <Pressable
          accessibilityRole="button"
          accessibilityState={{ disabled: busy }}
          disabled={busy}
          onPress={denied ? onOpenSettings : onRequest}
          style={({ pressed }) => [styles.cta, pressed && styles.pressed, busy && styles.ctaBusy]}>
          {busy ? (
            <ActivityIndicator color={colors.onAccent} />
          ) : (
            <Text style={styles.ctaLabel}>{denied ? 'Open settings' : 'Get started'}</Text>
          )}
        </Pressable>
      </View>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: colors.void },
  /** Pushed off the top bar by hand — the activity is edge-to-edge (§RecorderScreen). */
  body: { flex: 1, justifyContent: 'center', paddingHorizontal: 24, paddingTop: STATUS_BAR_INSET },
  intro: { gap: 6, marginBottom: 28 },
  eyebrow: { ...legend, color: colors.textFaint },
  title: { color: colors.text, fontSize: 40, fontWeight: '700', letterSpacing: -1.2 },
  lede: { color: colors.textDim, fontSize: 15, lineHeight: 22, marginTop: 6 },

  /** Opaque panel, not glass: there is no camera feed behind this screen. */
  group: {
    borderRadius: radius.md,
    backgroundColor: colors.panel,
    borderWidth: StyleSheet.hairlineWidth,
    borderColor: colors.hairline,
    overflow: 'hidden',
  },
  row: { paddingHorizontal: 16, paddingVertical: 14, gap: 3 },
  rowDivided: { borderTopWidth: StyleSheet.hairlineWidth, borderTopColor: colors.hairline },
  rowHead: { flexDirection: 'row', alignItems: 'center', gap: 8 },
  rowLabel: { color: colors.text, fontSize: 15, fontWeight: '600' },
  optional: { ...legend, fontSize: 9, color: colors.textFaint },
  rowNote: { color: colors.textDim, fontSize: 13, lineHeight: 18 },

  footnote: { color: colors.textFaint, fontSize: 12, lineHeight: 17, marginTop: 14 },

  footer: { paddingHorizontal: 24, paddingBottom: 24, gap: 14 },
  denied: { color: colors.danger, fontSize: 13, lineHeight: 19 },
  /**
   * Amber, and the only amber on the screen. The palette spends its accent on
   * the one action a screen exists for, and here there is precisely one.
   */
  cta: {
    height: 56,
    alignItems: 'center',
    justifyContent: 'center',
    borderRadius: radius.pill,
    backgroundColor: colors.accent,
  },
  ctaBusy: { opacity: 0.7 },
  ctaLabel: { ...legend, fontSize: 13, color: colors.onAccent },
  pressed: { opacity: 0.55 },
});
