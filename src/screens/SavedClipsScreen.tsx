import * as Sharing from 'expo-sharing';
import { useCallback, useEffect, useRef, useState } from 'react';
import {
  ActivityIndicator,
  Alert,
  AppState,
  FlatList,
  Pressable,
  SafeAreaView,
  StyleSheet,
  Text,
  View,
} from 'react-native';

import { LoopcamRecorder, type SavedClip } from '../../modules/loopcam-recorder';
import SwipeableRow from '../components/SwipeableRow';
import UndoToast from '../components/UndoToast';
import ClipPlayer from './ClipPlayer';
import { STATUS_BAR_INSET } from './RecorderScreen';

const formatDuration = (seconds: number) => {
  const total = Math.round(seconds);
  return `${String(Math.floor(total / 60)).padStart(2, '0')}:${String(total % 60).padStart(2, '0')}`;
};

const formatSize = (bytes: number) => `${(bytes / 1_000_000).toFixed(0)} MB`;

/** How long a delete stays undoable before the file is actually removed. */
const UNDO_WINDOW_MS = 5000;

const formatWhen = (ms: number) =>
  new Date(ms).toLocaleString(undefined, {
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  });

/**
 * The saved-clips gallery (§7.1). The saved directory *is* the index, so this
 * screen is a straight read of it — nothing to keep in sync.
 *
 * Opening it does not touch the recorder: the buffer belongs to the foreground
 * service, not the React tree, so a drive keeps being recorded while the user
 * browses what they already kept.
 */
export default function SavedClipsScreen({ onBack }: { onBack: () => void }) {
  const [clips, setClips] = useState<SavedClip[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [playing, setPlaying] = useState<SavedClip | null>(null);
  // At most one row stays parked open, as in Mail/Gmail.
  const [openId, setOpenId] = useState<string | null>(null);
  /** The delete awaiting confirmation, with the row's slot for a restore. */
  const [pending, setPending] = useState<{ clip: SavedClip; index: number } | null>(null);
  // Mirrored in a ref so cleanup paths can commit it without re-subscribing.
  const pendingRef = useRef<{ clip: SavedClip; index: number } | null>(null);
  const undoTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  const load = useCallback(async () => {
    try {
      setClips(await LoopcamRecorder.listSavedClips());
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
      setClips([]);
    }
  }, []);

  useEffect(() => {
    // Re-read on every mount rather than caching: a Save may have landed while
    // this screen was closed.
    void load();
  }, [load]);

  /**
   * Commit the outstanding delete for real. Called by the 5 s timer, by the
   * next delete, by leaving this screen, and by backgrounding the app — any of
   * those means the user has moved on, which counts as confirmation.
   */
  const flushPending = useCallback(() => {
    if (undoTimer.current) {
      clearTimeout(undoTimer.current);
      undoTimer.current = null;
    }
    const entry = pendingRef.current;
    pendingRef.current = null;
    setPending(null);
    if (!entry) return;

    LoopcamRecorder.deleteSavedClip(entry.clip.id).catch((e) => {
      Alert.alert('Could not delete', e instanceof Error ? e.message : String(e));
      // The delete may have half-happened; re-read to show the truth.
      void load();
    });
  }, [load]);

  const remove = useCallback(
    (clip: SavedClip) => {
      // A second delete confirms the first — only one undo is ever pending.
      flushPending();

      // The row leaves the list immediately; the file only goes once the undo
      // window closes, so the position is remembered for a possible restore.
      const index = Math.max(0, clips?.findIndex((c) => c.id === clip.id) ?? 0);
      setClips((current) => current?.filter((c) => c.id !== clip.id) ?? current);
      setOpenId((current) => (current === clip.id ? null : current));

      const entry = { clip, index };
      pendingRef.current = entry;
      setPending(entry);
      undoTimer.current = setTimeout(flushPending, UNDO_WINDOW_MS);
    },
    [clips, flushPending],
  );

  const share = useCallback(
    async (clip: SavedClip) => {
      // Sharing is another deliberate action, so it confirms any pending delete
      // rather than leaving two things in flight.
      flushPending();
      setOpenId(null);
      try {
        if (!(await Sharing.isAvailableAsync())) {
          Alert.alert('Sharing unavailable', 'This device has no share sheet available.');
          return;
        }
        // expo-sharing hands the app-private file to the system through its own
        // FileProvider, so the file:// URI needs no extra plumbing.
        await Sharing.shareAsync(clip.uri, {
          mimeType: 'video/mp4',
          UTI: 'public.mpeg-4',
          dialogTitle: `Share recording from ${formatWhen(clip.createdAtMs)}`,
        });
      } catch (e) {
        Alert.alert('Could not share', e instanceof Error ? e.message : String(e));
      }
    },
    [flushPending],
  );

  const undo = useCallback(() => {
    const entry = pendingRef.current;
    if (!entry) return;
    if (undoTimer.current) {
      clearTimeout(undoTimer.current);
      undoTimer.current = null;
    }
    pendingRef.current = null;
    setPending(null);
    setClips((current) => {
      if (!current) return current;
      const next = current.slice();
      next.splice(Math.min(entry.index, next.length), 0, entry.clip);
      return next;
    });
  }, []);

  // Leaving the screen (Back, or opening a clip) confirms the delete.
  useEffect(() => {
    if (playing) flushPending();
  }, [playing, flushPending]);

  // Unmount — including the app being torn down — confirms it too.
  useEffect(() => flushPending, [flushPending]);

  // Backgrounding is the last moment we can be sure of running: the OS may kill
  // the app afterwards without another React lifecycle.
  useEffect(() => {
    const sub = AppState.addEventListener('change', (state) => {
      if (state !== 'active') flushPending();
    });
    return () => sub.remove();
  }, [flushPending]);

  if (playing) {
    return <ClipPlayer clip={playing} onBack={() => setPlaying(null)} />;
  }

  return (
    <SafeAreaView style={styles.root}>
      <View style={styles.header}>
        <Pressable
          accessibilityRole="button"
          onPress={() => {
            flushPending();
            onBack();
          }}
          style={styles.back}>
          <Text style={styles.backLabel}>‹ Back</Text>
        </Pressable>
        <Text style={styles.title}>Saved</Text>
      </View>

      {clips === null ? (
        <ActivityIndicator style={styles.center} color="#fff" />
      ) : (
        <FlatList
          data={clips}
          keyExtractor={(clip) => clip.id}
          contentContainerStyle={styles.list}
          ListEmptyComponent={
            <Text style={styles.empty}>
              {error ?? 'No saved clips yet.\nPress Save while recording to keep the buffer.'}
            </Text>
          }
          renderItem={({ item }) => (
            <SwipeableRow
              onDelete={() => remove(item)}
              onShare={() => void share(item)}
              open={openId === item.id}
              onOpenChange={(open) => setOpenId(open ? item.id : null)}>
              <Pressable
                accessibilityRole="button"
                accessibilityLabel={`Play clip from ${formatWhen(item.createdAtMs)}`}
                accessibilityHint="Swipe left to delete, right to share"
                // Tapping an open row closes it rather than starting playback,
                // so the revealed Delete can't be hit by accident.
                onPress={() => (openId === item.id ? setOpenId(null) : setPlaying(item))}
                // Undo makes a confirmation dialog unnecessary here.
                onLongPress={() => remove(item)}
                style={({ pressed }) => [styles.row, pressed && styles.pressed]}>
                <View style={styles.thumb}>
                  <Text style={styles.thumbGlyph}>▶</Text>
                </View>
                <View style={styles.rowText}>
                  <Text style={styles.rowTitle}>{formatWhen(item.createdAtMs)}</Text>
                  <Text style={styles.rowMeta}>
                    {formatDuration(item.durationSec)} · {formatSize(item.sizeBytes)}
                  </Text>
                </View>
              </Pressable>
            </SwipeableRow>
          )}
        />
      )}

      {pending && (
        <UndoToast
          // Remount per delete so the countdown restarts.
          key={pending.clip.id}
          message="Recording deleted"
          durationMs={UNDO_WINDOW_MS}
          onUndo={undo}
        />
      )}
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: '#000' },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 12,
    padding: 16,
    paddingTop: STATUS_BAR_INSET + 16,
  },
  back: { paddingVertical: 8, paddingRight: 8 },
  backLabel: { color: '#0a84ff', fontSize: 20, fontWeight: '600' },
  title: { color: '#fff', fontSize: 28, fontWeight: '700' },
  center: { marginTop: 48 },
  list: { paddingHorizontal: 16, paddingBottom: 32, gap: 10 },
  empty: {
    color: 'rgba(255,255,255,0.5)',
    fontSize: 16,
    textAlign: 'center',
    marginTop: 64,
    lineHeight: 24,
  },
  row: { flexDirection: 'row', alignItems: 'center', gap: 14, padding: 12 },
  pressed: { opacity: 0.6 },
  thumb: {
    width: 56,
    height: 56,
    borderRadius: 10,
    backgroundColor: 'rgba(255,255,255,0.12)',
    alignItems: 'center',
    justifyContent: 'center',
  },
  thumbGlyph: { color: '#fff', fontSize: 20 },
  rowText: { flex: 1, gap: 4 },
  rowTitle: { color: '#fff', fontSize: 17, fontWeight: '600' },
  rowMeta: { color: 'rgba(255,255,255,0.6)', fontSize: 14, fontVariant: ['tabular-nums'] },
});
