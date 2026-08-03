import { useCallback, useEffect, useState } from 'react';
import {
  ActivityIndicator,
  FlatList,
  Pressable,
  SafeAreaView,
  StyleSheet,
  Text,
  View,
} from 'react-native';

import { LoopcamRecorder, type SavedClip } from '../../modules/loopcam-recorder';
import ClipPlayer from './ClipPlayer';
import { STATUS_BAR_INSET } from './RecorderScreen';

const formatDuration = (seconds: number) => {
  const total = Math.round(seconds);
  return `${String(Math.floor(total / 60)).padStart(2, '0')}:${String(total % 60).padStart(2, '0')}`;
};

const formatSize = (bytes: number) => `${(bytes / 1_000_000).toFixed(0)} MB`;

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

  if (playing) {
    return <ClipPlayer clip={playing} onBack={() => setPlaying(null)} />;
  }

  return (
    <SafeAreaView style={styles.root}>
      <View style={styles.header}>
        <Pressable accessibilityRole="button" onPress={onBack} style={styles.back}>
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
            <Pressable
              accessibilityRole="button"
              accessibilityLabel={`Play clip from ${formatWhen(item.createdAtMs)}`}
              onPress={() => setPlaying(item)}
              style={({ pressed }) => [styles.row, pressed && styles.rowPressed]}>
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
          )}
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
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 14,
    backgroundColor: 'rgba(255,255,255,0.08)',
    borderRadius: 14,
    padding: 12,
  },
  rowPressed: { opacity: 0.6 },
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
