# Store submission reference

Everything the two consoles ask for, with the answers this codebase actually
supports. Keep this in sync if behaviour changes — a Data Safety form that
disagrees with the binary is a policy violation, not a paperwork slip.

Privacy policy source: [`privacy-policy.md`](./privacy-policy.md). It must be
published at a public URL before either submission.

---

## Both stores

| Field | Value |
| --- | --- |
| App name | DashCam - Traffic |
| Bundle ID / package | `com.damjano.dashcam` |
| Category | Utilities (alternative: Travel) |
| Data collected | **Precise location** — on-device only, not linked to the user, not used for tracking |
| Data shared | **None** |
| Tracking | **No** |
| Account required | No |
| Privacy policy URL | *(fill in once hosted)* |

Verified in code: no network calls anywhere in `src/`, `modules/`, or
`targets/`; no analytics, crash-reporting, or advertising dependencies in
`package.json`; `expo.modules.updates.ENABLED=false` in the Android manifest.

**On "data collected".** Nothing is transmitted — the location never leaves the
phone. But both stores define *collection* by what the app reads and retains,
not by what it uploads: the speed is burned into the recorded video and the
position is written to a JSON sidecar next to each saved clip, both of which
persist on the device. Declaring "none" here because there is no server is the
mistake that gets an app pulled, so it is declared. `NSPrivacyCollectedDataTypes`
in `app.json` carries the matching `NSPrivacyCollectedDataTypePreciseLocation`
entry with linked `false` and tracking `false`.

---

## Google Play

### Data safety

- Does your app collect or share any of the required user data types? → **Yes**
  - Data type: **Location → Approximate location / Precise location** →
    *Precise location*
  - Collected? → **Yes**. Shared? → **No**.
  - Processed ephemerally? → **No** (it is written into saved clips and their
    sidecars, which persist)
  - Required or optional? → **Optional** — Settings → GPS speed turns it off,
    and refusing the permission leaves the app fully functional
  - Purpose → **App functionality** only. Not analytics, not advertising, not
    personalisation.
- Is all user data encrypted in transit? → N/A (no data transmitted)
- Do you provide a way for users to request data deletion? → N/A (no data
  reaches us; recordings and their sidecars are deleted in-app or by
  uninstalling)

### Permissions declarations

| Permission | Declaration |
| --- | --- |
| `CAMERA` | Core functionality — recording the road ahead. |
| `RECORD_AUDIO` | Records the audio track included in saved clips. |
| `POST_NOTIFICATIONS` | Shows the ongoing recording notice, which also carries the Save and Stop controls. |
| `FOREGROUND_SERVICE_CAMERA` | Keeps the rolling buffer recording while the app is not on screen — the app is useless if recording stops when the driver leaves the screen. |
| `FOREGROUND_SERVICE_MICROPHONE` | Same, for the audio track. |
| `ACCESS_FINE_LOCATION` | Reads the GNSS Doppler speed once a second to burn the speed into the footage and write the sidecar. Fine, not coarse, because an approximate fix carries no usable speed at all. |
| `ACCESS_COARSE_LOCATION` | Declared only because Android 12+ shows the user a precise/approximate choice, and requesting fine alone removes that choice. The app detects an approximate-only grant and shows `‑‑` rather than burning in a network-derived number. |
| `FOREGROUND_SERVICE_LOCATION` | The burned-in speed must keep updating with the screen off, which from Android 14 requires the service to declare the location type. Narrowed at `startForeground` to what is actually granted. |

`ACCESS_BACKGROUND_LOCATION` is deliberately **not** requested — a foreground
service with the location type already covers screen-off recording, and the
background permission would buy a Play policy review for nothing.

Play requires a **short video** demonstrating the foreground-service use.
Record: press Play, background the app, show the notification persisting and the
buffer counter advancing, then Save from the notification. The same video should
show the burned-in speed continuing to update while the app is backgrounded,
since that is what the location service type is for.

The app requests **no** storage or other restricted permissions. If the console
shows any beyond the list above, something regressed — check the merged release
manifest:

```bash
grep -o 'android:name="android.permission[^"]*"' \
  android/app/build/intermediates/merged_manifests/release/processReleaseManifest/AndroidManifest.xml \
  | sort -u
```

### Release format

App Bundle (`.aab`) via `eas build --platform android --profile production`.

A local `./gradlew assembleRelease` APK is signed with the real release key —
[`plugins/withAndroidSigning.js`](../plugins/withAndroidSigning.js) injects the
`LoopCam.keystore` signing config on every prebuild — so it is fine for
sideloading and internal testing. Play still wants the `.aab`, so upload that.

---

## App Store

### App privacy

One data type applies: **Location → Precise Location**.

- Used for: **App Functionality** only
- Linked to the user's identity? → **No**
- Used for tracking? → **No**

Nothing else. No contact info, no identifiers, no usage data, no diagnostics.

The video and audio recordings themselves are *not* declared: Apple's definition
covers data transmitted off the device or collected by the developer, and
recordings never leave the app's container unless the user shares them. Precise
location is declared because it is retained on the device inside saved files,
which Apple treats as collection regardless of whether anything is uploaded.

### Export compliance

`ITSAppUsesNonExemptEncryption` is set to `false` in `app.json`, so the upload
should not prompt. The app uses no encryption beyond what the OS provides.

### App Review notes

> DashCam is a rolling-buffer dash camera. It records video **with an audio
> track** into a short rolling buffer so the driver can save the last few minutes
> after an incident.
>
> The `audio` background mode is declared because the app keeps an active
> `AVAudioSession` recording audio while the device is locked or the app is
> backgrounded during a drive — that audio is written into the saved clips. This
> is the app's core function, not a background task unrelated to audio.
>
> The `location` background mode is declared for the same reason. The app draws
> the driver's current speed into every recorded frame, read from CoreLocation's
> Doppler speed once a second. Recording continues with the screen locked, so
> location updates must too — without the background mode every frame recorded
> after the screen locks would stamp "‑‑" instead of a speed, in the part of a
> drive most likely to matter. Location is read only while a recording session
> is active, never when the app is idle, and `ACCESS_BACKGROUND`-style
> always-on authorization is not requested.
>
> The app does not transmit any data off the device. There is no account, no
> analytics, and no server. Location is used only to burn the speed into the
> video and to write a JSON file next to each saved clip; both stay in the app's
> container. GPS can be switched off entirely under Settings → GPS speed, and
> refusing the location permission leaves the app fully functional. Saved clips
> remain in the app's container until the user shares them via the system share
> sheet or deletes them.
>
> To test:
> 1. Tap Play to start the rolling buffer. The buffer counter begins advancing.
> 2. Wait about 60 seconds, then tap Save. The clip is written without
>    interrupting recording.
> 3. Open the saved-clips list (folder icon) to play, share, lock, or delete it.
> 4. While recording, lock the device — a Live Activity appears on the Lock
>    Screen with working Save and Stop buttons.
> 5. Tap Stop to discard the buffer.
>
> Note: on iOS the app keeps the screen awake while recording ("driving mode"),
> since iOS provides no equivalent to Android's foreground camera service.

### Known review risk

Guideline 2.5.4 — using the `audio` and `location` background modes. Both
declarations are honest here (audio genuinely records in the background and
lands in saved clips; location genuinely feeds the speed burned into those same
frames), but camera apps do attract scrutiny and `location` attracts more of it
than `audio`. If review pushes back specifically on `location`, the narrow
fallback is to drop that one entry and accept that the speed reads `‑‑` while
the screen is locked — the recording itself is unaffected. The broader fallback
is to drop `UIBackgroundModes` entirely and state plainly in the listing that
iOS recording requires the app to stay foregrounded; the README already
documents this as an iOS platform limitation rather than a bug.

---

## Listing copy risk

"DashCam - Traffic" is 18 characters, within Play's 30-character title limit.
The trailing descriptor is keyword-adjacent; if App Store review raises guideline
2.3.7, rename the app to "DashCam" and move "Traffic" into the subtitle.
