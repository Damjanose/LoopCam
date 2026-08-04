import { type ReactNode, useEffect, useMemo, useRef } from 'react';
import { Animated, PanResponder, Pressable, StyleSheet, Text, View } from 'react-native';

import { colors, legend, radius } from '../theme';

/** Width of the action revealed when the row snaps open. */
const ACTION_WIDTH = 88;
/** Drag this far and releasing parks the row open instead of springing back. */
const OPEN_RATIO = 0.2;
/** Drag past this and releasing fires the action outright (Gmail-style). */
const COMMIT_RATIO = 0.5;
/** A fast flick past the open threshold counts as a commit too. */
const FLING_VX = 0.7;

const clamp = (value: number, min: number, max: number) => Math.min(max, Math.max(min, value));

/**
 * One two-sided swipe row: left uncovers Delete, right uncovers Share.
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
  onShare,
  open,
  onOpenChange,
}: {
  children: ReactNode;
  /** Called once the row has slid away; the caller drops it from the list. */
  onDelete: () => void;
  /** Opens the share sheet. Non-destructive, so the row springs back first. */
  onShare: () => void;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}) {
  const translateX = useRef(new Animated.Value(0)).current;
  // Where the row rests between gestures; a drag is measured from here so a
  // second swipe on an already-open row continues rather than restarts.
  const restingX = useRef(0);
  const width = useRef(0);

  // The responder is created once, so it reads its callbacks through refs
  // instead of capturing the first render's props.
  const onDeleteRef = useRef(onDelete);
  const onShareRef = useRef(onShare);
  const onOpenChangeRef = useRef(onOpenChange);
  useEffect(() => {
    onDeleteRef.current = onDelete;
    onShareRef.current = onShare;
    onOpenChangeRef.current = onOpenChange;
  });

  const settle = useRef((to: number) => {
    restingX.current = to;
    Animated.spring(translateX, {
      toValue: to,
      useNativeDriver: true,
      bounciness: 0,
      speed: 18,
    }).start();
  }).current;

  /** Delete is destructive, so the row leaves before the caller is told. */
  const commitDelete = useRef(() => {
    const to = -(width.current || ACTION_WIDTH);
    restingX.current = to;
    Animated.timing(translateX, { toValue: to, duration: 160, useNativeDriver: true }).start(
      ({ finished }) => {
        if (finished) onDeleteRef.current();
      },
    );
  }).current;

  /** Share leaves the row in place — the sheet is the feedback. */
  const commitShare = useRef(() => {
    settle(0);
    onOpenChangeRef.current(false);
    onShareRef.current();
  }).current;

  const responder = useMemo(
    () =>
      PanResponder.create({
        // Claim the gesture only once it is clearly horizontal, so vertical
        // list scrolling and taps still reach the children.
        onMoveShouldSetPanResponder: (_, g) =>
          Math.abs(g.dx) > 8 && Math.abs(g.dx) > Math.abs(g.dy) * 1.5,
        onPanResponderMove: (_, g) => {
          const limit = width.current || ACTION_WIDTH;
          translateX.setValue(clamp(restingX.current + g.dx, -limit, limit));
        },
        onPanResponderRelease: (_, g) => {
          const w = width.current || ACTION_WIDTH;
          const offset = clamp(restingX.current + g.dx, -w, w);
          const dragged = Math.abs(offset);
          // A flick commits in whichever direction it is actually travelling,
          // which is not always the side currently uncovered.
          const flung = Math.abs(g.vx) > FLING_VX && Math.sign(g.vx) === Math.sign(offset);

          if (dragged >= w * COMMIT_RATIO || (flung && dragged > w * OPEN_RATIO)) {
            if (offset < 0) commitDelete();
            else commitShare();
          } else if (dragged >= w * OPEN_RATIO) {
            settle(offset < 0 ? -ACTION_WIDTH : ACTION_WIDTH);
            onOpenChangeRef.current(true);
          } else {
            settle(0);
            onOpenChangeRef.current(false);
          }
        },
        onPanResponderTerminate: () => settle(restingX.current),
      }),
    [commitDelete, commitShare, settle, translateX],
  );

  useEffect(() => {
    if (!open && restingX.current !== 0) settle(0);
  }, [open, settle]);

  // Each action fades in as the row uncovers it, so a half-committed swipe
  // reads as "this is about to happen" rather than a bare coloured slab.
  const deleteOpacity = translateX.interpolate({
    inputRange: [-ACTION_WIDTH, -ACTION_WIDTH * 0.35, 0],
    outputRange: [1, 0.7, 0.3],
    extrapolate: 'clamp',
  });
  const shareOpacity = translateX.interpolate({
    inputRange: [0, ACTION_WIDTH * 0.35, ACTION_WIDTH],
    outputRange: [0.3, 0.7, 1],
    extrapolate: 'clamp',
  });
  // Only the uncovered side should be painted; otherwise the far edge of the
  // row shows the wrong colour behind the rounded corners.
  const deleteSide = translateX.interpolate({
    inputRange: [-1, 0, 1],
    outputRange: [1, 0, 0],
    extrapolate: 'clamp',
  });
  const shareSide = translateX.interpolate({
    inputRange: [-1, 0, 1],
    outputRange: [0, 0, 1],
    extrapolate: 'clamp',
  });

  return (
    <View
      style={styles.root}
      onLayout={(e) => {
        width.current = e.nativeEvent.layout.width;
      }}>
      <Animated.View
        style={[styles.actionLayer, styles.shareLayer, { opacity: shareSide }]}
        pointerEvents="box-none">
        <Animated.View style={[styles.actionSlot, styles.shareSlot, { opacity: shareOpacity }]}>
          <Pressable
            accessibilityRole="button"
            accessibilityLabel="Share recording"
            onPress={() => onShareRef.current()}
            style={({ pressed }) => [styles.actionButton, pressed && styles.pressed]}>
            <Text style={[styles.actionGlyph, styles.onAccent]}>↗</Text>
            <Text style={[styles.actionLabel, styles.onAccent]}>Share</Text>
          </Pressable>
        </Animated.View>
      </Animated.View>

      <Animated.View
        style={[styles.actionLayer, styles.deleteLayer, { opacity: deleteSide }]}
        pointerEvents="box-none">
        <Animated.View style={[styles.actionSlot, styles.deleteSlot, { opacity: deleteOpacity }]}>
          <Pressable
            accessibilityRole="button"
            accessibilityLabel="Delete recording"
            onPress={() => onDeleteRef.current()}
            style={({ pressed }) => [styles.actionButton, pressed && styles.pressed]}>
            <Text style={styles.actionGlyph}>✕</Text>
            <Text style={styles.actionLabel}>Delete</Text>
          </Pressable>
        </Animated.View>
      </Animated.View>

      <Animated.View
        style={[styles.content, { transform: [{ translateX }] }]}
        {...responder.panHandlers}>
        {children}
      </Animated.View>
    </View>
  );
}

const fill = { position: 'absolute', top: 0, right: 0, bottom: 0, left: 0 } as const;

const styles = StyleSheet.create({
  root: { borderRadius: radius.md, overflow: 'hidden', backgroundColor: colors.panel },
  actionLayer: { ...fill },
  shareLayer: { backgroundColor: colors.accent, alignItems: 'flex-start' },
  deleteLayer: { backgroundColor: colors.danger, alignItems: 'flex-end' },
  actionSlot: { height: '100%', width: ACTION_WIDTH },
  shareSlot: { alignItems: 'flex-start' },
  deleteSlot: { alignItems: 'flex-end' },
  actionButton: {
    width: ACTION_WIDTH,
    height: '100%',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 4,
  },
  pressed: { opacity: 0.6 },
  actionGlyph: { fontSize: 19, lineHeight: 22, color: colors.text },
  actionLabel: { ...legend, fontSize: 10, color: colors.text },
  /** Both actions are filled slabs, so their labels invert on the amber one. */
  onAccent: { color: colors.onAccent },
  // Opaque on purpose: a translucent row would let the actions show through
  // while it slides.
  content: { backgroundColor: colors.panel },
});
