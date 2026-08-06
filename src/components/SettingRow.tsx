import { Pressable, StyleSheet, Switch, Text, View } from 'react-native';

import { colors, radius } from '../theme';

/**
 * One choice in a settings group: a label, an optional reason, and a mark on
 * the selected one.
 *
 * A radio rather than a picker or a segmented control. The list is short, the
 * screen is read parked, and every option being visible at once is what lets an
 * unavailable one be *shown greyed with a reason* instead of silently missing —
 * which is the difference between "this phone can't do that" and "the app is
 * broken".
 */
export function SettingRow({
  label,
  note,
  selected,
  disabled = false,
  first = false,
  onPress,
}: {
  label: string;
  /** Why this row is unavailable, or what it will actually do. */
  note?: string;
  selected: boolean;
  disabled?: boolean;
  /** Suppresses the divider, which belongs *between* rows and not above them. */
  first?: boolean;
  onPress: () => void;
}) {
  return (
    <Pressable
      accessibilityRole="radio"
      accessibilityState={{ selected, disabled }}
      accessibilityLabel={note ? `${label}. ${note}` : label}
      disabled={disabled}
      onPress={onPress}
      style={({ pressed }) => [
        styles.row,
        !first && styles.divided,
        pressed && !disabled && styles.pressed,
      ]}>
      <View style={styles.text}>
        <Text style={[styles.label, disabled && styles.labelDisabled]}>{label}</Text>
        {note ? <Text style={styles.note}>{note}</Text> : null}
      </View>
      {/* The dot is the only coloured thing in the group — amber is spent on
          meaning here as it is everywhere else in the app. */}
      <View style={[styles.mark, selected && styles.markSelected]} />
    </Pressable>
  );
}

/**
 * One on/off setting, laid out on the same row geometry as {@link SettingRow}.
 *
 * A switch rather than a two-option radio because this is genuinely a toggle,
 * not a choice between alternatives — and because it sits directly above a real
 * radio group (the unit), where two radios in a row would read as one group of
 * four.
 */
export function SettingSwitch({
  label,
  note,
  value,
  disabled = false,
  first = false,
  onValueChange,
}: {
  label: string;
  /** Why this row is unavailable, or what turning it on actually does. */
  note?: string;
  value: boolean;
  disabled?: boolean;
  /** Suppresses the divider, which belongs *between* rows and not above them. */
  first?: boolean;
  onValueChange: (value: boolean) => void;
}) {
  return (
    <View
      accessibilityRole="switch"
      accessibilityState={{ checked: value, disabled }}
      accessibilityLabel={note ? `${label}. ${note}` : label}
      style={[styles.row, !first && styles.divided]}>
      <View style={styles.text}>
        <Text style={[styles.label, disabled && styles.labelDisabled]}>{label}</Text>
        {note ? <Text style={styles.note}>{note}</Text> : null}
      </View>
      {/* Amber when on, matching the radio's dot: the accent means "this is
          active" everywhere else in the app too. */}
      <Switch
        value={value}
        disabled={disabled}
        onValueChange={onValueChange}
        trackColor={{ false: colors.hairlineStrong, true: colors.accent }}
        thumbColor={colors.text}
      />
    </View>
  );
}

const MARK = 18;

const styles = StyleSheet.create({
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 12,
    // Tall: this is still a screen used in a parked car, often in gloves.
    minHeight: 56,
    paddingVertical: 12,
    paddingHorizontal: 16,
  },
  divided: { borderTopWidth: StyleSheet.hairlineWidth, borderTopColor: colors.hairline },
  text: { flex: 1, gap: 3 },
  label: { color: colors.text, fontSize: 16, letterSpacing: -0.2 },
  labelDisabled: { color: colors.textFaint },
  note: { color: colors.textDim, fontSize: 12, lineHeight: 16 },

  mark: {
    width: MARK,
    height: MARK,
    borderRadius: MARK / 2,
    borderWidth: StyleSheet.hairlineWidth,
    borderColor: colors.hairlineStrong,
  },
  markSelected: {
    backgroundColor: colors.accent,
    borderColor: colors.accent,
  },
  pressed: { opacity: 0.55 },
});
