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
| Data collected | **None** |
| Data shared | **None** |
| Tracking | **No** |
| Account required | No |
| Privacy policy URL | *(fill in once hosted)* |

Verified in code: no network calls anywhere in `src/`, `modules/`, or
`targets/`; no analytics, crash-reporting, or advertising dependencies in
`package.json`; `expo.modules.updates.ENABLED=false` in the Android manifest.

---

## Google Play

### Data safety

- Does your app collect or share any of the required user data types? → **No**
- Is all user data encrypted in transit? → N/A (no data transmitted)
- Do you provide a way for users to request data deletion? → N/A (no data
  collected; recordings are deleted in-app or by uninstalling)

### Permissions declarations

| Permission | Declaration |
| --- | --- |
| `CAMERA` | Core functionality — recording the road ahead. |
| `RECORD_AUDIO` | Records the audio track included in saved clips. |
| `POST_NOTIFICATIONS` | Shows the ongoing recording notice, which also carries the Save and Stop controls. |
| `FOREGROUND_SERVICE_CAMERA` | Keeps the rolling buffer recording while the app is not on screen — the app is useless if recording stops when the driver leaves the screen. |
| `FOREGROUND_SERVICE_MICROPHONE` | Same, for the audio track. |

Play requires a **short video** demonstrating the foreground-service use.
Record: press Play, background the app, show the notification persisting and the
buffer counter advancing, then Save from the notification.

The app requests **no** location, storage, or restricted permissions. If the
console shows any beyond the list above, something regressed — check the merged
release manifest:

```bash
grep -o 'android:name="android.permission[^"]*"' \
  android/app/build/intermediates/merged_manifests/release/processReleaseManifest/AndroidManifest.xml \
  | sort -u
```

### Release format

App Bundle (`.aab`) via `eas build --platform android --profile production`.
Never upload the local `assembleRelease` APK — it is signed with the debug
keystore.

---

## App Store

### App privacy

Select **Data Not Collected**. No data types apply.

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
> The app does not request location access and does not transmit any data off the
> device. There is no account, no analytics, and no server. Saved clips remain in
> the app's container until the user shares them via the system share sheet or
> deletes them.
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

Guideline 2.5.4 — using the `audio` background mode. The declaration is honest
here (audio genuinely records in the background and lands in saved clips), but
camera apps do attract scrutiny. If review pushes back, the fallback is to drop
`UIBackgroundModes` entirely and state plainly in the listing that iOS recording
requires the app to stay foregrounded — the README already documents this as an
iOS platform limitation rather than a bug.

---

## Listing copy risk

"DashCam - Traffic" is 18 characters, within Play's 30-character title limit.
The trailing descriptor is keyword-adjacent; if App Store review raises guideline
2.3.7, rename the app to "DashCam" and move "Traffic" into the subtitle.
