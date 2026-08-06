# Timestamp watermark and standby preview

Two features that both change the same seam — the lifetime of the capture
session and what happens to a frame between the sensor and the file.

1. **Watermark.** Every recorded frame carries a burned-in local date and time,
   bottom-right.
2. **Standby preview.** Stopping recording no longer closes the camera. The
   viewfinder stays live so the driver can aim the phone before pressing Play.

Section references (`§2.3`, `§7.1`, …) point at
[`docs/LoopCam_Development_Plan.pdf`](../../LoopCam_Development_Plan.pdf).

## Decisions taken before the design

**The watermark is burned in at capture, not at merge.** Save is a stream-copy
concat (`ClipMerger`, §4) — no decode, no re-encode. Drawing the timestamp at
merge time would force a full decode/draw/encode of the whole window on every
Save: seconds to minutes of CPU per save, a large battery cost, and the loss of
the single biggest efficiency lever in §6. Burning in at capture keeps the merge
a passthrough and costs one GPU composite per frame instead.

**A per-frame clock, not a per-clip one.** The timestamp advances during the
clip, so a saved file reads as continuous wall-clock footage rather than a
sequence of frozen labels. It is redrawn once per second and reused for the
frames in between.

**Always on, no setting.** There is no settings UI in this build and a dashcam
timestamp is the evidentiary point of the recording. `RecorderConfig` gains no
flag; if a toggle is wanted later it is one boolean and one branch.

## Part 1 — Watermark

### Content and appearance

```
┌─────────────────────────┐
│                         │
│      (road ahead)       │
│                         │
│      2026-08-05 18:53:12│
└─────────────────────────┘
```

- Format `dd/MM/yyyy HH:mm:ss`, device local time zone, 24-hour, zero-padded.
  (Was `yyyy-MM-dd` when this was written; changed to match the on-screen HUD
  clock, so the viewfinder and the footage read identically.)
  Fixed pattern in both native layers — not a locale-dependent formatter, whose
  width would jump between frames and whose meaning would be ambiguous in a
  dispute.
- Bottom-right of the frame as displayed, inset by 2.5% of the frame's short
  edge on each side.
- Monospace, ~3.5% of the displayed frame's *short* edge — the same edge the
  inset is measured against. (Was "frame height" when this was written, which
  is only right in landscape: the app records portrait, and against the long
  edge the plate came out around three quarters of the frame wide and ran off
  the side of the picture.) White, drawn over a
  black-at-55%-alpha rounded plate. The plate is what makes it legible against a
  bright road; a shadow alone is not enough on overexposed asphalt.
- Both platforms define these numbers as constants in one file each
  (`WatermarkStyle`), mirrored file-for-file the way the rest of the module is.

### Android — `OverlayEffect`

CameraX is already pinned at 1.4.2, which ships `androidx.camera:camera-effects`.
`OverlayEffect` is exactly this job: it hands out a `Canvas` per frame,
composites it on the GPU, and can target `PREVIEW` and `VIDEO_CAPTURE` at once —
so the viewfinder shows the same watermark the file gets, for free.

New file `WatermarkOverlay.kt`:

- Owns the `OverlayEffect`, its `HandlerThread`, the `Paint`s, and the cached
  formatted string.
- `setOnDrawListener` clears the canvas (`PorterDuff.Mode.CLEAR`), applies
  `frame.sensorToBufferTransform` so the drawing lands in the same space the
  frame does, then draws the plate and the text. Returns `true` to have the
  frame rendered.
- The frame's clock is `frame.timestampNanos`, which is in the
  `SystemClock.uptimeNanos` domain. It is converted to wall clock through an
  offset sampled once when the effect is created. Using `System.currentTimeMillis()`
  directly inside the draw callback would work too, but drifts from the frame it
  is labelling by up to a pipeline's worth of latency.
- `close()` releases the effect and quits the thread.

`CameraXSegmentRecorder.prepare` changes from binding use cases directly to
binding a `UseCaseGroup`:

```kotlin
val group = UseCaseGroup.Builder()
  .addUseCase(preview)
  .addUseCase(capture)
  .addEffect(watermark.effect)
  .build()
provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, group)
```

Everything else in `prepare` — the camera-state latch, the quality ladder, the
failure propagation — is untouched.

**Known cost, accepted:** routing `VIDEO_CAPTURE` through an effect puts the
stream through a GPU surface-processing pass instead of straight to the encoder.
That is a real power and thermal cost on a long drive, and on some devices it
narrows the set of resolutions CameraX will resolve. The quality ladder in
`qualityLadder()` already handles a camera that cannot honour the requested tier
by stepping down, so a narrowed set degrades rather than fails to bind. This is
the price of burned-in text on Android; there is no zero-cost path.

### iOS — CoreImage composite in `ClipWriter`

Video samples currently go `videoInput.append(sampleBuffer)` — a passthrough.
They become:

1. `CIImage(cvPixelBuffer:)` over the incoming buffer.
2. The cached overlay `CIImage` composited on top (`CISourceOverCompositing`).
3. Rendered by a shared `CIContext` (Metal-backed, created once for the process)
   into a buffer from `AVAssetWriterInputPixelBufferAdaptor.pixelBufferPool`.
4. `adaptor.append(buffer, withPresentationTime: pts)`.

Audio is unchanged and still goes through its own input.

New file `WatermarkRenderer.swift` owns the overlay: given a pixel-buffer size
and an orientation, it renders the text to a `CGImage` and caches it, keyed by
the formatted second string plus the size. At 30 fps that is one text rasterise
per 30 frames; the other 29 are a composite.

**The rotation trap.** `videoInput.transform` is `rotationAngle: .pi / 2`
(`SegmentRecorder.swift:270`) — the pixels are landscape and the container asks
players to display them portrait. Text drawn upright in pixel space therefore
appears rotated 90° in every player. The overlay must be drawn pre-rotated in
pixel space, and positioned in the pixel-space corner that maps to the
*displayed* bottom-right, not the pixel-space bottom-right. This mapping is a
single function, `WatermarkRenderer.overlayRect(pixelSize:)`, isolated so it is
the one thing to check on device.

`ClipWriter.init` gains the adaptor; `sourcePixelBufferAttributes` request
`kCVPixelFormatType_32BGRA` at the video settings' dimensions. Note the pool is
only available after `startWriting()`, which the existing init already calls
last.

**Preview.** The iOS preview layer is fed straight from `AVCaptureSession`
(`LoopcamRecorderView.attach`), so it never sees the composite. The view gains a
`CATextLayer`, styled from the same `WatermarkStyle` constants and ticked by a
1 s timer, positioned in the corresponding corner of its bounds. It is a
best-effort match for framing purposes — the file is the source of truth. This
keeps both platforms WYSIWYG in standby, which is the whole point of Part 2.

### Merge and metadata

`ClipMerger` is unchanged on both platforms. That is the design working: because
the burn-in happened upstream of the file, the concat stays a stream copy.

## Part 2 — Standby preview

### What changes

Today `stop()` runs `recorder.release()` and kills the foreground service, so
the preview goes black and the camera closes. Camera-bound and recording become
independent:

| | camera bound | foreground service | writing clips |
| --- | --- | --- | --- |
| screen mounted, idle, app foregrounded | yes | no | no |
| recording | yes | yes (Android) | yes |
| app backgrounded while idle | no | no | no |
| app backgrounded while recording | yes | yes | yes |
| screen unmounted (Saved Clips) while idle | no | no | no |

The `RecorderState` machine (`idle | recording | saving | stopping`) does **not**
gain a state. Whether the camera is bound is a property of the capture engine,
not of the buffer's state machine, and conflating them would put a
non-recording state into every consumer that switches on it — including the
notification and the Live Activity.

### Android — a lifecycle the module owns

The camera currently binds to `RecordingService` as its `LifecycleOwner`. That
is why closing the service closes the camera, and it is why standby cannot
simply reuse it: an idle viewfinder must not cost a persistent notification.

New file `PreviewLifecycleOwner.kt` — a `LifecycleOwner` backed by a
`LifecycleRegistry`, main-thread confined, driven to `STARTED` while the camera
is wanted and `CREATED` when it is not. `CameraXSegmentRecorder.lifecycleOwner`
becomes this, permanently. The foreground service still starts at Play and stops
at Stop, but purely for background survival (§5.1); it is no longer what holds
the camera open.

The consequence that makes this the right option: **the camera is never rebound
at Play.** Standby binds it; recording reuses the same binding. No preview
blink, and no first clip racing a camera that is still opening — the failure
`prepare()`'s existing comment describes at length.

`prepare(config)` becomes idempotent. If already bound with the same
`videoQuality` it returns immediately; if the quality changed it unbinds and
rebinds. `release()` splits in two:

- `stopClip(discard:)` + leaving the binding alone — what `SegmentController.stop`
  now calls.
- `release()` — full unbind, effect closed, bus cleared — called only by
  `stopPreview` and `OnDestroy`.

`SegmentController.stop` therefore no longer calls `recorder.release()`. It
still cancels the in-flight clip, drains and deletes the buffer, deletes the
session directory, and returns to `IDLE`.

**Android 14 camera-while-backgrounded.** Accessing the camera from the
background without a `camera`-type foreground service is blocked. Standby is
foreground-only, so this is satisfied by construction — but not by accident:
the module implements Expo's `OnActivityEntersBackground` and unbinds there
*only when the controller is `IDLE`*. A backgrounded recording keeps its camera,
because it has the service. This native hook is the safety net; the JS
`AppState` handling below is the normal path.

### iOS — session running, audio deferred

Simpler, because `AVCaptureSession` was already independent of the writer.
`prepare(config:)` splits:

- `startPreview(config:)` — configure the session **video-only**, `startRunning`,
  publish to `CameraPreviewBus`.
- `enableAudio()` — called at Play: adds the mic input and audio output inside a
  `beginConfiguration`/`commitConfiguration` pair and activates the
  `.playAndRecord` audio session.

Deferring the audio session is not an optimisation, it is a correctness point:
activating `.playAndRecord` merely to idle in the viewfinder would duck or stop
whatever the driver is listening to, for no recording benefit. The reconfigure
costs roughly 100 ms and happens before the first clip opens, so it costs no
footage.

`teardown()` splits the same way as Android: stopping a recording leaves the
session running and only deactivates audio; `stopPreview()` stops the session
and clears the bus.

The existing graceful degradation is preserved — if the mic cannot be added,
`audioEnabled` drops to false and the session records video-only, with every
segment agreeing, which is what the passthrough merge requires.

### JS surface

`LoopcamRecorderModule` gains two async functions on both platforms:

```ts
startPreview(): Promise<void>;   // idempotent
stopPreview(): Promise<void>;    // idempotent; no-op while recording
```

`stopPreview` while recording is deliberately a no-op rather than an error: it
is called from an `AppState` transition, and a background-while-recording is the
exact case the rolling buffer exists to survive.

Permissions split. Camera alone gates the preview; the mic is only needed to
record:

```ts
requestCameraPermission(): Promise<boolean>;   // new
requestPermissions(): Promise<boolean>;        // unchanged — camera + mic, at Play
```

Asking for the mic at mount would put a microphone prompt in front of a user who
has only opened the app to look at the road.

`useRecorder` gains:

- `previewActive: boolean`
- `cameraDenied: boolean`
- an effect that, on mount, requests camera permission and calls `startPreview()`
- an `AppState` subscription: `background`/`inactive` → `stopPreview()`;
  `active` → `startPreview()`. Both no-ops while recording.
- cleanup on unmount → `stopPreview()`

`RecorderScreen` renders a centred "Camera access is needed to see the road
ahead" panel with a button that opens system settings when `cameraDenied`, in
place of the black rectangle it would otherwise show. The `Stop` button's
behaviour in the UI is otherwise unchanged — after Stop the HUD reads `Standby`
and the feed keeps running, which is now literally true rather than a label over
a dead surface.

## Files

New:

```
modules/loopcam-recorder/android/…/WatermarkOverlay.kt
modules/loopcam-recorder/android/…/PreviewLifecycleOwner.kt
modules/loopcam-recorder/ios/WatermarkRenderer.swift
```

Modified:

```
modules/loopcam-recorder/android/build.gradle          + camera-effects
modules/loopcam-recorder/android/…/CameraXSegmentRecorder.kt   UseCaseGroup, idempotent prepare, split release
modules/loopcam-recorder/android/…/SegmentController.kt        stop() no longer releases the camera
modules/loopcam-recorder/android/…/LoopcamRecorderModule.kt    startPreview/stopPreview, camera permission, background hook
modules/loopcam-recorder/ios/SegmentRecorder.swift             split prepare, pixel-buffer adaptor path
modules/loopcam-recorder/ios/SegmentController.swift           stop() no longer tears down the session
modules/loopcam-recorder/ios/LoopcamRecorderModule.swift       startPreview/stopPreview, camera permission
modules/loopcam-recorder/ios/LoopcamRecorderView.swift         preview timestamp layer
modules/loopcam-recorder/src/LoopcamRecorderModule.ts          + the two functions
modules/loopcam-recorder/src/LoopcamRecorderModule.web.ts      + stubs
src/hooks/useRecorder.ts                                       preview lifecycle, AppState
src/screens/RecorderScreen.tsx                                 permission-denied panel
```

## Verification

There is no test harness in this repo — no runner, no test script, no device
farm — and both features are pixel-and-lifecycle behaviour that a unit test
would not have caught anyway. Verification is a device checklist, run on one
Android device and one iPhone (not simulators; the emulator camera is fake):

1. Open the app cold. Preview is live before Play is pressed, with a ticking
   timestamp in the bottom-right of the viewfinder.
2. Aim the phone. Framing is adjustable with nothing recording — no notification
   on Android, no audio interruption of background music on either platform.
3. Press Play. **The preview does not blink or drop.** Recording starts.
4. Wait past two clip boundaries, press Save. Open the saved clip in the
   gallery: the timestamp is burned in, upright, bottom-right, legible, and
   **advancing** through the clip — including across every clip boundary in the
   merged file.
5. Check the merged file's timestamp is continuous across boundaries with no
   repeated or skipped second at the seams.
6. Press Stop. Preview stays live; HUD reads `Standby`; the notification is
   gone on Android.
7. Background the app while idle → return. Preview resumes; no crash, no
   permission re-prompt.
8. Background the app **while recording** → wait past a boundary → return.
   Recording survived and the buffer grew.
9. Navigate to Saved Clips while idle and back. Preview resumes.
10. Deny camera permission → the screen shows the permission panel, not a black
    rectangle.
11. Rotate-check the iOS overlay specifically: the pixel-space mapping in
    `overlayRect(pixelSize:)` is the most likely thing to be wrong, and it is
    wrong in a way only a played-back file reveals.
12. Record a 10-minute session on each device and note battery drop and case
    temperature against a pre-change run — the Android GPU effect pass is the
    one change with an ongoing cost, and it should be measured rather than
    assumed acceptable.
