import { Platform } from 'react-native';

/**
 * One instrument cluster, lit at night.
 *
 * The app is glanced at through a windshield mount, so the palette is
 * deliberately monochrome — cold white etched over the live feed — and spends
 * its only two colours on the two things that carry meaning: amber for Save
 * (the action the whole product exists for) and red for "this is live".
 * Nothing else is allowed to be coloured.
 */
export const colors = {
  /** Behind the camera, and every screen that isn't the viewfinder. */
  void: '#05070A',
  /** Panels floating over the feed: dark enough to read against any scene. */
  glass: 'rgba(11,14,19,0.72)',
  /** Panels on an opaque screen, where there is nothing to veil. */
  panel: '#0D1116',
  /** Etched edges. The design is built from hairlines, not fills. */
  hairline: 'rgba(238,242,248,0.12)',
  hairlineStrong: 'rgba(238,242,248,0.26)',

  text: '#EEF2F8',
  textDim: 'rgba(238,242,248,0.56)',
  textFaint: 'rgba(238,242,248,0.30)',
  /** Sits on top of an amber fill. */
  onAccent: '#100B02',

  accent: '#FFB020',
  accentSoft: 'rgba(255,176,32,0.16)',
  live: '#FF3B2F',
  danger: '#FF3B2F',
} as const;

/** Instrument readouts are monospaced so digits stop jittering as they count. */
export const mono = Platform.select({ ios: 'Menlo', default: 'monospace' });

/** Utility labels are small, uppercase and widely tracked — cluster legends. */
export const legend = {
  fontSize: 11,
  fontWeight: '700',
  letterSpacing: 1.6,
  textTransform: 'uppercase',
} as const;

export const radius = { sm: 8, md: 14, lg: 20, pill: 999 } as const;
