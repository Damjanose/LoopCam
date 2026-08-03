import { StatusBar } from 'expo-status-bar';
import {
  Pressable,
  SafeAreaView,
  StatusBar as RNStatusBar,
  StyleSheet,
  Text,
  View,
} from 'react-native';

/**
 * The activity is edge-to-edge, and RN's SafeAreaView does not inset on
 * Android, so anything at the top of the screen lands under the system clock
 * and battery icons unless it is pushed down by hand.
 */
export const STATUS_BAR_INSET = RNStatusBar.currentHeight ?? 0;

import { LoopcamRecorderView } from '../../modules/loopcam-recorder';
import { useRecorder } from '../hooks/useRecorder';

const formatDuration = (seconds: number) => {
  const total = Math.floor(seconds);
  return `${String(Math.floor(total / 60)).padStart(2, '0')}:${String(total % 60).padStart(2, '0')}`;
};

/**
 * The driving screen: mounted on a dashboard holder, glanced at, not read.
 * Everything is sized for a single glance at arm's length — no small text, no
 * controls that need aim.
 */
export default function RecorderScreen({ onOpenSaved }: { onOpenSaved: () => void }) {
  const { status, isRecording, bufferFill, lastSaved, error, busy, config, play, stop, save } =
    useRecorder();

  return (
    <View style={styles.root}>
      <StatusBar style="light" />
      <LoopcamRecorderView style={StyleSheet.absoluteFill} lens="back" resizeMode="cover" />

      <SafeAreaView style={styles.overlay}>
        <View style={styles.header}>
          <View style={styles.headerRow}>
            <View style={[styles.dot, isRecording && styles.dotLive]} />
            <Text style={styles.elapsed}>{formatDuration(status.elapsedSec)}</Text>
            {/* Reachable mid-drive on purpose: browsing saved clips never
                interrupts the buffer. */}
            <Pressable
              accessibilityRole="button"
              accessibilityLabel="Saved clips"
              onPress={onOpenSaved}
              style={({ pressed }) => [styles.savedLink, pressed && styles.buttonPressed]}>
              <Text style={styles.savedLinkLabel}>Saved</Text>
            </Pressable>
          </View>
          <Text style={styles.caption}>
            {isRecording
              ? `Holding last ${formatDuration(status.bufferedSec)} · ${status.clipCount}/${status.maxClips} clips`
              : `Ready · ${config.bufferDurationSec / 60} min buffer`}
          </Text>

          {/* The buffer meter is the whole product in one bar: how much footage
              pressing Save would actually keep. */}
          <View style={styles.meterTrack}>
            <View style={[styles.meterFill, { width: `${bufferFill * 100}%` }]} />
          </View>
        </View>

        <View style={styles.footer}>
          {(error || lastSaved) && (
            <Text style={[styles.toast, error ? styles.toastError : styles.toastOk]}>
              {error ?? `Saved ${lastSaved?.id}`}
            </Text>
          )}

          <View style={styles.controls}>
            <Control
              label="Stop"
              tone="neutral"
              disabled={!isRecording || busy}
              onPress={stop}
            />
            <Control
              label={isRecording ? 'Recording' : 'Play'}
              tone="primary"
              disabled={isRecording || busy}
              onPress={play}
            />
            {/* Save keeps recording — deliberately not a stop-then-save (§2.3). */}
            <Control label="Save" tone="accent" disabled={!isRecording || busy} onPress={save} />
          </View>
        </View>
      </SafeAreaView>
    </View>
  );
}

function Control({
  label,
  tone,
  disabled,
  onPress,
}: {
  label: string;
  tone: 'primary' | 'accent' | 'neutral';
  disabled: boolean;
  onPress: () => void;
}) {
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={label}
      disabled={disabled}
      onPress={onPress}
      style={({ pressed }) => [
        styles.button,
        styles[tone],
        disabled && styles.buttonDisabled,
        pressed && styles.buttonPressed,
      ]}>
      <Text style={styles.buttonLabel}>{label}</Text>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: '#000' },
  overlay: { flex: 1, justifyContent: 'space-between' },
  header: { paddingHorizontal: 24, paddingTop: STATUS_BAR_INSET + 16, gap: 8 },
  headerRow: { flexDirection: 'row', alignItems: 'center', gap: 10 },
  savedLink: {
    marginLeft: 'auto',
    paddingVertical: 10,
    paddingHorizontal: 16,
    borderRadius: 12,
    backgroundColor: 'rgba(255,255,255,0.18)',
  },
  savedLinkLabel: { color: '#fff', fontSize: 16, fontWeight: '600' },
  dot: { width: 12, height: 12, borderRadius: 6, backgroundColor: '#555' },
  dotLive: { backgroundColor: '#ff3b30' },
  elapsed: { color: '#fff', fontSize: 34, fontVariant: ['tabular-nums'], fontWeight: '600' },
  caption: { color: 'rgba(255,255,255,0.7)', fontSize: 15 },
  meterTrack: {
    height: 6,
    borderRadius: 3,
    backgroundColor: 'rgba(255,255,255,0.15)',
    overflow: 'hidden',
  },
  meterFill: { height: '100%', backgroundColor: '#34c759' },
  footer: { paddingHorizontal: 20, paddingBottom: 28, gap: 12 },
  toast: { textAlign: 'center', fontSize: 14, paddingVertical: 6 },
  toastError: { color: '#ff6b6b' },
  toastOk: { color: '#34c759' },
  controls: { flexDirection: 'row', gap: 12 },
  button: { flex: 1, paddingVertical: 20, borderRadius: 16, alignItems: 'center' },
  buttonLabel: { color: '#fff', fontSize: 18, fontWeight: '600' },
  buttonPressed: { opacity: 0.75 },
  buttonDisabled: { opacity: 0.35 },
  primary: { backgroundColor: '#0a84ff' },
  accent: { backgroundColor: '#ff9f0a' },
  neutral: { backgroundColor: 'rgba(255,255,255,0.18)' },
});
