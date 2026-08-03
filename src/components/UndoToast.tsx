import { useEffect, useRef } from 'react';
import { Animated, Easing, Pressable, StyleSheet, Text } from 'react-native';

/**
 * Bottom toast offering to undo the last delete.
 *
 * `key`ing this on the pending clip's id from the parent restarts both
 * animations for each new delete, so a second delete reads as a fresh 5 s
 * window rather than a stale bar continuing to drain.
 */
export default function UndoToast({
  message,
  durationMs,
  onUndo,
}: {
  message: string;
  /** How long the parent will wait before committing; drives the progress bar. */
  durationMs: number;
  onUndo: () => void;
}) {
  const entrance = useRef(new Animated.Value(0)).current;
  const remaining = useRef(new Animated.Value(1)).current;

  useEffect(() => {
    Animated.spring(entrance, {
      toValue: 1,
      useNativeDriver: true,
      bounciness: 4,
      speed: 16,
    }).start();
    Animated.timing(remaining, {
      toValue: 0,
      duration: durationMs,
      easing: Easing.linear,
      useNativeDriver: true,
    }).start();
  }, [durationMs, entrance, remaining]);

  return (
    <Animated.View
      style={[
        styles.root,
        {
          opacity: entrance,
          transform: [
            { translateY: entrance.interpolate({ inputRange: [0, 1], outputRange: [40, 0] }) },
          ],
        },
      ]}>
      <Text style={styles.message} numberOfLines={1}>
        {message}
      </Text>
      <Pressable
        accessibilityRole="button"
        accessibilityLabel="Undo delete"
        onPress={onUndo}
        hitSlop={10}
        style={({ pressed }) => [styles.undo, pressed && styles.pressed]}>
        <Text style={styles.undoLabel}>UNDO</Text>
      </Pressable>

      <Animated.View
        // scaleX from the left edge keeps the countdown on the native driver.
        style={[styles.progress, { transform: [{ scaleX: remaining }] }]}
      />
    </Animated.View>
  );
}

const styles = StyleSheet.create({
  root: {
    position: 'absolute',
    left: 16,
    right: 16,
    bottom: 32,
    flexDirection: 'row',
    alignItems: 'center',
    gap: 12,
    paddingLeft: 16,
    paddingRight: 8,
    paddingVertical: 14,
    borderRadius: 14,
    backgroundColor: '#2c2c2e',
    overflow: 'hidden',
  },
  message: { flex: 1, color: '#fff', fontSize: 15 },
  undo: { paddingHorizontal: 10, paddingVertical: 4 },
  undoLabel: { color: '#0a84ff', fontSize: 15, fontWeight: '700', letterSpacing: 0.5 },
  pressed: { opacity: 0.6 },
  progress: {
    position: 'absolute',
    left: 0,
    right: 0,
    bottom: 0,
    height: 2,
    backgroundColor: '#0a84ff',
    transformOrigin: 'left',
  },
});
