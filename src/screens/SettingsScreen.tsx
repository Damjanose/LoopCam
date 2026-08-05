import { useMemo } from 'react';
import { Platform, Pressable, SafeAreaView, ScrollView, StyleSheet, Text, View } from 'react-native';

import {
  CAMERA_MODE_LABELS,
  CAMERA_MODE_UNAVAILABLE,
  LoopcamRecorder,
  QUALITY_LADDER,
  type CameraMode,
  type LocationStatus,
  type SpeedUnit,
} from '../../modules/loopcam-recorder';
import { SettingRow, SettingSwitch } from '../components/SettingRow';
import { useRecorder } from '../hooks/useRecorder';
import { colors, legend, radius } from '../theme';
import { STATUS_BAR_INSET } from './RecorderScreen';

const MODES: CameraMode[] = ['back', 'front', 'both'];

const SPEED_UNITS: { value: SpeedUnit; label: string }[] = [
  { value: 'kmh', label: 'km/h' },
  { value: 'mph', label: 'mph' },
];

/**
 * Why the speed field is blank, in the driver's words.
 *
 * Only the causes the user can do something about get a line. `noFix` and `ok`
 * deliberately say nothing: a receiver that has not locked yet resolves itself
 * within half a minute, and a note that appears and disappears on its own would
 * read as a fault rather than as normal cold-start behaviour.
 */
const LOCATION_NOTES: Partial<Record<LocationStatus, string>> = {
  denied: 'Location access is off — the stamp will show ‑‑ instead of a speed.',
  coarseOnly:
    'Approximate location cannot measure speed. Switch to precise location to burn a speed in.',
};

/**
 * Settings.
 *
 * Two groups, because two settings genuinely change what gets recorded: which
 * camera, and at what tier. Both are read from the same `useRecorder` the
 * recorder screen uses, so a change here is already applied by the time the
 * back arrow is pressed — there is no Save button and nothing to discard.
 */
export default function SettingsScreen({ onBack }: { onBack: () => void }) {
  // No preview: this screen shows no camera feed, and holding the session open
  // behind it would be battery spent on a picture nobody can see.
  const { config, capabilities, applyConfig, isRecording } = useRecorder({ preview: false });

  const qualities = capabilities.qualities[config.cameraMode];
  // Fall back to the full ladder rather than rendering an empty group: a mode
  // that reports no tiers is a probe that failed, and an empty box tells the
  // user nothing while a full one still lets them record.
  const tiers = qualities.length > 0 ? qualities : QUALITY_LADDER;

  /**
   * Only worth saying when the cap is real. On a phone whose single-camera
   * ceiling is 720p anyway, "both cameras are limited to 720p" is noise.
   */
  const dualCap =
    config.cameraMode === 'both' &&
    capabilities.qualities.both.length > 0 &&
    capabilities.qualities.both.length < capabilities.qualities.back.length
      ? capabilities.qualities.both[capabilities.qualities.both.length - 1]
      : null;

  /**
   * Re-read whenever the toggle changes, which is the only moment on this
   * screen that can turn a permission problem into a visible one. Synchronous
   * and side-effect free on both platforms — it never raises a dialog.
   */
  const locationNote = useMemo(
    () => (config.locationTaggingEnabled ? LOCATION_NOTES[LoopcamRecorder.getLocationStatus()] : undefined),
    [config.locationTaggingEnabled]
  );

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

      <ScrollView contentContainerStyle={styles.body}>
        <Text style={styles.section}>Camera</Text>
        <View style={styles.group} accessibilityRole="radiogroup">
          {MODES.map((mode, index) => {
            const supported = capabilities.modes.includes(mode);
            return (
              <SettingRow
                key={mode}
                first={index === 0}
                label={CAMERA_MODE_LABELS[mode]}
                note={supported ? undefined : CAMERA_MODE_UNAVAILABLE[mode]}
                selected={config.cameraMode === mode}
                disabled={!supported || isRecording}
                onPress={() => applyConfig({ cameraMode: mode })}
              />
            );
          })}
        </View>
        <Text style={styles.footnote}>
          {isRecording
            ? 'Stop recording to change the camera.'
            : 'Applies the next time you press Play.'}
        </Text>

        <Text style={styles.section}>Quality</Text>
        <View style={styles.group} accessibilityRole="radiogroup">
          {tiers.map((tier, index) => (
            <SettingRow
              key={tier}
              first={index === 0}
              label={tier}
              // Android's lowest *named* CameraX tier is SD (480p).
              // `Quality.LOWEST` resolves to 176x144 on some hardware, which is
              // unusable as evidence, so 360p maps to SD and says so rather
              // than gambling the buffer on whatever "lowest" means here.
              note={
                tier === '360p' && Platform.OS === 'android'
                  ? 'Records at 480p — Android has no lower tier.'
                  : undefined
              }
              selected={config.quality === tier}
              disabled={isRecording}
              onPress={() => applyConfig({ quality: tier })}
            />
          ))}
        </View>
        {dualCap ? (
          <Text style={styles.footnote}>
            Both cameras are limited to {dualCap} on this phone.
          </Text>
        ) : null}

        <Text style={styles.section}>GPS speed</Text>
        <View style={styles.group}>
          <SettingSwitch
            first
            label="Record speed and position"
            note={locationNote}
            value={config.locationTaggingEnabled}
            onValueChange={(locationTaggingEnabled) => applyConfig({ locationTaggingEnabled })}
          />
          {/* Nested rather than a separate group: the unit is meaningless with
              the switch off, and hiding it entirely would make the group jump
              in height every time the switch is flipped. */}
          {SPEED_UNITS.map(({ value, label }) => (
            <SettingRow
              key={value}
              label={label}
              selected={config.speedUnit === value}
              disabled={!config.locationTaggingEnabled}
              onPress={() => applyConfig({ speedUnit: value })}
            />
          ))}
        </View>
        {/* Unlike the camera and quality rows, neither of these is disabled
            while recording: they change what the next frame draws and nothing
            about the capture session, so there is nothing to rebuild. */}
        <Text style={styles.footnote}>
          The speed is burned into saved footage next to the clock, and written to a JSON file
          beside each clip. Nothing leaves your phone. Applies immediately, even while recording.
        </Text>
      </ScrollView>
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

  body: { paddingHorizontal: 16, paddingBottom: 40 },
  section: { ...legend, color: colors.textFaint, marginTop: 12, marginBottom: 10 },
  /** Opaque panel, not glass: there is no camera feed behind this screen. */
  group: {
    borderRadius: radius.md,
    backgroundColor: colors.panel,
    borderWidth: StyleSheet.hairlineWidth,
    borderColor: colors.hairline,
    overflow: 'hidden',
  },
  footnote: { color: colors.textDim, fontSize: 12, lineHeight: 17, marginTop: 10 },
  pressed: { opacity: 0.55 },
});
