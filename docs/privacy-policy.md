# Privacy Policy — DashCam - Traffic

**Effective date:** 5 August 2026
**Contact:** damjanoda@gmail.com

DashCam - Traffic ("the app") is a dash camera that records a rolling buffer of
video so you can save the last few minutes of footage after something happens on
the road.

## The short version

No account, no analytics, no crash reporting, no advertising, no tracking. The
app reads your location while it is recording, in order to stamp your speed onto
the footage and write it into a file beside each saved clip — but that stays on
your device like the video does. Nothing the app records leaves your device
unless you share it yourself.

## What the app records

When you press Play, the app records **video and audio** into a rolling buffer
held in the app's private storage on your device. The buffer keeps only the most
recent footage — older segments are continuously discarded as new ones are
written. Nothing is retained from a drive unless you press Save.

Pressing Save copies the current buffer into a permanent clip, also in the app's
private storage.

### Location and speed

While recording, and only while recording, the app also reads your device's GPS
position about once a second. Two things are done with it, both on the device:

- Your **speed** is drawn into the corner of each recorded frame, beside the
  clock. Once burned in, it is part of the video and cannot be removed.
- Your **position, speed and the time of each reading** are written into a small
  JSON file saved alongside each clip, so a saved incident can show where it
  happened.

This is switched on by default and can be turned off at any time under
**Settings → GPS speed → Record speed and position**. With it off, the app does
not request location access, does not start the location receiver, and the
footage carries the clock alone. Turning it off does not alter clips already
saved — a speed already burned into a video stays there.

Location is read only while a recording session is running. It is never read
when the app is idle, and the app never asks for background location access.

## Where recordings are stored

On the device only, inside the app's own private storage area:

- Android: the app's private external files directory
- iOS: the app's Documents container

These locations are not readable by other apps. Recordings are not uploaded,
backed up to any service operated by us, or transmitted anywhere.

## What leaves the device

Nothing, unless you choose to send it. The only way a recording leaves the app is
if you tap Share on a saved clip, which hands that single file to your device's
standard share sheet. Where it goes from there — messages, email, cloud storage —
is determined by the app you pick, and is governed by that app's privacy policy,
not this one.

The app makes no network requests of its own. It contains no analytics, tracking,
advertising, or crash-reporting code, and it has no server component.

## How long recordings are kept

- **Buffered footage** is discarded continuously as you drive and is deleted
  entirely when you press Stop.
- **Location readings** are held in memory only, for no longer than the buffer
  window itself, and are discarded when you press Stop. The only ones written to
  disk are those covering a clip you saved, in that clip's own file.
- **Saved clips** — and the location file beside each one — stay on your device
  until you delete them, with one exception:
  when saved clips exceed the app's storage budget (5 GB, or 50 clips), the app
  automatically deletes the oldest ones to make room. Clips you have locked using
  the lock control in the saved-clips list are never deleted automatically.
- Uninstalling the app removes all recordings, saved and buffered.

## Permissions and why they are needed

| Permission | Why |
| --- | --- |
| Camera | To record video. This is the app's core function. |
| Microphone | To record the audio track that accompanies saved clips. |
| Notifications (Android) | To display the ongoing recording notice required while the app records in the background, which also carries the Save and Stop controls. |
| Location (while using the app) | To read your speed and position while recording, for the burned-in speed stamp and the file saved beside each clip. Requested once, alongside camera and microphone; refusing it leaves the app fully working, with `‑‑` where the speed would be. |

The app does **not** request background location access. Location is read only
while a recording session is running.

On Android, the app runs a foreground service while recording so that the buffer
survives when the app is not on screen. On iOS, the app keeps an audio session
active while recording so that audio continues to be captured while the screen is
locked, and shows a Live Activity on the Lock Screen with Save and Stop controls.

## Children

The app is not directed at children and collects no personal information from
anyone, including children.

## Your rights over your data

Because all recordings and location readings stay on your device and we never
receive them, you retain
full control. Delete individual clips in the app, or uninstall the app to remove
everything. There is no data held by us to request, correct, or erase.

## Changes to this policy

If this policy changes, the updated version will be published at this address and
the effective date above will be revised.

## Contact

Questions about this policy: damjanoda@gmail.com
