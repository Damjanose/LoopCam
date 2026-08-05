# Store Release Readiness Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Get DashCam - Traffic (LoopCam) to a state where a signed release build can be submitted to both the App Store and Google Play without policy rejections or visibly broken features.

**Architecture:** Three phases, executed in order. Phase A is configuration only — permissions are cut down to what the code actually does, and the missing release-build scaffolding (`eas.json`, version fields, team ID, privacy manifest) is added. Phase B closes the three functional gaps that a reviewer or a real user would hit within one drive: iOS clips reporting 0:00, clip protection that evaporates on restart, and a storage budget that is declared but never enforced. Phase C is branding/metadata cleanup plus an end-to-end release-build verification on both platforms.

**Tech Stack:** Expo SDK 57 (`~57.0.10`), React Native 0.86, TypeScript, local Expo Module `modules/loopcam-recorder` (Kotlin/CameraX on Android, Swift/AVFoundation on iOS), `@bacons/apple-targets` for the iOS Live Activity widget, EAS Build for signing.

---

## Decisions locked in before writing this plan

1. **Location/GPS tagging is cut from v1.** The sidecar is an empty `TODO(phase-5)` on both platforms, so every location permission, purpose string and background mode is a declared-but-unused capability — the single largest rejection risk on both stores. All of it is removed. `locationTaggingEnabled` stays in the config type (harmless, unused) so re-adding the feature in 1.1 is a pure addition.
2. **iOS keeps `UIBackgroundModes: ["audio"]`.** The app genuinely records an audio track into saved clips, so the declaration is defensible. It is justified in App Review notes and in onboarding copy (Task 12).
3. **v1 scope for unfinished features:** storage budget + warnings, iOS clip duration, persisted clip protection. Camera flip stays a TODO and does **not** block release.

## Testing approach — read this first

This repo has **no test framework**: no jest, no native test targets, no `npm test` script. Adding one for a codebase that is ~90% native camera plumbing is not justified by this release, so the usual red/green/refactor loop does not apply literally here.

**Each task therefore substitutes a concrete, checkable verification step for the failing test:** a command with expected output (build succeeds, grep finds/doesn't find a line, `expo-doctor` passes) or, for native behaviour changes, a numbered on-device checklist. Do not skip these — they are the only evidence the change worked. Where a verification is device-only, run it before committing and record the result in the commit body.

Two commands recur throughout:

```bash
# Regenerate native projects after ANY app.json change. android/ and ios/ are
# gitignored prebuild output — never hand-edit them, the edit will be lost.
npx expo prebuild --clean

# The Android release build is the fastest full-stack smoke test we have.
cd android && ./gradlew :app:assembleRelease
```

## File Structure

**Configuration (Phase A)**

| File | Responsibility | Change |
| --- | --- | --- |
| `app.json` | Single source of truth for both native projects | Modify — versions, team ID, encryption flag, permission list, blocked permissions, privacy manifest |
| `eas.json` | Build + submit profiles and signing | **Create** |
| `package.json` | Dependency pinning | Modify — bump `expo` to `~57.0.10` |
| `modules/loopcam-recorder/android/src/main/AndroidManifest.xml` | Module-contributed permissions + service declaration | Modify — drop location permissions and the `location` foreground-service type |

**Native feature work (Phase B)**

| File | Responsibility | Change |
| --- | --- | --- |
| `modules/loopcam-recorder/android/.../StorageManager.kt` | §7.1 paths, §7.2 budget | Modify — protection markers, `enforceBudget()` |
| `modules/loopcam-recorder/android/.../LoopcamRecorderModule.kt` | JS bridge | Modify — persist protection, enforce budget on save, emit `onStorageWarning` |
| `modules/loopcam-recorder/ios/StorageManager.swift` | Same, iOS | Modify — protection markers, `enforceBudget()`, `durationSec(at:)` |
| `modules/loopcam-recorder/ios/LoopcamRecorderModule.swift` | JS bridge | Modify — same three changes as Android |
| `modules/loopcam-recorder/src/LoopcamRecorder.types.ts` | Shared contract | Modify — `onStorageWarning` payload gains `deletedClipIds` |
| `src/hooks/useRecorder.ts` | Mirrors native state into UI | Modify — subscribe to `onStorageWarning` |
| `src/screens/SavedClipsScreen.tsx` | Saved-clips gallery | Modify — lock toggle + protected badge, refresh on auto-delete |

Android and iOS deliberately mirror each other file-for-file (see README). **Every Phase B change must land on both platforms in the same task** — a divergence here is how the two engines drift apart.

---

# Phase A — Configuration and permissions

### Task 1: Config hygiene and version fields

Removes the invalid `newArchEnabled` key, pins the patch version `expo-doctor` is asking for, and adds the four fields with no defaults you can ship twice: `versionCode`, `buildNumber`, `appleTeamId`, `usesNonExemptEncryption`.

`newArchEnabled` is not a valid top-level key in the SDK 57 config schema (verified against https://docs.expo.dev/versions/v57.0.0/config/app/). The new architecture is on by default in SDK 57 and `android/gradle.properties` already carries `newArchEnabled=true` from the template, so removing the key changes nothing about the build.

**Files:**
- Modify: `app.json:1-10` (top-level block), `app.json:10-29` (ios), `app.json:30-51` (android)
- Modify: `package.json:6`

- [ ] **Step 1: Record the current failure**

Run: `npx expo-doctor`
Expected: 2 failures — "should NOT have additional property 'newArchEnabled'" and "expo expected ~57.0.10 found 57.0.9".

- [ ] **Step 2: Bump expo to the required patch**

```bash
npx expo install expo@~57.0.10
```

- [ ] **Step 3: Remove `newArchEnabled` from `app.json`**

Delete this line from the top-level `expo` block:

```json
    "newArchEnabled": true,
```

- [ ] **Step 4: Add the iOS release fields**

In `app.json`, the `ios` block gains `buildNumber` and `config`. Place them immediately after `"supportsTablet": false`:

```json
    "ios": {
      "supportsTablet": false,
      "bundleIdentifier": "com.damjano.dashcam",
      "buildNumber": "1",
      "appleTeamId": "REPLACE_WITH_TEAM_ID",
      "config": {
        "usesNonExemptEncryption": false
      },
```

`appleTeamId` is mandatory, not optional: `@bacons/apple-targets` cannot sign the Live Activity extension without it (this is called out in `README.md`). Get it from https://developer.apple.com/account → Membership Details → Team ID (a 10-character string like `A1B2C3D4E5`). **If you do not have it, stop and ask — do not invent a placeholder that reaches a build.**

`usesNonExemptEncryption: false` is correct here: the app makes no network calls and uses no encryption beyond what the OS provides. Without it, every single App Store Connect upload stalls on the export-compliance question.

- [ ] **Step 5: Add the Android version code**

In the `android` block, immediately after `"package"`:

```json
      "package": "com.damjano.dashcam",
      "versionCode": 1,
```

- [ ] **Step 6: Regenerate and verify**

```bash
npx expo prebuild --clean
npx expo-doctor
```

Expected: `expo-doctor` reports **15/15 checks passed** (or "No issues detected"). Zero failures.

- [ ] **Step 7: Verify the fields landed in the native projects**

```bash
grep -n "versionCode\|versionName" android/app/build.gradle
grep -n -A1 "CFBundleVersion\|ITSAppUsesNonExemptEncryption" ios/DashCamTraffic/Info.plist
```

Expected: `versionCode 1`, `versionName "1.0.0"`, `CFBundleVersion` = `1`, and `ITSAppUsesNonExemptEncryption` present as `<false/>`.

- [ ] **Step 8: Commit**

```bash
git add app.json package.json package-lock.json
git commit -m "chore: add release version fields, drop invalid newArchEnabled key"
```

---

### Task 2: Strip location from Android

Every Android location permission is removed, along with the `location` foreground-service type. Nothing in the Kotlin sources reads a location — `ClipMerger.writeMetadataSidecar` is an empty `TODO(phase-5)` (`ClipMerger.kt:212-217`) and `RecordingService.foregroundTypes()` only ever asks for `camera|microphone` (`RecordingService.kt:153-159`), so this removes zero functionality.

**Files:**
- Modify: `app.json:39-50` (android.permissions)
- Modify: `modules/loopcam-recorder/android/src/main/AndroidManifest.xml:12-19,29`
- Modify: `modules/loopcam-recorder/android/.../LoopcamRecorderModule.kt:72-73` (stale TODO)

- [ ] **Step 1: Confirm nothing reads location**

```bash
grep -rn "FusedLocation\|LocationManager\|ACCESS_FINE_LOCATION" modules/loopcam-recorder/android/src
```

Expected: only the manifest lines and the `TODO(phase-2)` comment. **If this returns real implementation code, stop — the premise of this task is wrong and the plan needs revisiting.**

- [ ] **Step 2: Trim `android.permissions` in `app.json`**

Replace the whole `permissions` array with:

```json
      "permissions": [
        "android.permission.CAMERA",
        "android.permission.RECORD_AUDIO",
        "android.permission.POST_NOTIFICATIONS",
        "android.permission.FOREGROUND_SERVICE",
        "android.permission.FOREGROUND_SERVICE_CAMERA",
        "android.permission.FOREGROUND_SERVICE_MICROPHONE"
      ],
```

Removed: both `ACCESS_*_LOCATION`, `FOREGROUND_SERVICE_LOCATION`, and `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`. The last one is never requested anywhere in the code and is a Play policy-restricted permission that would need its own justification form — cutting it is free.

- [ ] **Step 3: Trim the module manifest**

In `modules/loopcam-recorder/android/src/main/AndroidManifest.xml`, delete these blocks:

```xml
  <uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION" />

  <!-- §8 — GPS/speed sidecar on saved clips. -->
  <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
  <uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />

  <!-- §5.1 — prompt to exempt from battery optimization; aggressive OEM battery
       managers kill the service otherwise. -->
  <uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />
```

and change the service declaration's type:

```xml
    <service
      android:name=".RecordingService"
      android:exported="false"
      android:foregroundServiceType="camera|microphone" />
```

- [ ] **Step 4: Update the stale TODO**

In `LoopcamRecorderModule.kt`, replace the comment at line 72-73:

```kotlin
      // Location is deliberately not requested: the GPS sidecar (§7.1) is not in
      // this release, and a permission the app never uses is a Play policy
      // problem. Re-add ACCESS_FINE_LOCATION here together with the sidecar.
```

- [ ] **Step 5: Regenerate and verify the merged release manifest**

```bash
npx expo prebuild --clean -p android
cd android && ./gradlew :app:assembleRelease && cd ..
grep -o 'android:name="android.permission[^"]*"' \
  android/app/build/intermediates/merged_manifests/release/processReleaseManifest/AndroidManifest.xml \
  | sort -u
```

Expected: **no** `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, `FOREGROUND_SERVICE_LOCATION`, or `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` in the output. (`SYSTEM_ALERT_WINDOW` and the two `EXTERNAL_STORAGE` entries are still expected at this point — Task 3 removes them.)

- [ ] **Step 6: Commit**

```bash
git add app.json modules/loopcam-recorder/android/src/main/AndroidManifest.xml \
        modules/loopcam-recorder/android/src/main/java/expo/modules/loopcamrecorder/LoopcamRecorderModule.kt
git commit -m "fix: remove unused location and battery-optimization permissions on Android"
```

---

### Task 3: Block the Expo template's leftover Android permissions

`android/app/src/main/AndroidManifest.xml` is prebuild output, and the Expo template contributes `SYSTEM_ALERT_WINDOW` plus `READ/WRITE_EXTERNAL_STORAGE` (maxSdk 32) to it. All three survive into the **release** merged manifest. Play surfaces `SYSTEM_ALERT_WINDOW` as "Display over other apps" and asks for a justification the app has none for; the storage permissions are dead weight because `StorageManager` writes exclusively to app-private external storage (`StorageManager.kt:21-27`).

Because the file is generated, the fix is `android.blockedPermissions` in `app.json`, not an edit to the manifest.

**Files:**
- Modify: `app.json` (android block)

- [ ] **Step 1: Add `blockedPermissions`**

In the `android` block, directly after the `permissions` array:

```json
      "blockedPermissions": [
        "android.permission.SYSTEM_ALERT_WINDOW",
        "android.permission.READ_EXTERNAL_STORAGE",
        "android.permission.WRITE_EXTERNAL_STORAGE"
      ],
```

- [ ] **Step 2: Regenerate, rebuild, verify**

```bash
npx expo prebuild --clean -p android
cd android && ./gradlew :app:assembleRelease && cd ..
grep -o 'android:name="android.permission[^"]*"' \
  android/app/build/intermediates/merged_manifests/release/processReleaseManifest/AndroidManifest.xml \
  | sort -u
```

Expected final permission set — exactly these, nothing more:

```
ACCESS_NETWORK_STATE   (RN core)
CAMERA
FOREGROUND_SERVICE
FOREGROUND_SERVICE_CAMERA
FOREGROUND_SERVICE_MICROPHONE
INTERNET               (RN core / Metro; keep)
POST_NOTIFICATIONS
RECEIVE_BOOT_COMPLETED (androidx.work)
RECORD_AUDIO
VIBRATE
WAKE_LOCK              (androidx.work)
```

- [ ] **Step 3: Smoke-test the debug build on a device**

The blocked permissions matter for the dev menu, so confirm nothing broke:

```bash
npx expo run:android --device
```

Checklist: app launches; Play starts recording; the foreground-service notification appears; Save produces a clip; Stop clears the buffer.

- [ ] **Step 4: Commit**

```bash
git add app.json
git commit -m "fix: block template-contributed SYSTEM_ALERT_WINDOW and storage permissions"
```

---

### Task 4: Strip location from iOS and add the privacy manifest

Two changes to `app.json`'s `infoPlist`, plus the `PrivacyInfo.xcprivacy` Apple now requires.

`UIBackgroundModes: ["location"]` with no CoreLocation code is a guideline 2.5.4 rejection. `NSPhotoLibraryAddUsageDescription` is also unused — export goes through the `expo-sharing` share sheet (`SavedClipsScreen.tsx:123-130`), which needs no photo-library entitlement from this app. `["audio"]` **stays**, per the locked-in decision.

There is currently no `.xcprivacy` file anywhere in `ios/`, while the code calls required-reason APIs: file modification timestamps (`LoopcamRecorderModule.swift:249-251`, `StorageManager.kt` equivalents) and available disk space (`StorageManager.swift:56-65`). Uploads are rejected at processing time without declared reasons.

**Files:**
- Modify: `app.json:18-28` (ios.infoPlist)
- Modify: `app.json` (ios block — add `privacyManifests`)

- [ ] **Step 1: Replace the `infoPlist` block**

```json
      "infoPlist": {
        "NSCameraUsageDescription": "DashCam records a rolling buffer of the road ahead so you can save the last few minutes when something happens.",
        "NSMicrophoneUsageDescription": "DashCam records audio alongside video so saved incidents include sound.",
        "UIBackgroundModes": [
          "audio"
        ],
        "UIRequiresFullScreen": true
      },
```

- [ ] **Step 2: Add the privacy manifest**

In the `ios` block, after `infoPlist`:

```json
      "privacyManifests": {
        "NSPrivacyAccessedAPITypes": [
          {
            "NSPrivacyAccessedAPIType": "NSPrivacyAccessedAPICategoryFileTimestamp",
            "NSPrivacyAccessedAPITypeReasons": ["C617.1"]
          },
          {
            "NSPrivacyAccessedAPIType": "NSPrivacyAccessedAPICategoryDiskSpace",
            "NSPrivacyAccessedAPITypeReasons": ["E174.1"]
          }
        ],
        "NSPrivacyCollectedDataTypes": [],
        "NSPrivacyTracking": false
      }
```

Reason codes, and why these are the honest ones:
- `C617.1` — timestamps of files inside the app's own container. That is exactly what `contentModificationDateKey` is read for: sorting the saved-clips gallery.
- `E174.1` — checking available space before writing, to avoid failing a write. That is `storageStatus()`'s only purpose.
- No data leaves the device, so `NSPrivacyCollectedDataTypes` is empty and `NSPrivacyTracking` is false.

- [ ] **Step 3: Regenerate and verify the file exists**

```bash
npx expo prebuild --clean -p ios
find ios -name "*.xcprivacy" -not -path "*/Pods/*"
grep -c "NSPrivacyAccessedAPICategoryFileTimestamp" ios/DashCamTraffic/PrivacyInfo.xcprivacy
```

Expected: the file exists at `ios/DashCamTraffic/PrivacyInfo.xcprivacy` and the grep count is `1`.

- [ ] **Step 4: Verify location is gone from the generated Info.plist**

```bash
grep -n "NSLocation\|UIBackgroundModes" -A4 ios/DashCamTraffic/Info.plist
```

Expected: no `NSLocation*` keys at all; `UIBackgroundModes` contains only `audio`.

- [ ] **Step 5: Commit**

```bash
git add app.json
git commit -m "fix: drop unused iOS location/photo permissions, add privacy manifest"
```

---

### Task 5: Add `eas.json` and release signing

There is no `eas.json`, and `android/app/build.gradle:110-115` signs the release variant with the **debug** keystore. The release APK built during the audit is therefore unshippable. EAS owns credentials for both platforms.

**Files:**
- Create: `eas.json`

- [ ] **Step 1: Create `eas.json`**

```json
{
  "cli": {
    "version": ">= 12.0.0",
    "appVersionSource": "remote"
  },
  "build": {
    "development": {
      "developmentClient": true,
      "distribution": "internal",
      "channel": "development"
    },
    "preview": {
      "distribution": "internal",
      "channel": "preview",
      "android": {
        "buildType": "apk"
      }
    },
    "production": {
      "channel": "production",
      "autoIncrement": true,
      "android": {
        "buildType": "app-bundle"
      }
    }
  },
  "submit": {
    "production": {
      "ios": {
        "appleId": "damjanoda@gmail.com",
        "ascAppId": "REPLACE_WITH_APP_STORE_CONNECT_APP_ID",
        "appleTeamId": "REPLACE_WITH_TEAM_ID"
      },
      "android": {
        "track": "internal"
      }
    }
  }
}
```

Notes for whoever runs this:
- `appVersionSource: "remote"` + `autoIncrement` means EAS owns `versionCode`/`buildNumber` from here on. The values added in Task 1 are the starting point only; **stop hand-editing them** once this lands.
- `buildType: "app-bundle"` because Play requires an AAB for new apps.
- `ascAppId` comes from App Store Connect after the app record is created — it is the numeric ID in the app's URL.
- Android submission also needs a Google Play service-account JSON; add `"serviceAccountKeyPath": "./play-service-account.json"` under the android submit block and **add that filename to `.gitignore`** (the existing `*.jks`/`*.p8` rules do not cover it).

- [ ] **Step 2: Validate the config**

```bash
npx eas-cli@latest build:configure --platform all
```

Expected: EAS validates `eas.json` and reports the project is configured. It may add an `extra.eas.projectId` to `app.json` — keep that change.

- [ ] **Step 3: Generate release credentials**

```bash
npx eas-cli@latest credentials
```

Android: let EAS generate and store a new upload keystore (choose "Set up a new keystore"). **Back it up** — losing it means losing the ability to update the app on Play without a key reset request.
iOS: let EAS manage the distribution certificate and provisioning profiles. Confirm it creates profiles for **both** bundle IDs: `com.damjano.dashcam` and the widget extension's (`com.damjano.dashcam.LoopCamWidget` or whatever `@bacons/apple-targets` generated — check with `grep -rn "PRODUCT_BUNDLE_IDENTIFIER" ios/DashCamTraffic.xcodeproj/project.pbxproj`).

- [ ] **Step 4: Commit**

```bash
git add eas.json app.json .gitignore
git commit -m "build: add EAS build and submit profiles"
```

---

# Phase B — Close the functional gaps

### Task 6: Real clip durations on iOS

`LoopcamRecorderModule.swift:239-240` hardcodes `durationSec: 0`, so every saved clip in the gallery renders `00:00` (`SavedClipsScreen.tsx:235`). Android already does this correctly via `MediaMetadataRetriever` (`LoopcamRecorderModule.kt:282-294`); this mirrors that behaviour with `AVAsset`.

The `loadSync` bridge already exists in `ClipMerger.swift:147-160` for exactly this problem (AVFoundation's property loading is async-only from iOS 16). It is `private`, so rather than widening its visibility, `StorageManager` gets its own small copy next to the other file-inspection helpers — that keeps duration reading with the rest of the disk-facing code.

**Files:**
- Modify: `modules/loopcam-recorder/ios/StorageManager.swift` (add `durationSec(at:)` next to `fileSize(at:)`)
- Modify: `modules/loopcam-recorder/ios/LoopcamRecorderModule.swift:239-240`

- [ ] **Step 1: Add the duration reader to `StorageManager.swift`**

Add `import AVFoundation` at the top of the file, then insert after `fileSize(at:)` (line 67-70):

```swift
  /// The container is the only honest source for how long a saved clip runs:
  /// the merge concatenates a variable number of segments and the last one is
  /// cut short by the Save itself, so no arithmetic over the config predicts
  /// it. A file that cannot be read reports 0 rather than guessing.
  ///
  /// Called from `listSavedClips`, which is already off the main thread, so
  /// blocking on the async loader here is safe.
  func durationSec(at url: URL) -> Double {
    let asset = AVURLAsset(url: url)
    let semaphore = DispatchSemaphore(value: 0)
    var seconds = 0.0
    Task {
      if let duration = try? await asset.load(.duration) {
        let value = CMTimeGetSeconds(duration)
        if value.isFinite, value > 0 { seconds = value }
      }
      semaphore.signal()
    }
    semaphore.wait()
    return seconds
  }
```

- [ ] **Step 2: Use it in the module**

In `LoopcamRecorderModule.swift`, replace:

```swift
          // TODO(phase-3): read the real duration from AVAsset.
          durationSec: 0,
```

with:

```swift
          durationSec: storage.durationSec(at: url),
```

- [ ] **Step 3: Verify on a device**

```bash
npx expo run:ios --device
```

Checklist:
1. Press Play, wait ~60 s, press Save, press Stop.
2. Open the saved-clips gallery.
3. The new row shows a **non-zero** duration roughly matching the buffer length (not `00:00`).
4. Tap the row — playback length matches the number shown.

- [ ] **Step 4: Commit**

```bash
git add modules/loopcam-recorder/ios/StorageManager.swift \
        modules/loopcam-recorder/ios/LoopcamRecorderModule.swift
git commit -m "fix: read real clip duration from AVAsset on iOS"
```

---

### Task 7: Persist clip protection on both platforms

Protection is an in-memory `Set` on both platforms (`LoopcamRecorderModule.kt:28,146-150`, `LoopcamRecorderModule.swift:20,112-119`), so it is lost on every process death — and the UI never exposes it at all, so today it is unreachable dead code. Task 8 makes protection load-bearing (it decides what auto-delete may remove), so it has to be real first.

The storage design already states that "the saved directory *is* the index — no database to fall out of sync with disk" (`LoopcamRecorderModule.kt:251-254`). Protection follows the same rule: an empty `<clipname>.protected` marker file next to the `.mp4`. No new persistence layer, survives restarts, and orphan markers are harmless.

**Files:**
- Modify: `modules/loopcam-recorder/android/.../StorageManager.kt` (marker helpers)
- Modify: `modules/loopcam-recorder/android/.../LoopcamRecorderModule.kt:28,140-150,255-274`
- Modify: `modules/loopcam-recorder/ios/StorageManager.swift` (marker helpers)
- Modify: `modules/loopcam-recorder/ios/LoopcamRecorderModule.swift:20,104-119,228-245`

- [ ] **Step 1: Android — add marker helpers to `StorageManager.kt`**

Insert after `metadataFileFor` (line 51-52):

```kotlin
  /**
   * §7.2 — protection is a marker file rather than app state: the saved
   * directory is the index, and a flag that lives only in memory silently
   * un-protects every clip on process death, which is exactly when the
   * budget sweep is most likely to run.
   */
  fun protectionMarkerFor(video: File): File =
    File(video.parentFile, video.nameWithoutExtension + ".protected")

  fun isProtected(video: File): Boolean = protectionMarkerFor(video).exists()

  fun setProtected(video: File, isProtected: Boolean) {
    val marker = protectionMarkerFor(video)
    if (isProtected) {
      if (!marker.exists()) marker.createNewFile()
    } else {
      marker.delete()
    }
  }
```

- [ ] **Step 2: Android — use them in the module**

Delete the `private val protectedIds = mutableSetOf<String>()` field (line 28).

Replace the `setClipProtected` function (lines 146-150):

```kotlin
    AsyncFunction("setClipProtected") { id: String, isProtected: Boolean ->
      savedClips().firstOrNull { it.id == id }?.let { clip ->
        storage.setProtected(File(URI(clip.uri)), isProtected)
      }
    }
```

In `savedClips()`, replace the `isProtected` line (line 270):

```kotlin
        isProtected = storage.isProtected(file),
```

And in `deleteSavedClip` (lines 139-144), remove the marker too so it does not outlive its clip:

```kotlin
    AsyncFunction("deleteSavedClip") { id: String ->
      savedClips().firstOrNull { it.id == id }?.let { clip ->
        val video = File(URI(clip.uri))
        storage.protectionMarkerFor(video).delete()
        video.delete()
        clip.metadataUri?.let { File(URI(it)).delete() }
      }
    }
```

- [ ] **Step 3: iOS — add marker helpers to `StorageManager.swift`**

Insert after `metadataURL(for:)` (line 31-33):

```swift
  /// §7.2 — protection is a marker file rather than app state; see the Android
  /// counterpart for the reasoning. Both platforms must agree, since the
  /// budget sweep reads this to decide what it may delete.
  func protectionMarkerURL(for video: URL) -> URL {
    video.deletingPathExtension().appendingPathExtension("protected")
  }

  func isProtected(_ video: URL) -> Bool {
    fm.fileExists(atPath: protectionMarkerURL(for: video).path)
  }

  func setProtected(_ video: URL, _ isProtected: Bool) {
    let marker = protectionMarkerURL(for: video)
    if isProtected {
      if !fm.fileExists(atPath: marker.path) {
        fm.createFile(atPath: marker.path, contents: nil)
      }
    } else {
      try? fm.removeItem(at: marker)
    }
  }
```

- [ ] **Step 4: iOS — use them in the module**

Delete the `private var protectedIds = Set<String>()` field (line 20).

Replace `setClipProtected` (lines 112-119):

```swift
    AsyncFunction("setClipProtected") { (id: String, isProtected: Bool) in
      guard let clip = self.savedClips().first(where: { $0.id == id }) else { return }
      self.storage.setProtected(clip.url, isProtected)
    }
```

In `savedClips()`, replace `isProtected: protectedIds.contains(id)` with:

```swift
          isProtected: storage.isProtected(url),
```

In `deleteSavedClip`, also remove the marker:

```swift
    AsyncFunction("deleteSavedClip") { (id: String) in
      guard let clip = self.savedClips().first(where: { $0.id == id }) else { return }
      try? FileManager.default.removeItem(at: self.storage.protectionMarkerURL(for: clip.url))
      try? FileManager.default.removeItem(at: clip.url)
      if let metadataURL = clip.metadataURL {
        try? FileManager.default.removeItem(at: metadataURL)
      }
    }
```

- [ ] **Step 5: Make sure the marker never shows up as a clip**

Both `savedClips()` implementations already filter to `.mp4` (`LoopcamRecorderModule.kt:259`, and the iOS equivalent). Confirm:

```bash
grep -n 'extension == "mp4"' modules/loopcam-recorder/android/src/main/java/expo/modules/loopcamrecorder/LoopcamRecorderModule.kt
grep -n "pathExtension" modules/loopcam-recorder/ios/LoopcamRecorderModule.swift
```

Expected: both list only `.mp4` files. If the iOS side does not filter by extension, add `where url.pathExtension == "mp4"` before proceeding — otherwise markers become phantom rows in the gallery.

- [ ] **Step 6: Add the UI toggle**

Protection is unreachable without one. In `src/screens/SavedClipsScreen.tsx`, add a lock button to each row. `onLongPress` is already taken by delete, so this is a separate pressable inside the row, before the chevron:

```tsx
const toggleProtected = useCallback(async (clip: SavedClip) => {
  // Optimistic: the write is a marker file, and a failure re-syncs on the
  // next load() anyway.
  setClips((current) =>
    current?.map((c) => (c.id === clip.id ? { ...c, protected: !c.protected } : c)),
  );
  try {
    await LoopcamRecorder.setClipProtected(clip.id, !clip.protected);
  } catch {
    void load();
  }
}, [load]);
```

In `renderItem`, immediately before `<Text style={styles.rowChevron}>›</Text>`:

```tsx
                <Pressable
                  accessibilityRole="switch"
                  accessibilityState={{ checked: item.protected }}
                  accessibilityLabel={
                    item.protected ? 'Unprotect this clip' : 'Protect this clip from auto-delete'
                  }
                  hitSlop={12}
                  onPress={() => void toggleProtected(item)}>
                  <Text style={[styles.rowLock, item.protected && styles.rowLockOn]}>
                    {item.protected ? '🔒' : '🔓'}
                  </Text>
                </Pressable>
```

And in `StyleSheet.create`:

```tsx
  rowLock: { fontSize: 16, opacity: 0.35, paddingHorizontal: 6 },
  rowLockOn: { opacity: 1 },
```

- [ ] **Step 7: Typecheck**

Run: `npx tsc --noEmit`
Expected: no output (clean).

- [ ] **Step 8: Verify on both platforms**

Run on a device (`npx expo run:android --device`, then `npx expo run:ios --device`) and for each:
1. Save a clip, open the gallery, tap the lock — it fills in.
2. Force-quit the app and reopen the gallery — **the lock is still on**. (This is the whole point; before this change it would reset.)
3. Confirm the file exists: `adb shell run-as com.damjano.dashcam ls files/LoopCam/saved/` on Android should show a `.protected` file next to the `.mp4`.
4. Delete the clip — no orphan `.protected` file is left behind.
5. No phantom rows appear in the gallery.

- [ ] **Step 9: Commit**

```bash
git add modules/loopcam-recorder src/screens/SavedClipsScreen.tsx
git commit -m "feat: persist clip protection as a marker file, expose it in the gallery"
```

---

### Task 8: Enforce the storage budget and emit storage warnings

`SAVED_STORAGE_BUDGET_BYTES = 5 GB` and `SAVED_CLIP_COUNT_LIMIT = 50` are declared (`StorageManager.kt:90-92`, `StorageManager.swift:82-84`) and **never referenced anywhere**. `onStorageWarning` is registered as an event on both platforms and **never sent**. A dashcam that silently fills the device is the most predictable one-star review this app can earn.

Design: the sweep runs right after a successful save — the only moment saved storage grows, and already a natural place for I/O. It deletes oldest-first among unprotected clips until both the byte budget and the count limit are satisfied, then emits `onStorageWarning` if anything was deleted or free space is under the ~1 GB threshold.

Hooking it into the module's `onSaved`/`segmentControllerDidSave` covers saves from the JS button **and** from the notification/Live Activity, which both route through the same delegate call.

**Files:**
- Modify: `modules/loopcam-recorder/android/.../StorageManager.kt` (add `enforceBudget`)
- Modify: `modules/loopcam-recorder/android/.../LoopcamRecorderModule.kt:183` (`onSaved`)
- Modify: `modules/loopcam-recorder/ios/StorageManager.swift` (add `enforceBudget`)
- Modify: `modules/loopcam-recorder/ios/LoopcamRecorderModule.swift:160-163` (`segmentControllerDidSave`)
- Modify: `modules/loopcam-recorder/src/LoopcamRecorder.types.ts:87`
- Modify: `src/hooks/useRecorder.ts:52-60`
- Modify: `src/screens/SavedClipsScreen.tsx`

- [ ] **Step 1: Android — add `enforceBudget` to `StorageManager.kt`**

Insert before `storageStatus` (line 75):

```kotlin
  /**
   * §7.2 — the budget sweep. Oldest-first among unprotected clips until both
   * the byte budget and the count limit are met. Returns the ids removed, so
   * the caller can tell JS which rows just vanished from under it.
   *
   * Protected clips are counted against the budget but never deleted: a user
   * who locks 5 GB of footage has told us to stop reclaiming space, and
   * silently ignoring that would be worse than running out.
   */
  fun enforceBudget(): List<String> {
    val files = savedRoot.listFiles { f: File -> f.extension == "mp4" } ?: return emptyList()
    var totalBytes = files.sumOf { it.length() }
    var count = files.size
    val removed = mutableListOf<String>()

    val candidates = files.filterNot { isProtected(it) }.sortedBy { it.lastModified() }
    for (file in candidates) {
      if (totalBytes <= SAVED_STORAGE_BUDGET_BYTES && count <= SAVED_CLIP_COUNT_LIMIT) break
      val size = file.length()
      val id = file.nameWithoutExtension
      if (!file.delete()) continue
      metadataFileFor(file).delete()
      protectionMarkerFor(file).delete()
      totalBytes -= size
      count -= 1
      removed += id
    }
    return removed
  }
```

- [ ] **Step 2: Android — run the sweep on save**

In `LoopcamRecorderModule.kt`, replace `onSaved` (line 183):

```kotlin
  override fun onSaved(clip: SavedClip) {
    sendEvent("onSaved", clip.toMap())
    // The only moment saved storage grows, so the only moment the budget can
    // be breached (§7.2).
    val deleted = storage.enforceBudget()
    val clips = savedClips()
    val status = storage.storageStatus(clips.size, clips.sumOf { it.sizeBytes })
    if (deleted.isNotEmpty() || status.lowSpaceWarning) {
      sendEvent(
        "onStorageWarning",
        status.toMap() + mapOf("deletedClipIds" to deleted),
      )
    }
  }
```

- [ ] **Step 3: iOS — add `enforceBudget` to `StorageManager.swift`**

Insert before `storageStatus` (line 56):

```swift
  /// §7.2 — the budget sweep. Mirrors the Android implementation exactly:
  /// oldest-first among unprotected clips until both the byte budget and the
  /// count limit are met. Returns the ids removed.
  func enforceBudget() -> [String] {
    guard let urls = try? fm.contentsOfDirectory(
      at: savedRoot,
      includingPropertiesForKeys: [.contentModificationDateKey, .fileSizeKey]
    ) else { return [] }

    let clips = urls.filter { $0.pathExtension == "mp4" }
    var totalBytes = clips.reduce(Int64(0)) { $0 + fileSize(at: $1) }
    var count = clips.count
    var removed: [String] = []

    let candidates = clips
      .filter { !isProtected($0) }
      .sorted { modifiedAt($0) < modifiedAt($1) }

    for url in candidates {
      if totalBytes <= Self.savedStorageBudgetBytes, count <= Self.savedClipCountLimit { break }
      let size = fileSize(at: url)
      guard (try? fm.removeItem(at: url)) != nil else { continue }
      try? fm.removeItem(at: metadataURL(for: url))
      try? fm.removeItem(at: protectionMarkerURL(for: url))
      totalBytes -= size
      count -= 1
      removed.append(url.deletingPathExtension().lastPathComponent)
    }
    return removed
  }

  func modifiedAt(_ url: URL) -> Date {
    (try? url.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate)
      ?? Date.distantPast
  }
```

Note: `LoopcamRecorderModule.swift` has its own `private func modifiedAt` (line 248-251). Now that `StorageManager` owns one, **delete the module's copy** and route its `savedClips()` through `storage.modifiedAt(...)` — two implementations of the same thing on the same file is how sort order and sweep order drift apart.

- [ ] **Step 4: iOS — run the sweep on save**

Replace `segmentControllerDidSave` (lines 160-163):

```swift
  func segmentControllerDidSave(_ clip: SavedClip) {
    sendEvent("onSaved", clip.asDictionary() as [String: Any])
    LiveActivityBridge.post(LiveActivityBridge.update, controller.status, banner: "Clip saved")

    // The only moment saved storage grows, so the only moment the budget can
    // be breached (§7.2).
    let deleted = storage.enforceBudget()
    let clips = savedClips()
    let status = storage.storageStatus(
      savedClipCount: clips.count,
      savedBytes: clips.reduce(0) { $0 + $1.sizeBytes }
    )
    if !deleted.isEmpty || status.lowSpaceWarning {
      var payload = status.asDictionary()
      payload["deletedClipIds"] = deleted
      sendEvent("onStorageWarning", payload)
    }
  }
```

- [ ] **Step 5: Update the shared type**

In `modules/loopcam-recorder/src/LoopcamRecorder.types.ts`, replace the `onStorageWarning` line (87):

```ts
  /**
   * Fired after a save when the budget sweep removed clips (§7.2) or free
   * space fell under the warning threshold. `deletedClipIds` is what the
   * gallery has to drop.
   */
  onStorageWarning: (payload: StorageStatus & { deletedClipIds: string[] }) => void;
```

- [ ] **Step 6: Surface it in the recorder hook**

In `src/hooks/useRecorder.ts`, add state and a subscription:

```ts
  const [storageWarning, setStorageWarning] = useState<string | null>(null);
```

Inside the `useEffect` subscription array (lines 53-58):

```ts
      LoopcamRecorder.addListener('onStorageWarning', ({ deletedClipIds, lowSpaceWarning }) => {
        if (deletedClipIds.length > 0) {
          setStorageWarning(
            `Storage full — removed ${deletedClipIds.length} old clip${
              deletedClipIds.length === 1 ? '' : 's'
            }. Lock a clip to keep it.`,
          );
        } else if (lowSpaceWarning) {
          setStorageWarning('Running low on storage.');
        }
      }),
```

Return `storageWarning` from the hook (add it to the returned object alongside `error`), and render it in `RecorderScreen.tsx` wherever `error` is currently shown — same treatment, it is the same class of transient notice.

- [ ] **Step 7: Keep the gallery honest**

In `src/screens/SavedClipsScreen.tsx`, subscribe so rows deleted by the sweep disappear while the gallery is open:

```tsx
  useEffect(() => {
    const subscription = LoopcamRecorder.addListener('onStorageWarning', ({ deletedClipIds }) => {
      if (deletedClipIds.length === 0) return;
      setClips((current) => current?.filter((clip) => !deletedClipIds.includes(clip.id)) ?? null);
    });
    return () => subscription.remove();
  }, []);
```

- [ ] **Step 8: Typecheck and build**

```bash
npx tsc --noEmit
cd android && ./gradlew :app:assembleRelease && cd ..
```

Expected: `tsc` silent, Gradle `BUILD SUCCESSFUL`.

- [ ] **Step 9: Verify the sweep with a lowered budget**

5 GB is impractical to hit by hand, so test with a temporary constant, then restore it. On Android, set `SAVED_STORAGE_BUDGET_BYTES = 50_000_000L` and `SAVED_CLIP_COUNT_LIMIT = 3` in `StorageManager.kt`, rebuild, then:

1. Save 4 clips.
2. **Expected:** the gallery drops to 3, oldest gone; the recorder screen shows the "removed old clips" notice.
3. Lock the oldest surviving clip, save 2 more.
4. **Expected:** the locked clip is still there; unlocked ones were evicted instead.
5. Lock all 3 and save again.
6. **Expected:** nothing is deleted, the app does not crash, and the new clip still saves.
7. Repeat 1-6 on iOS with the same temporary values in `StorageManager.swift`.
8. **Restore both constants to `5_000_000_000` / `50`** and confirm with `git diff` that no test values survive.

- [ ] **Step 10: Commit**

```bash
git add modules/loopcam-recorder src/hooks/useRecorder.ts \
        src/screens/SavedClipsScreen.tsx src/screens/RecorderScreen.tsx
git commit -m "feat: enforce the §7.2 storage budget and emit storage warnings"
```

---

# Phase C — Branding, metadata, and release verification

### Task 9: Consistent product name

The app ships as "DashCam - Traffic" but the Android foreground-service notification says "LoopCam is recording" (`RecordingService.kt`), which is what a user sees on the lock screen for an entire drive. LoopCam is the internal/repo name; it should not be user-visible.

**Files:**
- Modify: `modules/loopcam-recorder/android/.../RecordingService.kt` (notification title, channel name)
- Audit: `targets/widget/*.swift`, `src/**` for other user-visible "LoopCam" strings

- [ ] **Step 1: Find every user-visible occurrence**

```bash
grep -rn "LoopCam" src targets modules/loopcam-recorder/android/src modules/loopcam-recorder/ios \
  | grep -vi "loopcamrecorder\|LoopCamActivity\|LoopCamLiveActivity\|LoopCamWidget\|package\|import\|§"
```

Review each hit and decide: identifier (leave) vs. string a user reads (change to "DashCam").

- [ ] **Step 2: Fix the notification copy**

In `RecordingService.kt`, `setContentTitle("LoopCam is recording")` → `setContentTitle("DashCam is recording")`. Do the same for the notification channel name and any `Log`-adjacent user-facing strings. Keep `TAG` values as-is — those are developer-facing.

- [ ] **Step 3: Verify on device**

Run `npx expo run:android --device`, press Play, pull down the shade. The notification reads "DashCam is recording" with the buffer clock beneath it.

- [ ] **Step 4: Commit**

```bash
git add modules/loopcam-recorder/android/src/main/java/expo/modules/loopcamrecorder/RecordingService.kt
git commit -m "fix: use the shipping product name in user-visible strings"
```

---

### Task 10: Privacy policy and store metadata

Both stores require a reachable privacy policy URL for any app that touches camera and microphone. There is none.

**Files:**
- Create: `docs/privacy-policy.md` (source for the hosted page)

- [ ] **Step 1: Write the policy**

Create `docs/privacy-policy.md`. The honest version is short, because the app genuinely collects nothing:

- What is recorded: video and audio, stored **only** in the app's private storage on the device.
- What leaves the device: **nothing**. No analytics, no accounts, no network transmission of footage. The only way footage leaves is the user explicitly using the system share sheet.
- Retention: the rolling buffer is discarded continuously; saved clips stay until the user deletes them or the 5 GB budget evicts the oldest unprotected ones.
- Permissions and why: camera (recording), microphone (audio track), notifications (the foreground-service recording notice).
- Deletion: uninstalling the app removes everything; individual clips are deleted in-app.
- Contact: damjanoda@gmail.com.

- [ ] **Step 2: Host it**

Publish at a stable public URL (GitHub Pages off this repo is sufficient). Verify it loads in a browser with no login.

- [ ] **Step 3: Prepare the store listings**

Google Play — Data safety form: "No data collected", "No data shared". Declare the **foreground service types** (camera, microphone) with a short justification and a screen recording showing the rolling-buffer recording continuing with the app backgrounded.

App Store Connect — App Privacy: "Data Not Collected". Under Age Rating and category, "Utilities" or "Travel" fits.

Check the app name against Play's title rules: "DashCam - Traffic" is 18 characters (limit 30) and is fine, but keyword-ish suffixes can attract App Store guideline 2.3.7 attention. If review pushes back, "DashCam" with the descriptor moved to the subtitle is the safe fallback.

- [ ] **Step 4: Write the App Review notes**

This is where the `UIBackgroundModes: ["audio"]` decision gets defended. Draft, to paste into App Store Connect:

> DashCam is a rolling-buffer dash camera. It records video **with an audio track** into a short rolling buffer so the driver can save the last few minutes after an incident. The `audio` background mode is declared because the app keeps an active `AVAudioSession` recording audio while the device is locked or the app is backgrounded during a drive — that audio is written into the saved clips.
>
> The app does not request location and does not transmit any data off the device. Saved clips remain in the app's container until the user shares or deletes them.
>
> To test: tap Play to start the buffer, wait ~60 seconds, tap Save. The saved clip appears in the gallery (folder icon). A Live Activity with Save and Stop buttons appears on the Lock Screen while recording.

- [ ] **Step 5: Commit**

```bash
git add docs/privacy-policy.md
git commit -m "docs: add privacy policy for store submission"
```

---

### Task 11: Verify release builds on both platforms

The iOS release path **has never been compiled** — `ios/Pods` does not exist and there is no `.xcworkspace`. Everything above is theoretical until both platforms produce a signed artifact.

- [ ] **Step 1: Clean regenerate**

```bash
npx expo prebuild --clean
```

Expected: both `android/` and `ios/` are regenerated with no plugin errors. Watch specifically for `[loopcam-live-activity]` throws — that plugin fails loudly if `AppDelegate` or `targets/widget/Shared` doesn't match what it expects.

- [ ] **Step 2: iOS — install pods and build Release**

```bash
cd ios && pod install && cd ..
xcodebuild -workspace ios/DashCamTraffic.xcworkspace \
  -scheme DashCamTraffic \
  -configuration Release \
  -sdk iphonesimulator \
  CODE_SIGNING_ALLOWED=NO \
  build 2>&1 | tail -30
```

Expected: `** BUILD SUCCEEDED **`. This is the first real check that the `expo-modules-jsi` patch, the widget extension, and the Live Activity shared sources all compile in Release. **If this fails, stop and fix it — nothing downstream matters until it passes.**

- [ ] **Step 3: Android — release bundle**

```bash
cd android && ./gradlew :app:bundleRelease && cd ..
ls -la android/app/build/outputs/bundle/release/
```

Expected: `app-release.aab` exists.

- [ ] **Step 4: EAS production builds**

```bash
npx eas-cli@latest build --platform android --profile production
npx eas-cli@latest build --platform ios --profile production
```

Expected: both succeed with EAS-managed credentials. The iOS build is the one that proves the widget extension signs — the failure the README warns about surfaces here if `appleTeamId` is wrong.

- [ ] **Step 5: Install and smoke-test the production builds**

On a real Android device and a real iPhone (not simulators — the camera is fake there and Live Activities need real hardware):

1. Fresh install. Permission prompts appear for camera, mic, notifications — **and nothing else**. No location prompt anywhere.
2. Play → recording starts, buffer clock advances.
3. Background the app for 2 minutes. Android: the notification persists and the buffer keeps growing. iOS: the Lock Screen Live Activity is present with Save and Stop.
4. Save from the notification (Android) and from the Live Activity (iOS). Both produce a clip.
5. Gallery: clip shows a real duration on **both** platforms, plays, shares, locks, deletes.
6. Force-quit and reopen: the lock survives; no orphaned temp session remains.
7. Stop → buffer cleared.

- [ ] **Step 6: Final audit sweep**

```bash
npx expo-doctor
npx tsc --noEmit
grep -o 'android:name="android.permission[^"]*"' \
  android/app/build/intermediates/merged_manifests/release/processReleaseManifest/AndroidManifest.xml | sort -u
grep -n "NSLocation\|UIBackgroundModes" -A4 ios/DashCamTraffic/Info.plist
grep -rn "TODO(phase" modules/loopcam-recorder src
```

Expected: doctor clean, tsc clean, no location permissions on either platform, and the only remaining `TODO(phase-N)` markers are the deliberately-deferred ones (camera flip, location sidecar, impact detection). If a TODO shows up in code paths this plan touched, it did not get finished.

- [ ] **Step 7: Tag the release**

```bash
git tag -a v1.0.0 -m "v1.0.0 — first store submission"
git push origin main --tags
```

---

## Explicitly out of scope for v1

Deferred deliberately; do **not** let these expand the release:

- **Camera flip** (`LoopcamRecorderView.kt:41`, `.swift:48`) — a missing convenience, not a defect.
- **Location/GPS sidecar** (§7.1) — cut per the decision above; ships in 1.1 with its permissions re-added *together with* the implementation.
- **Impact detection** (§8) — `impactDetectionEnabled` exists in the config type but nothing reads the accelerometer.
- **`autoStopBatteryPercent`** (§6) — config-only. iOS enables battery monitoring (`LoopcamRecorderModule.swift:32`) but no code acts on a threshold on either platform.

Both are safe to defer because **neither is exposed in the UI** — verified: `grep -rn "impactDetection\|autoStopBattery\|locationTagging" src/` returns nothing. They are inert config fields, not lying switches. If a settings screen is added before submission, it must not surface any of the three.
