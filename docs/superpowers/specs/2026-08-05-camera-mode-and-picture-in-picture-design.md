# Camera mode, picture-in-picture, and the first real Settings screen

One choice — which camera feeds the buffer — pulled through every layer it
touches. Back, front, or both at once; when both, the front camera rides in the
top-right corner of the frame the way a video call carries the near end.

Three things fall out of that choice and are specified here together because
none of them stands up alone:

1. **Camera mode.** `RecorderConfig` gains `cameraMode: 'back' | 'front' | 'both'`.
2. **The composite.** In `both`, the front camera is burned into the recorded
   frame as a picture-in-picture, so a saved clip *is* what the viewfinder showed.
3. **The Settings screen.** It is a stub today. It becomes the place the mode
   and the quality are chosen — which also means settings have to survive an app
   restart, which nothing in this codebase currently does.

Section references (`§2.3`, `§7.1`, …) point at
[`docs/LoopCam_Development_Plan.pdf`](../../LoopCam_Development_Plan.pdf).
The compositing pipeline this builds on is specified in
[the watermark and standby-preview design](./2026-08-05-watermark-and-standby-preview-design.md).

## Decisions taken before the design

**One composited file, not two files.** In `both`, a segment is a single video
with the front camera drawn into it. The alternative — a back file and a front
file per segment — doubles buffer storage and encoder load, and turns
`SavedClip` (one `uri`), the ring buffer, `ClipMerger` and the gallery into
pair-aware code. The composite rides the per-frame drawing path that already
exists for the watermark on both platforms and leaves every one of those types
untouched. The cost is that the front footage can never be separated from the
back afterwards; for a dashcam, where the file is evidence of one moment seen
two ways, that is the right trade.

**Unsupported modes are hidden, not attempted.** Dual capture is hardware-gated
— `AVCaptureMultiCamSession` needs an A12 or newer iPhone, and CameraX's
concurrent-camera support is absent on a large share of Android devices. The
module therefore exposes a capability probe and Settings only offers what the
device can actually do. A mode that silently degrades at Play is worse than no
mode: on a dash mount, nobody reads the toast.

**The PiP is fixed at top-right and never moves.** No drag, no tap-to-swap. The
screen is glanced at, not operated, and a fixed layout is the only version where
the compositor has nothing to persist, the file can never change layout
partway through a clip, and there is nothing to mis-tap while driving.

**Quality is one setting, not two.** The tier ladder gains 360p and 480p and is
filtered by the active mode rather than duplicated per mode. Two quality rows on
one screen would leave the user guessing which one is live.

**Camera mode and quality apply at the next Play.** Changing either means
tearing down and rebinding the capture session, which drops the clip in flight
and puts a seam in the buffer. Both rows are disabled while recording, with the
reason on screen.

## Part 1 — The configuration

### `RecorderConfig`

```ts
export type CameraMode = 'back' | 'front' | 'both';
export type VideoQuality = '360p' | '480p' | '720p' | '1080p' | '4k';

export interface RecorderConfig {
  // …existing fields unchanged…
  /** Which camera(s) feed the buffer. `both` composites front into back. */
  cameraMode: CameraMode;
  quality: VideoQuality;
}
```

`DEFAULT_CONFIG.cameraMode` is `'back'` — the mode every device supports, and
the one a dashcam is for.

`BITRATE_MBPS` gains the two new tiers:

| Tier | Mbps |
|---|---|
| 360p | 0.8 |
| 480p | 1.2 |
| 720p | 2.5 (unchanged) |
| 1080p | 5 (unchanged) |
| 4k | 20 (unchanged) |

`estimatedBufferBytes` needs no other change: `both` produces one composited
stream at the chosen tier, so the storage projection is the same arithmetic it
already does. This is a second reason the single-file decision earns its keep.

### Resolving a tier on each platform

Neither platform offers all five tiers by name, so a tier is a *target height*
and each platform picks the closest thing it supports.

| Tier | Android (`Quality`) | iOS (single camera, preset) | iOS (multi-cam, `activeFormat`) |
|---|---|---|---|
| 360p | `SD` — see note | `.cif352x288` | nearest format to 640×360 |
| 480p | `SD` | `.vga640x480` | nearest format to 640×480 |
| 720p | `HD` | `.hd1280x720` | nearest format to 1280×720 |
| 1080p | `FHD` | `.hd1920x1080` | nearest format to 1920×1080 |
| 4k | `UHD` | `.hd4K3840x2160` | not offered |

**Note on 360p / Android.** CameraX's lowest *named* tier is `SD` (480p);
`Quality.LOWEST` resolves to whatever the device calls lowest, which on some
hardware is 176×144. Rather than gamble the buffer on that, 360p maps to `SD`
and Android may deliver 480p when 360p is chosen. Settings says so in one line
under the tier. Guessing low and getting 176×144 footage of a licence plate is a
failure the user cannot recover from; delivering 480p when they asked for 360p
costs them a little disk.

`qualityLadder` on Android (`CameraXSegmentRecorder.kt:185`) keeps its existing
shape — step down from the request before stepping up — with `SD` now reachable
from two tiers. Its `DESCENDING_QUALITIES` list is unchanged; only the
`VideoQuality` → `Quality` mapping grows.

### Persistence

Config is in-memory on both platforms today: `getConfig()` returns whatever the
controller happens to hold, and a cold start returns the defaults. A Settings
screen whose choices evaporate on relaunch is not a Settings screen.

Config is persisted **natively**, not in JS:

- Android — `SharedPreferences("loopcam.config")`, written in
  `configure()`, read in the controller's initialiser.
- iOS — `UserDefaults.standard` under a `loopcam.config.` key prefix, same two
  points.

Native, because native already owns config, and because `RecordingService` runs
in a process that can outlive the JS side (§5.1) — a JS-side store would leave
the foreground service booting on the wrong camera after a restart, and the
project has no storage dependency to do it with anyway.

Each field is stored individually rather than as one blob, so a config written
by an older build gains new fields at their defaults instead of failing to
decode. An unrecognised stored value (a `cameraMode` a downgrade doesn't know,
a `quality` tier that was removed) falls back to the default for that field —
`VideoQuality.from` already does exactly this and `CameraMode.from` mirrors it.

### The capability probe

```ts
export interface CameraCapabilities {
  /** Modes this device can actually run. Always contains 'back'. */
  modes: CameraMode[];
  /** Tiers selectable per mode; 4k never appears under 'both'. */
  qualities: Record<CameraMode, VideoQuality[]>;
}

getCapabilities(): CameraCapabilities;   // synchronous, cached after first call
```

Synchronous because Settings renders from it on first paint, and because both
platforms answer from a cheap static query.

- **iOS** — `modes` includes `'both'` when
  `AVCaptureMultiCamSession.isMultiCamSupported`, and `'front'` when a
  `.builtInWideAngleCamera` exists at `.front`. Dual tiers come from the
  intersection of both devices' `activeFormat` candidates, capped at 1080p.
- **Android** — `'both'` requires
  `ProcessCameraProvider.availableConcurrentCameraInfos` to contain a pair with
  one front and one back camera. Dual tiers are capped at **720p**: CameraX's
  concurrent mode constrains both streams, and offering 1080p there would be a
  promise the bind cannot keep.

The web stub (`LoopcamRecorderModule.web.ts`) returns back-only with the full
single-camera tier list, matching how the other stubs behave.

## Part 2 — Capture

### The composite, in both platforms' terms

The PiP is drawn in *displayed* coordinates — the same space the watermark
already uses — so it lands top-right of the picture as a player will show it,
not top-right of a sideways buffer.

```
┌──────────────────────────┐
│                 ┌──────┐ │   PiP: width 30% of displayed frame width,
│                 │front │ │   16:9, inset from the top and right edges by
│                 └──────┘ │   the watermark's own inset fraction (2.5% of
│      (road ahead)        │   the short edge), corner radius 6% of PiP
│                          │   height, 1px hairline at 40% white.
│       05/08/2026 18:53:12│
└──────────────────────────┘
```

Geometry constants live in `WatermarkStyle` (both platforms already mirror this
file for the stamp) under a `Pip` grouping, so the two overlays cannot drift
apart. The PiP is drawn **before** the timestamp — they do not overlap by
construction, but the order is fixed so a future style change cannot bury the
clock.

The front image is scaled to fill the PiP rect and centre-cropped, never
letterboxed: a black bar inside an already-small inset reads as a broken feed.

**The front camera is mirrored, in the file and on screen; the back camera
never is.** Mirroring the front lens is the phone-camera convention and the
only thing a driver checking their framing can read, and the file follows the
preview rather than disagreeing with it — a viewfinder that contradicted the
footage would defeat the point of compositing at capture. This supersedes the
original unmirrored-everywhere rule, which made the front viewfinder read
backwards. In `both` mode the main stream is the road, so only the inset is
mirrored.

### Android — concurrent camera into the existing `OverlayEffect`

Back camera keeps everything it has today: a `UseCaseGroup` of `Preview` +
`VideoCapture` with the overlay effect attached, which is what puts the same
composite on the screen and in the file. The front camera is bound as a second
concurrent camera carrying a single `ImageAnalysis` use case, and its latest
frame becomes the bitmap the overlay draws.

```kotlin
val back = ConcurrentCamera.SingleCameraConfig(BACK, backGroup, owner)
val front = ConcurrentCamera.SingleCameraConfig(FRONT, frontGroup, owner)
provider.bindToLifecycle(listOf(back, front))
```

`WatermarkOverlay` becomes the general per-frame overlay and gains a nullable
PiP source. It keeps its current contract — everything runs on its own
`HandlerThread`, the canvas is cleared each frame, `false` is never returned —
and gains one field:

- The analyzer converts each `ImageProxy` to an upright `Bitmap`
  (`toBitmap()`, rotated by `imageInfo.rotationDegrees`) and publishes it
  through an `AtomicReference`. The draw listener reads the reference and, if
  non-null, draws it into the PiP rect.
- **Frames are never recycled.** The draw thread and the analyzer thread are
  different threads with no handshake between them; recycling a bitmap the
  compositor may be mid-draw on is a crash. The analyzer allocates and lets GC
  collect the displaced one.
- The analyzer requests a **small** resolution (a `ResolutionSelector` targeting
  ~640×360) and `STRATEGY_KEEP_ONLY_LATEST`, and drops frames so it publishes at
  **~15 fps**. The PiP occupies under a tenth of the frame; decoding the front
  camera at capture resolution and 30 fps to draw it a third of an inch wide is
  pure battery cost (§6). A stale-by-66 ms PiP is not observable.
- A front frame older than **2 seconds** is not drawn. If the front camera
  stalls, the PiP goes away rather than freezing a stale picture into footage
  that is timestamped as live — the one failure mode that would make the file
  actively misleading.

Standby preview in `both` binds the same pair with the front `ImageAnalysis` and
a back `Preview` only, targeting `CameraEffect.PREVIEW` — the existing
`PREVIEW_TARGETS`/`SESSION_TARGETS` split carries over unchanged.

`startPreview` and `prepare` both already unbind everything before binding; both
now also tear down the front analyzer, on the same reasoning the existing code
gives for clearing `standbyPreview` (`CameraXSegmentRecorder.kt:110`) — a
forgotten front binding is a stale use case a later `unbind` would take the live
session down with.

### iOS — `AVCaptureMultiCamSession` into the existing `ClipWriter`

`AVSegmentRecorder` swaps `AVCaptureSession` for `AVCaptureMultiCamSession` when
the mode is `both`. Multi-cam refuses `sessionPreset`, so the session runs at
`.inputPriority` and each device's `activeFormat` is chosen directly against the
tier's target height — the `preset(for:)` path stays exactly as it is for the
two single-camera modes.

Inputs and outputs are added with `addInputWithNoConnections` /
`addOutputWithNoConnections` and wired by explicit `AVCaptureConnection`s;
multi-cam will not form implicit connections. The front connection sets
`isVideoMirrored = true`, the back one `false`.

- A second `AVCaptureVideoDataOutput` carries the front camera, delivering on
  the same `sessionQueue` — the writer is already single-threaded on that queue
  and a second queue would put two threads on it.
- Its delegate stores the latest frame as a `CIImage` plus the host time it
  arrived. `ClipWriter.appendVideo` composites it into the PiP rect over the
  back frame, then composites the timestamp on top, then renders once into the
  pool buffer. One render pass, not two.
- The same **2-second staleness cutoff** applies, for the same reason.
- The front camera's `activeVideoMinFrameDuration` is set to **15 fps** — under
  multi-cam the two cameras share a hardware budget, and this is the cheapest
  place to buy it back.
- After configuration, `session.hardwareCost` and
  `session.systemPressureCost` are checked. Over `1.0`, the front format steps
  down one candidate and it is checked again, up to three times; if it still
  will not fit, the session falls back to back-only and emits
  `onError(cameraUnavailable)`. A device that cannot afford both cameras must
  still record the road.

**The preview.** `LoopcamRecorderView` draws its own timestamp today
(`LoopcamRecorderView.swift:24`) because iOS feeds the preview layer straight
from the session, bypassing the writer's composite. The PiP has exactly the same
problem and takes exactly the same solution: a second
`AVCaptureVideoPreviewLayer`, built with `init(sessionWithNoConnection:)` and
given an explicit connection to the front camera's port, laid out in the PiP
rect above the back layer and below the stamp. The rect comes from the shared
`WatermarkStyle` constants, so the viewfinder's PiP sits where the recorded one
will.

The bus (`CameraPreviewBus`) publishes the session as it does now; the view
decides whether to build a front layer by asking the session for a front video
port, which keeps the view ignorant of `RecorderConfig` — it stays a window onto
the session and nothing more (`LoopcamRecorderView.swift:5`).

### Dead code this replaces

`LoopcamRecorderViewProps.lens` and iOS's `setLens` TODO
(`LoopcamRecorderView.swift:129`) are removed. Camera selection is a config
concern owned by the controller; a view prop that also claimed to select the
camera was only ever going to be the second, disagreeing source of truth. The
`lens="back"` on `RecorderScreen.tsx:91` goes with it.

## Part 3 — The Settings screen

`SettingsScreen` currently renders "Nothing to configure yet." It gains two
sections, in the app's existing idiom — hairline-bordered rows on `colors.panel`,
`legend` section headers, the amber accent reserved for the selected row.

```
CAMERA
┌────────────────────────────────────────┐
│  Back                              ●   │
│  Front                                 │
│  Both — front in the corner            │
└────────────────────────────────────────┘
  Applies the next time you press Play.

QUALITY
┌────────────────────────────────────────┐
│  360p                                  │
│  480p                              ●   │
│  720p                                  │
│  1080p                                 │
└────────────────────────────────────────┘
  Android records 360p as 480p — it has no lower tier.
  Both cameras are limited to 720p on this phone.
```

- Modes absent from `getCapabilities().modes` render **disabled**, dimmed, with
  a one-line reason beneath: *"This phone can't run both cameras at once."*
  Shown rather than hidden, so the option's absence is explained rather than
  mysterious.
- The quality list is `capabilities.qualities[cameraMode]` — 4K simply is not in
  the list while `both` is selected, and switching to `both` while 4K is
  selected clamps the stored tier to the highest the mode allows and says so.
- While `isRecording`, every row in both sections is disabled and the footer
  note becomes *"Stop recording to change the camera."*
- Selection writes through `useRecorder`'s existing `applyConfig`, which already
  pushes to native; native persists it.

`useRecorder` gains `capabilities` (read once from
`LoopcamRecorder.getCapabilities()`) and is otherwise unchanged. `RecorderScreen`
needs no change at all for the PiP: on both platforms the preview already shows
the composite, which is the whole point of compositing at capture.

## Files

New:

```
modules/loopcam-recorder/src/capabilities.ts                    CameraMode, CameraCapabilities
src/components/SettingRow.tsx                                   the shared radio row
```

Modified:

```
modules/loopcam-recorder/src/LoopcamRecorder.types.ts           CameraMode, 360p/480p, cameraMode field
modules/loopcam-recorder/src/config.ts                          bitrates, DEFAULT_CONFIG.cameraMode
modules/loopcam-recorder/src/LoopcamRecorderModule.ts           getCapabilities
modules/loopcam-recorder/src/LoopcamRecorderModule.web.ts       stub capabilities
modules/loopcam-recorder/src/LoopcamRecorderView.tsx            lens prop removed

modules/loopcam-recorder/android/…/RecorderTypes.kt             CameraMode, new tiers, config map
modules/loopcam-recorder/android/…/CameraXSegmentRecorder.kt    concurrent bind, front analyzer, tier map
modules/loopcam-recorder/android/…/WatermarkOverlay.kt          PiP source and rect
modules/loopcam-recorder/android/…/WatermarkStyle.kt            PiP geometry
modules/loopcam-recorder/android/…/LoopcamRecorderModule.kt     getCapabilities, SharedPreferences

modules/loopcam-recorder/ios/RecorderTypes.swift                CameraMode, new tiers
modules/loopcam-recorder/ios/SegmentRecorder.swift              multi-cam session, front output, composite
modules/loopcam-recorder/ios/WatermarkRenderer.swift            PiP composite helper
modules/loopcam-recorder/ios/WatermarkStyle.swift               PiP geometry
modules/loopcam-recorder/ios/LoopcamRecorderView.swift          front preview layer, setLens removed
modules/loopcam-recorder/ios/LoopcamRecorderModule.swift        getCapabilities, UserDefaults

src/hooks/useRecorder.ts                                        capabilities
src/screens/SettingsScreen.tsx                                  the two sections
src/screens/RecorderScreen.tsx                                  lens prop removed
```

## Risks, and the spike that retires them

Two platform behaviours are load-bearing and neither is safe to assume. Both are
cheap to test and **must be tested before the rest of the work starts**, because
a negative result changes the design rather than the implementation.

1. **Does CameraX accept a `CameraEffect` on a concurrent binding, with
   `VideoCapture` on the back camera?** Concurrent mode documents a limit of two
   use cases per camera and constrained resolutions, and `VideoCapture` support
   there is device-dependent. If the effect or the video capture is rejected,
   the fallback is a custom `SurfaceProcessor` doing the composite in GLES —
   more code, better performance, same user-facing behaviour.
2. **Does the `ImageAnalysis` → `Bitmap` → `Canvas` path hold 30 fps on the back
   stream?** The YUV→RGB conversion is on the CPU. If it costs too much, the
   same GLES `SurfaceProcessor` is the answer, sampling the front
   `SurfaceTexture` directly with no CPU copy at all.

A one-day spike on one mid-range Android device answers both.

iOS carries less risk — multi-cam is a documented API with an explicit support
flag and an explicit cost model, and the composite lands in a CoreImage pipeline
that already exists.

## Verification

There is still no test harness in this repo, and this is pixel-and-lifecycle
behaviour that a unit test would not have caught. Verification is a device
checklist, run on one Android phone with concurrent-camera support, one without,
and one iPhone with multi-cam (not simulators — the emulator camera is fake):

1. Open Settings cold. Camera shows Back selected. On the phone without
   concurrent support, **Both** is visibly disabled with its reason underneath.
2. Select **Front**. Return to the recorder: the standby preview is the front
   camera, upright, **mirrored**, with the timestamp still bottom-right — and
   the saved clip matches it. Check the burned-in timestamp reads forwards.
3. Select **Both**. The standby preview shows the road with the front camera in
   the top-right, inset, rounded, not stretched.
4. Quality lists 360p–720p under Both on Android (with the cap note) and
   360p–1080p on iOS. 4K is absent. Switching back to **Back** restores 4K.
5. Press Play in Both. **The preview does not blink.** Record past two clip
   boundaries and Save.
6. Open the saved clip: the PiP is burned in, top-right, upright, correctly
   placed across **every clip boundary in the merged file**, and the timestamp
   is still legible bottom-right.
7. Cover the front camera / force it to stall (a second app grabbing it, where
   the OS permits): the PiP disappears within 2 s rather than freezing, and the
   back camera keeps recording.
8. Change the camera mode while recording: every row is disabled and the footer
   explains why.
9. Set Front + 480p, force-quit the app, relaunch: Settings still reads Front
   and 480p, and Play records the front camera.
10. Record 10 minutes in Both and check battery drain and thermal state against
    the same run in Back — the front camera at 15 fps and a small analysis
    resolution should keep the difference modest, and a device that throttles is
    a signal to drop the PiP frame rate further.
