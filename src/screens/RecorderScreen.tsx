import { StatusBar } from 'expo-status-bar';
import { useEffect, useState } from 'react';
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
import { colors, legend, mono, radius } from '../theme';

const formatDuration = (seconds: number) => {
  const total = Math.floor(seconds);
  return `${String(Math.floor(total / 60)).padStart(2, '0')}:${String(total % 60).padStart(2, '0')}`;
};

const pad = (n: number) => String(n).padStart(2, '0');

/** `dd/mm/yyyy hh:mm:ss` — fixed order, so it is read the same way every glance. */
const formatStamp = (d: Date) =>
  `${pad(d.getDate())}/${pad(d.getMonth() + 1)}/${d.getFullYear()} ` +
  `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;

/**
 * The wall clock, re-rendered on the second it changes rather than on a free
 * 1 s interval: an interval started at an arbitrary phase drifts against the
 * system clock and visibly skips a second every so often.
 */
function useWallClock() {
  const [now, setNow] = useState(() => new Date());

  useEffect(() => {
    const id = setTimeout(() => setNow(new Date()), 1000 - (now.getTime() % 1000));
    return () => clearTimeout(id);
  }, [now]);

  return formatStamp(now);
}

/**
 * Shutter geometry, from the stock camera apps: an outer ring that never moves
 * and an inner shape that carries the state. Oversized for a phone — this one
 * is pressed at arm's length, on a bracket, without looking straight at it.
 */
const SHUTTER = 84;
const SHUTTER_CORE = SHUTTER - 20;
/** The flanking slots. Equal widths are what centre the shutter. */
const SLOT = 62;

/**
 * The driving screen: mounted on a dashboard holder, glanced at, not read.
 * Everything is sized for a single glance at arm's length — no small text, no
 * controls that need aim.
 */
export default function RecorderScreen({
  onOpenSaved,
  onOpenSettings,
}: {
  onOpenSaved: () => void;
  onOpenSettings: () => void;
}) {
  const {
    status,
    isRecording,
    lastSaved,
    error,
    storageWarning,
    busy,
    play,
    stop,
    save,
  } =
    useRecorder();

  const stamp = useWallClock();

  return (
    <View style={styles.root}>
      <StatusBar style="light" />
      <LoopcamRecorderView style={StyleSheet.absoluteFill} resizeMode="cover" />

      <SafeAreaView style={styles.overlay}>
        {/* No panel: the dot and the clock carry the state, so a card behind
            them would only cover road. The word "Standby" said nothing the
            unlit dot and the 00:00 clock do not. */}
        <View style={styles.header}>
          <View style={styles.headerRow}>
            <View style={styles.state}>
              <View style={[styles.dot, isRecording && styles.dotLive]} />
              <Text style={styles.elapsed}>{formatDuration(status.elapsedSec)}</Text>
            </View>

            {/* Reachable mid-drive on purpose: browsing saved clips never
                interrupts the buffer. */}
            <Pressable
              accessibilityRole="button"
              accessibilityLabel="Saved clips"
              onPress={onOpenSaved}
              style={({ pressed }) => [styles.savedLink, pressed && styles.buttonPressed]}>
              <Text style={styles.savedLinkLabel}>Saved</Text>
              <Text style={styles.savedLinkChevron}>›</Text>
            </Pressable>
            {/* Icon-only: settings are a rare, parked-car errand, so it takes
                the smallest target on the HUD rather than a labelled one. */}
            <Pressable
              accessibilityRole="button"
              accessibilityLabel="Settings"
              onPress={onOpenSettings}
              hitSlop={8}
              style={({ pressed }) => [styles.iconButton, pressed && styles.buttonPressed]}>
              <Text style={styles.iconGlyph}>⚙</Text>
            </Pressable>
          </View>
        </View>

        <View style={styles.footer}>
          {/* One slot, so a failure is never buried under a success. A storage
              sweep outranks the save that triggered it: the clip did save, but
              the part the user needs to know is that older ones just went. */}
          {(error || storageWarning || lastSaved) && (
            <View
              style={[styles.toast, error || storageWarning ? styles.toastError : styles.toastOk]}>
              <Text
                style={[
                  styles.toastLabel,
                  error || storageWarning ? styles.toastLabelError : styles.toastLabelOk,
                ]}>
                {/* The clip id is filename plumbing, not news — mid-drive the
                    only thing worth reading is that the save landed. */}
                {error ?? storageWarning ?? 'Saved ✓'}
              </Text>
            </View>
          )}

          {/* Stock-camera control bar: one shutter on the centre line, one
              secondary action orbiting it. The empty left slot is deliberate —
              it is what keeps the shutter under the thumb's centre of travel. */}
          <View style={styles.controls}>
            <View style={styles.slot} />

            {/* Start and stop are the same button, as on every camera: the two
                actions are mutually exclusive states of one recording toggle,
                so a second button could only ever be the disabled one. */}
            <Pressable
              accessibilityRole="button"
              accessibilityLabel={isRecording ? 'Stop recording' : 'Start recording'}
              accessibilityState={{ disabled: busy }}
              disabled={busy}
              onPress={isRecording ? stop : play}
              hitSlop={12}
              style={({ pressed }) => [
                styles.shutter,
                busy && styles.buttonDisabled,
                pressed && styles.shutterPressed,
              ]}>
              {/* Filled circle at rest, square while running — the shape is the
                  state readout, legible without reading the word "Rec". */}
              <View style={[styles.shutterCore, isRecording && styles.shutterCoreLive]} />
            </Pressable>

            {/* Save only exists while there is something to save — with an empty
                buffer the button is not merely inert, it is meaningless, and a
                permanently greyed control on a three-item bar reads as broken.
                The slot stays behind it so the shutter never shifts. */}
            {isRecording ? (
              /* Save keeps recording — deliberately not a stop-then-save (§2.3). */
              <Pressable
                accessibilityRole="button"
                accessibilityLabel="Save the last few minutes"
                accessibilityState={{ disabled: busy }}
                disabled={busy}
                onPress={save}
                hitSlop={12}
                style={({ pressed }) => [
                  styles.slot,
                  styles.save,
                  busy && styles.buttonDisabled,
                  pressed && styles.buttonPressed,
                ]}>
                <Text style={styles.saveLabel}>Save</Text>
              </Pressable>
            ) : (
              <View style={styles.slot} />
            )}
          </View>

          {/* Last line on the screen, in the bottom-right corner — the same
              corner the watermark burns into the footage, so what is on screen
              matches what a saved clip will show. */}
          <Text style={styles.stamp}>{stamp}</Text>
        </View>
      </SafeAreaView>
    </View>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: colors.void },
  overlay: { flex: 1, justifyContent: 'space-between' },

  header: { paddingHorizontal: 16, paddingTop: STATUS_BAR_INSET + 16 },
  headerRow: { flexDirection: 'row', alignItems: 'center', gap: 8 },
  /**
   * The clock keeps its own glass behind it — the readouts float over the feed
   * now, and a bright sky would otherwise swallow the one number that matters.
   */
  state: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 10,
    paddingVertical: 8,
    paddingHorizontal: 14,
    borderRadius: radius.pill,
    borderWidth: StyleSheet.hairlineWidth,
    borderColor: colors.hairline,
    backgroundColor: colors.glass,
  },
  dot: { width: 8, height: 8, borderRadius: 4, backgroundColor: colors.textFaint },
  dotLive: {
    backgroundColor: colors.live,
    shadowColor: colors.live,
    shadowOpacity: 1,
    shadowRadius: 6,
    shadowOffset: { width: 0, height: 0 },
    elevation: 4,
  },
  savedLink: {
    marginLeft: 'auto',
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
    paddingVertical: 8,
    paddingHorizontal: 14,
    borderRadius: radius.pill,
    borderWidth: StyleSheet.hairlineWidth,
    borderColor: colors.hairlineStrong,
  },
  savedLinkLabel: { ...legend, color: colors.text },
  savedLinkChevron: { color: colors.textDim, fontSize: 16, marginTop: -2 },

  /** Square-ish pill, same height as the Saved link so the row reads as one. */
  iconButton: {
    width: 38,
    height: 38,
    alignItems: 'center',
    justifyContent: 'center',
    borderRadius: radius.pill,
    borderWidth: StyleSheet.hairlineWidth,
    borderColor: colors.hairlineStrong,
  },
  iconGlyph: { color: colors.text, fontSize: 17, lineHeight: 20 },

  elapsed: {
    color: colors.text,
    fontFamily: mono,
    fontSize: 30,
    lineHeight: 34,
    letterSpacing: -0.5,
    fontVariant: ['tabular-nums'],
  },

  footer: { paddingHorizontal: 16, paddingBottom: 24, gap: 12 },
  /** Tabular figures: the seconds tick without the whole line reflowing. */
  stamp: {
    alignSelf: 'flex-end',
    color: colors.textDim,
    fontFamily: mono,
    fontSize: 13,
    letterSpacing: 0.4,
    fontVariant: ['tabular-nums'],
  },
  toast: {
    alignSelf: 'center',
    paddingVertical: 8,
    paddingHorizontal: 16,
    borderRadius: radius.pill,
    borderWidth: StyleSheet.hairlineWidth,
    backgroundColor: colors.glass,
  },
  toastError: { borderColor: 'rgba(255,59,47,0.5)' },
  toastOk: { borderColor: 'rgba(255,176,32,0.5)' },
  toastLabel: { fontSize: 13, letterSpacing: 0.2 },
  toastLabelError: { color: colors.danger },
  toastLabelOk: { color: colors.accent },

  controls: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: 8,
    paddingTop: 4,
  },
  /** Side slots are equal width so the shutter sits on the screen's centre line. */
  slot: { width: SLOT, height: SLOT, alignItems: 'center', justifyContent: 'center' },

  /** The ring: untouched by state, so the target never moves or changes size. */
  shutter: {
    width: SHUTTER,
    height: SHUTTER,
    borderRadius: SHUTTER / 2,
    borderWidth: 4,
    borderColor: colors.text,
    alignItems: 'center',
    justifyContent: 'center',
  },
  shutterPressed: { opacity: 0.7 },
  shutterCore: {
    width: SHUTTER_CORE,
    height: SHUTTER_CORE,
    borderRadius: SHUTTER_CORE / 2,
    backgroundColor: colors.live,
  },
  shutterCoreLive: {
    width: SHUTTER_CORE * 0.46,
    height: SHUTTER_CORE * 0.46,
    borderRadius: radius.sm,
  },

  /** The one filled, coloured element on the screen — the reason the app exists. */
  save: {
    borderRadius: SLOT / 2,
    backgroundColor: colors.accent,
    shadowColor: colors.accent,
    shadowOpacity: 0.45,
    shadowRadius: 16,
    shadowOffset: { width: 0, height: 4 },
    elevation: 6,
  },
  saveLabel: { ...legend, fontSize: 12, color: colors.onAccent },

  buttonPressed: { opacity: 0.6 },
  buttonDisabled: { opacity: 0.3 },
});
