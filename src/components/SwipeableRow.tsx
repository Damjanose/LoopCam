import { type ReactNode, useEffect, useMemo, useRef } from 'react';
import { Animated, PanResponder, Pressable, StyleSheet, Text, View } from 'react-native';

/** Width of the action revealed when the row snaps open. */
const ACTION_WIDTH = 88;
/** Drag this far and releasing parks the row open instead of springing back. */
const OPEN_RATIO = 0.2;
/** Drag past this and releasing commits the delete outright (Gmail-style). */
const COMMIT_RATIO = 0.5;
/** A fast flick past the open threshold counts as a commit too. */
const FLING_VX = 0.7;

const clamp = (value: number, min: number, max: number) => Math.min(max, Math.max(min, value));

/**
 * One swipe-to-delete row.
 *
 * Built on PanResponder rather than react-native-gesture-handler: this app
 * ships a custom native module and a dev client, so pulling in a new native
 * dependency for one gesture would cost a rebuild that the interaction doesn't
 * justify.
 *
 * `open`/`onOpenChange` are lifted so the list can keep at most one row parked
 * open — a row that is told it is no longer the open one closes itself.
 */
export default function SwipeableRow({
  children,
  onDelete,
  open,
  onOpenChange,
}: {
  children: ReactNode;
  /** Called once the row has slid away; the caller drops it from the list. */
  onDelete: () => void;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}) {
  const translateX = useRef(new Animated.Value(0)).current;
  // Where the row rests between gestures; a drag is measured from here so a
  // second swipe on an already-open row continues rather than restarts.
  const restingX = useRef(0);
  const width = useRef(0);

  const settle = useRef((to: number) => {
    restingX.current = to;
    Animated.spring(translateX, {
      toValue: to,
      useNativeDriver: true,
      bounciness: 0,
      speed: 18,
    }).start();
  }).current;

  const commit = useRef(() => {
    restingX.current = -(width.current || ACTION_WIDTH);
    Animated.timing(translateX, {
      toValue: -(width.current || ACTION_WIDTH),
      duration: 160,
      useNativeDriver: true,
    }).start(({ finished }) => {
      if (finished) onDeleteRef.current();
    });
  }).current;

  // The responder is created once, so it reads its callbacks through refs
  // instead of capturing the first render's props.
  const onDeleteRef = useRef(onDelete);
  const onOpenChangeRef = useRef(onOpenChange);
  useEffect(() => {
    onDeleteRef.current = onDelete;
    onOpenChangeRef.current = onOpenChange;
  });

  const responder = useMemo(
    () =>
      PanResponder.create({
        // Claim the gesture only once it is clearly horizontal, so vertical
        // list scrolling and taps still reach the children.
        onMoveShouldSetPanResponder: (_, g) =>
          Math.abs(g.dx) > 8 && Math.abs(g.dx) > Math.abs(g.dy) * 1.5,
        onPanResponderMove: (_, g) => {
          translateX.setValue(clamp(restingX.current + g.dx, -(width.current || ACTION_WIDTH), 0));
        },
        onPanResponderRelease: (_, g) => {
          const w = width.current || ACTION_WIDTH;
          const dragged = -clamp(restingX.current + g.dx, -w, 0);
          const flung = g.vx < -FLING_VX && dragged > w * OPEN_RATIO;

          if (dragged >= w * COMMIT_RATIO || flung) {
            commit();
          } else if (dragged >= w * OPEN_RATIO) {
            settle(-ACTION_WIDTH);
            onOpenChangeRef.current(true);
          } else {
            settle(0);
            onOpenChangeRef.current(false);
          }
        },
        onPanResponderTerminate: () => settle(restingX.current),
      }),
    [commit, settle, translateX],
  );

  useEffect(() => {
    if (!open && restingX.current !== 0) settle(0);
  }, [open, settle]);

  // The action fades in as the row uncovers it, so a half-committed swipe reads
  // as "this is about to delete" rather than a bare red slab.
  const actionOpacity = translateX.interpolate({
    inputRange: [-ACTION_WIDTH, -ACTION_WIDTH * 0.35, 0],
    outputRange: [1, 0.7, 0.3],
    extrapolate: 'clamp',
  });

  return (
    <View
      style={styles.root}
      onLayout={(e) => {
        width.current = e.nativeEvent.layout.width;
      }}>
      <Animated.View style={[styles.action, { opacity: actionOpacity }]}>
        <Pressable
          accessibilityRole="button"
          accessibilityLabel="Delete recording"
          onPress={() => onDeleteRef.current()}
          style={({ pressed }) => [styles.actionButton, pressed && styles.actionPressed]}>
          <Text style={styles.actionGlyph}>🗑</Text>
          <Text style={styles.actionLabel}>Delete</Text>
        </Pressable>
      </Animated.View>

      <Animated.View style={[styles.content, { transform: [{ translateX }] }]} {...responder.panHandlers}>
        {children}
      </Animated.View>
    </View>
  );
}

const styles = StyleSheet.create({
  root: { borderRadius: 14, overflow: 'hidden', backgroundColor: '#ff453a' },
  action: {
    position: 'absolute',
    top: 0,
    right: 0,
    bottom: 0,
    left: 0,
    alignItems: 'flex-end',
  },
  actionButton: {
    width: ACTION_WIDTH,
    height: '100%',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 4,
  },
  actionPressed: { opacity: 0.6 },
  actionGlyph: { fontSize: 20 },
  actionLabel: { color: '#fff', fontSize: 13, fontWeight: '600' },
  // Opaque on purpose: a translucent row would let the red action show through
  // while it slides.
  content: { backgroundColor: '#141414' },
});
