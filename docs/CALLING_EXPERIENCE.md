# Call sounds, proximity and routes

Implementation uses the existing `VoiceCallState`, TDLib reducer, native Established callback,
AndroidX Core-Telecom session and `VoiceCallService`. No native/JNI, cryptography, authentication,
database paths or call-screen layout changes are required.

## Sound assets and ownership

`scripts/Generate-CallSounds.py` synthesizes seven original mono PCM 24 kHz WAV assets under
`app/src/main/res/raw/call_*.wav`. These new assets and their generator are dedicated to the
public domain under CC0-1.0 (https://creativecommons.org/publicdomain/zero/1.0/).
They contain no Telegram audio recordings or other third-party samples. Telegram-style refers
to state-dependent cadence and behavior, not identical Telegram recordings.

| Real event | Feedback |
| --- | --- |
| Outgoing call with registered foreground service and communication audio ready | One soft initiation cue |
| TDLib Pending with `is_received`, mapped to Ringing | Looping ringback |
| Exchanging keys / initial native media connection | Looping connecting cue |
| First native Established | One short connected cue; timer origin unchanged |
| Reconnecting after Established | No repeated connected cue or waiting loop |
| TDLib Declined before connection on outgoing call | One busy/declined signal |
| Other terminal state / local hangup | One ended cue |
| TDLib/native/platform failure after audio setup | One failure cue; actual error remains visible |

TDLib `callDiscardReasonDeclined` does not distinguish busy from a deliberate decline. The UI
therefore says "Contact is busy or declined the call." A disconnected transport uses failure
feedback. No ringback is generated merely from tapping Call, and a platform registration failure
cannot enable call audio. Early failures before audio setup do not stop the app's music or
acquire a separate sound focus session.

One worker owns a call-scoped, asynchronously loaded SoundPool with one stream. State changes
stop the old stream before playback. Native/reducer duplicate terminal updates cannot replay
the terminal cue. Pools and policy/mode listeners are released after termination (at most 850ms
for a one-shot tail; at most 1200ms while awaiting a terminal reason after HangingUp). The
process-owned worker then sleeps; no extra audio service or sensor poll remains.
Call effects honor the preference, ringtone/silent policy, DND, communication mode and voice-call
volume. Telecom interruption and mode/policy changes stop loops. Android may suppress a final
cue if it withdraws communication audio before playback; no media-focus fallback overrides it.

Incoming ringtone has exactly one owner: the existing Android incoming-call notification channel,
`telegram_incoming_calls_v1`. Android retains a user-configured channel ringtone or uses the
system ringtone default. The app creates no second ringtone player and does not bypass DND.
Answer changes the notification to its silent ongoing channel; decline, cancellation, timeout
and failure remove the call notification through existing cleanup.

## Proximity

The service owns a non-reference-counted `PROXIMITY_SCREEN_OFF_WAKE_LOCK`, only when both a
proximity sensor and that wake-lock level are supported, the preference is enabled, media is
Active/Reconnecting after native establishment, and Telecom reports the built-in earpiece.
Android monitors the sensor; no app sensor callbacks, polling, black overlay or brightness changes
are used. Speaker/headset/video/preference changes release immediately so those modes can use the
display. Call termination/service destruction use `RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY`: Android
keeps the near-ear screen off until the device moves away, without an app-held wake lock. Cleanup
is idempotent and runs while the app is backgrounded through the existing foreground service.

## Routing and focus

Only Core-Telecom selects audio endpoints. Android's reported endpoint drives the speaker icon
and proximity. Automatic routing prefers an available wired headset, Bluetooth, then earpiece;
speaker is a fallback on hardware without private output. In-app and observed Android route
selections are retained while available. Removed headsets fall back to actual available devices.
Turning speaker off restores the most recent available private endpoint, otherwise the preferred
available private output. A failed endpoint request does not change the UI to the requested route.
No `setCommunicationDevice`, SCO, mode or focus owner competes with Telecom. Existing shared-media
cleanup parks Music and stops voice/video playback after call audio setup, preserving queue and
position for manual resume. Media3 and Telecom retain Android audio-focus arbitration.

Settings -> Calls adds persisted Call sound effects and Proximity during earpiece calls (enabled
by default on supported phones). Incoming ringtone keeps its channel settings. Ringtone volume
opens Android sound settings. No unreliable persisted output selector is advertised.

## Registration and physical-device verification

No device was connected in `adb devices` during this change. No call, proximity, headset,
Bluetooth, ringtone, focus, background or sensor test has been performed for these changes.
Compilation and WAV inspection do not validate audible sound or hardware behavior.

The existing registration path registers Core-Telecom, enters its call scope, starts the legitimate
phoneCall foreground service, then completes readiness. Ongoing CallStyle is not posted ahead of
the foreground service. Existing `DefaultTelecom` diagnostics and visible setup errors are retained.
The previously reported "Android could not register this call" cannot be reproduced or ruled out
without a phone; do not treat sounds/sensors as validated until this preflight succeeds.

Install the newly built debug APK on a supported Android phone and use a consenting Telegram peer.
Before A-I, inspect `adb logcat -s DefaultTelecom DefaultVoiceCall` while placing a call. Require
`addCall.scopeReady`, `startForeground.complete` and native Established. If registration fails,
record the sanitized operation/exception/code and fix that failure before interpreting audio tests.

| Check | Procedure and required result |
| --- | --- |
| A - Outgoing | Call a peer; initiation once, real received/ringing ringback once, no overlap; stop on answer. |
| B - Established | Confirm two-way speech, one brief connected cue, timer starts at native Established; reconnect does not repeat it. |
| C - Earpiece | With no headset and speaker off, actual endpoint is earpiece and speech is private. On speaker-only hardware no earpiece is invented. |
| D - Proximity | Move to/from ear during an active earpiece call: display off/on, speech uninterrupted, no accidental touches. Disable preference and repeat. |
| E - Speaker | Switch speaker on/off; verify actual route, icon and immediate proximity disable; returning to earpiece enables it. |
| F - Headsets | Insert/remove wired and connect/disconnect Bluetooth during the call; check automatic/manual routes, speaker override retention and no proximity on headsets. |
| G - Near hangup | Remote hangup while covered: media stops; moving away restores screen; subsequent use has no proximity lock. |
| H - Failure | Test busy/decline, native/network failure, microphone denial and Telecom rejection; real error visible, loops stop, focus/service released. No simulated ringing after registration failure. |
| I - Background | Background/return during speech: same call persists, no repeated cues, proximity follows actual route; hangup removes service. |

Also test incoming ringtone exactly once in foreground/background/lock screen, configured custom
and default ringtone, silent mode, DND, ringtone volume zero, answer/decline/caller cancellation/
timeout; Music/voice/video interruption and manual resume; changing Calls settings during a call;
oldest supported Android plus Android 12+ and the target Android version. No unrelated test suites
or native rebuilds are required for this Kotlin-only change.

## Build verification

`./gradlew.bat :app:assembleDebug` succeeded in 1m 28s on the first build for this change.
All seven non-silent call WAVs are packaged in the APK. The packaged arm64 calling library still
matches the reviewed native manifest SHA-256. No native rebuild or unrelated test suite ran.
Physical-device checks above remain unperformed.

## Files changed

- `app/src/main/java/com/vector/messenger/calling/CallSoundController.kt` (new): call-scoped cue loading, playback, deduplication and cleanup.
- `app/src/main/java/com/vector/messenger/calling/CallProximityController.kt` (new): supported earpiece-only screen-off wake lock.
- `app/src/main/java/com/vector/messenger/calling/CallPreferences.kt` (new): persisted effects/proximity preferences.
- `app/src/main/java/com/vector/messenger/calling/VoiceCallService.kt`: owns resource lifecycle and reacts to call/settings updates.
- `app/src/main/java/com/vector/messenger/calling/TelegramVoiceCalls.kt`: audio-readiness gating, terminal feedback, real discard/error status and speaker return action.
- `app/src/main/java/com/vector/messenger/calling/TelegramCallPlatform.kt`: actual Telecom endpoint selection and observations.
- `app/src/main/java/com/vector/messenger/feature/SettingsWorkspace.kt`: Calls effects/proximity and system ringtone-volume controls.
- `app/src/main/AndroidManifest.xml`: WAKE_LOCK grant and optional proximity feature.
- `scripts/Generate-CallSounds.py` and seven `app/src/main/res/raw/call_*.wav` assets (new).
- `docs/TELEGRAM_CALLING_STATUS.md` and this report.

## References

- Official Telegram Android VoIP service (behavior reference; no source/assets copied):
  https://github.com/DrKLO/Telegram/blob/master/TMessagesProj/src/main/java/org/telegram/messenger/voip/VoIPService.java
- Android Core-Telecom endpoint control and call lifecycle:
  https://developer.android.com/develop/connectivity/telecom/voip-app/telecom
- Android PowerManager wake-lock levels and release behavior:
  https://developer.android.com/reference/android/os/PowerManager
  https://developer.android.com/reference/android/os/PowerManager.WakeLock
