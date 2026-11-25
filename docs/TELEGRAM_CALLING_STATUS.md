# Telegram voice calling

## Current implementation

Default now builds `libdefault_calls.so` from pinned source and connects its own JNI bridge to the existing TDLib client. Video calling remains disabled. No separate Telegram client or database is created.

- Telegram X build reference: `54884b053f9a243111d6af18de415c6770d56291`.
- tgcalls: TGX-Android production revision `a90b7a8e9e24e14735b19806d08b08045fdb6be6`, derived from the official Telegram calling engine.
- Matching WebRTC: `51b8a449a66f6966f7c7dc814e885f3c2d516712`.
- Registered engine: `InstanceV2ReferenceImpl`, versions `10.0.0` and `11.0.0`, maximum protocol layer 92. The JNI exports actual engine capabilities; Kotlin advertises the registered engine's conservative layer and chooses a common negotiated version.
- Existing TDLib: 1.8.70 at `c8828d50f5eac2fc7089ce1089d7ce9095c61b5e`. Its documented JSON call/signaling APIs match this bridge. Interoperability is determined by the advertised engine versions, not an assumed tgcalls/TDLib version number equivalence.
- Android NDK r28c (`28.2.13676358`), CMake >=3.22.1, Ninja, Android API 29, arm64-v8a. Current native build was completed with CMake 4.3.1. Other ABIs are not packaged.

## Reproduce

First build the project's existing reviewed TDLib/OpenSSL recipe. With MSYS2 and Git POSIX utilities installed:

```powershell
.\scripts\Build-CallingAndroid.ps1 -MsysRoot 'E:\programs\mysys' -Jobs 8
.\gradlew.bat :app:assembleDebug
```

The recipe fetches exact source/dependency pins from `native/calling/sources.json`, builds libvpx and the tgcalls/WebRTC stack, and copies a stripped arm64 library to `app/build/generated/callingJni`. It records the packaged SHA-256 in `native/calling-manifest.json`. Gradle verifies this independently of the unchanged TDLib provenance task. A clean Gradle build needs the calling recipe rerun to regenerate the native package. WebRTC Java sources and the three matching Telegram X enum classes come from the pinned checkout.

The wrapper exposes pinned Opus/libyuv/libvpx headers in WebRTC's expected source layout and uses the pinned Chromium DSP/JNI-export files. Disabled software H264 decoding does not link FFmpeg. C++20 and narrowly scoped upstream warning adjustments are needed for NDK r28c; warnings remain visible. No third-party precompiled calling binary is used. Source builds and notices remain available through the recipe and pins; LGPLv3/GPLv3 and individual dependency license obligations apply to redistribution.

## Signaling, media and lifecycle

- `createCall`, `acceptCall`, `discardCall`, `updateCall`, `sendCallSignalingData`, `updateNewCallSignalingData` use the existing authenticated manager, with account/attempt guards.
- Negotiated key (256 bytes), direction, relay/TURN credentials, P2P permission and custom parameters are handed to tgcalls. Kotlin clears its key byte array. No custom cryptography, media recording, persistent call keys or unencrypted fallback is added.
- Active is entered only for native `Established`, never for TDLib `callStateReady`. The timer begins at that callback.
- A guarded native handle registry prevents operations on released handles. Stop closes the engine and releases it off engine threads after completion. Signaling payloads and logs are not persisted; native debug logging is disabled after engine construction.
- Microphone permission is requested from user actions. Android communication audio mode/focus, earpiece/speaker and optional Bluetooth routing are coordinated by one controller. Existing audio playback stops before the call. A non-exported microphone foreground service exists only for a started/answered call and provides an End action.
- Borderless call UI exposes Answer/Decline, Mute, audio route, End, and an in-app minimized indicator. Video capture is not implemented.

## Runtime evidence (2026-11-24)

An arm64 build was installed on Samsung SM-S9310 / Android API 36. The native engine emitted `Established`; Android reported the microphone foreground service and `MODE_IN_COMMUNICATION`. The user confirmed both participants could hear each other between Default and the official Telegram client for the user-authorized private contact. Mute/Unmute UI state changed during the call. Following hangup, the call service stopped, audio returned to `MODE_NORMAL`, and RECORD_AUDIO was no longer marked running. This is a live two-way-audio result, not a claim based only on compilation.

A compact-control clipping issue found during that test was fixed before the final build. A subsequent incoming call was displayed in Default, answered through the Samsung UI, reached native Established, and the user again confirmed both audio directions. After the call, RECORD_AUDIO was not running and the foreground service was absent. Local hangup, precise mute attenuation, speaker switching and Bluetooth still need dedicated repeatable audible checks; the mute/unmute UI transition alone is not proof of attenuation.

## Remaining limitations

Bluetooth headset routing, all older Android versions/ABIs, network interruptions, incoming calls after process death and sustained background behavior require additional device tests. Incoming call state is process-wide and independent of chat screens, but a native incoming CallStyle notification/ringtone/full-screen/Telecom integration is not included in this voice-first phase. An open Default UI can answer an incoming call. Force-stopped Android apps or unavailable Telegram push cannot be promised ringing. No video calling is implemented. The reference engine's traffic-stat getter returns empty counters, so these are not presented as two-way-audio evidence.

## Security verification update (2026-11-25)

The installed pinned TDLib JSON schema explicitly defines `callStateReady.emojis` as four encryption fingerprint emoji. Default now copies exactly these four strings into call-scoped `CallSecurityUiState`, alongside the call ID and negotiated/media-initialized/media-established stage. It validates the count and nonempty bounded strings; absent or invalid data produces no emoji indicator. No fingerprint algorithm or placeholder is added. The UI only shows the row for the matching active/reconnecting call; tapping opens the existing floating blur/modal system with comparison instructions and a Close icon. Opening it does not mark anyone verified.

Call signaling, TDLib Ready, native initialization, and native Established remain distinct. The main call state becomes Active only on the matching native Established callback. Verification state is cleared synchronously on release, failure, hangup, account/session replacement, and a new call. No key enters Compose state. Protocol version must intersect the registered engine versions, and the remote layer range must include the exported native layer. Only a decoded 256-byte key reaches the bridge. There is no unencrypted fallback.

### Findings and fixes

- Media initialization previously awaited option requests inline without rechecking the call, allowing local hangup/account changes during suspension to leave stale initialization work. It now runs in a cancellable call-specific job so incoming terminal updates remain processable, and rechecks account, epoch, attempt and call ID immediately before creating native media. Release cancels that job.
- TDLib HangingUp previously changed only the UI phase. It now immediately releases local media/audio/service resources. Discarded and Error also invalidate the attempt. Stale outgoing request failures cannot terminate a replacement call.
- Native server-ID conversion could throw across JNI; nativeCreate now validates versions, layer, timeouts, server count/address/port and numeric IDs, catches C++ exceptions, and publishes a registry handle only after engine creation succeeds. Null inputs and key length are checked. JNI reference allocation and signaling-copy exceptions are checked.
- Session closing is atomic for native callback checks. Callbacks hold a shared Session (protecting the Java GlobalRef), use WebRTC's managed JVM attachment and clear callback exceptions. A callback already in flight at stop may still enqueue an event; Kotlin ignores its released handle and wipes signaling bytes, including discarded events. No pointer-valued Java handles or handle reuse are introduced. Stop remains idempotent and engine destruction stays off engine callback threads.
- The bridge-owned shared key allocation now uses OpenSSL `OPENSSL_cleanse` in its final-owner deleter. This does not erase a key while tgcalls still owns it. Kotlin key arrays and consumed/discarded mutable signaling buffers are cleared in finally paths. No persistent call-key storage or sensitive parameter logging was found in the reviewed calling path. Native logging remains disabled; the debug Established diagnostic contains no key or participant data.
- Unexpected foreground-service destruction ends only its matching call attempt. Explicit release still stops the service, abandons audio focus and restores Android routing/mode. Activity recreation alone intentionally preserves an ongoing call managed by the application singleton and foreground service; task removal ends it. No audio recording or additional microphone permission was added.

### Connection privacy

Privacy now exposes Telegram's actual `userPrivacySettingAllowPeerToPeerCalls` default rule through `getUserPrivacySettingRules` and `setUserPrivacySettingRules`. Existing per-user/chat and other exception objects remain in order; Contacts includes a restrictive fallback. Changes occur only after an explicit user choice, with a fresh conflict check and a post-write server read; external rule updates trigger refresh. Telegram has no atomic compare-and-set for these rules, so concurrent edits on another client during the write window cannot be excluded. This control changes no Last Seen, Ghost Mode or call-acceptance setting. Relay-only defaults can still have retained allow exceptions; the UI reports the exception count. The media engine continues to obey TDLib `allow_p2p` AND the negotiated `udp_p2p` flag. Relay services can observe connection metadata; this is not anonymity.

### Verification and limits

Only the changed JNI bridge was compiled and relinked successfully; pinned tgcalls/WebRTC source and encryption algorithms are unchanged. The packaged native hash is updated in `native/calling-manifest.json`. `:app:assembleDebug` passed; the final incremental build took 43s after including the last privacy-rule normalization edit. Gradle packaged the already-stripped JNI library unchanged. No additional test suites ran.

The user requested build-only verification and declined live calls for this update. No new APK installation, two-client emoji comparison, second-call comparison, forced-failure test, runtime microphone/service cleanup check, routing check, or race stress test was performed. Earlier two-way-audio and post-hangup microphone observations above predate these changes and do not validate this update. Successful native/APK compilation cannot establish cryptographic correctness or runtime cleanup guarantees.

TDLib's immutable JSON/base64 strings, JVM/allocator copies, TDLib-owned secrets and internal tgcalls derived-key copies cannot be guaranteed erased by this bridge. This is a focused source review, not an independent cryptographic or native-memory security audit.

References: [pinned TDLib schema](https://github.com/tdlib/td/blob/c8828d50f5eac2fc7089ce1089d7ce9095c61b5e/td/generate/scheme/td_api.tl), [Telegram voice/video encryption](https://core.telegram.org/api/end-to-end/video-calls).

## Phase 2 stability update (2026-11-25)

### Verified source findings and implementation

- The registered v2 reference engine's `setNetworkType` is empty. Its Android platform inherited a null network-monitor factory, leaving WebRTC's interface enumeration without Android's native network callbacks/socket binding support. The build now compiles an Android platform factory adapter returning the pinned WebRTC `AndroidNetworkMonitorFactory`. Only the platform factory return expression is replaced in a generated copy; the dependency checkout and crypto/media algorithms remain unchanged. Existing application-context initialization and ACCESS_NETWORK_STATE permission are reused. No network binding of the whole process, forced mobile-network permission, new engine per handover, or custom reconnect algorithm is added. WebRTC catches denial of its optional mobile-network request and continues observing permitted networks.
- The engine already implements ICE failure recovery from the offerer side, throttled to at least five seconds, and a roughly twenty-second disconnected failure timeout. Its ICE disconnect reporting has a two-second grace period after WebRTC detects disconnection. These engine behaviors are retained. Network diagnostics do not declare media connected; only native Established restores Active and the existing timer origin. An initial native reconnecting callback maps to Connecting before the first Established event.
- A single suspended `sendCallSignalingData` previously blocked native Failed/Reconnecting handling, and the first signaling request error ended even a working media call. Signaling now has one call-owned bounded queue and sender job, separate from native-state reduction. Network failures receive at most three delayed retransmissions (1/2/4 seconds); permanent signaling errors still end the matching call. Exhausted transient messages are dropped rather than declaring media failure; the native engine determines recovery/fatal timeout. The queue, pending bytes, and job are canceled/cleared on termination. No polling or reconnect loop is added.
- An old notification's generic End intent could affect a replacement call. Foreground start and End intents now carry the attempt token, including a distinct PendingIntent identity. Canceled/stale starts stop themselves, stale End actions cannot end a different call, foreground-start exceptions invoke matching-call cleanup, and service destruction explicitly removes the ongoing notification. New incoming calls increment the attempt counter. A replacement outgoing call cannot overlap a still-running earlier create request or native wrapper release.
- Android route restoration failures could abort cleanup before abandoning audio focus. Cleanup now clears ownership first and isolates native stop, callback removal, route restoration, focus abandonment and service stop. Repeated cleanup does not repeat ownership release. Account changes and local/remote/native/TDLib terminal states still release only the individual call, never the shared TDLib client.
- JNI callback method lookups now check null/exception state before proceeding. Temporary native signaling bytes use an RAII clearing buffer even when an exception occurs. Native mute catches boundary exceptions and reports whether the current session accepted its queued request; the UI changes mute state only after acceptance. This is not an acknowledgment that the remote participant heard silence. The pinned engine applies mute via the outgoing audio track and retains that track/mute state during its ICE recovery.
- Native stop retains its guarded registry/shared Session ownership and asynchronous engine stop. A new released callback runs after destroying the wrapper off the media thread; Kotlin tracks outstanding releases and ignores media callbacks for released handles. The engine's internal destructor is itself queued on its media thread, so this callback is NOT proof that the Android microphone has already stopped. Native key final-owner clearing and existing call/account key association remain unchanged. Shared Session ownership keeps the listener GlobalRef alive for callbacks; weak callbacks cannot resurrect a destroyed Session.
- Audio-route observation moved from the full-screen UI to the existing call controller while audio focus is owned, so it continues when the call is minimized or the Activity is stopped. API 31+ observes the actual communication device; speaker toggling uses that device rather than a stale Boolean. A disconnected explicitly selected device clears its selection and requests wired/earpiece fallback when available. Legacy SCO uses its connected/disconnected broadcast instead of assuming startBluetoothSco succeeded. All listeners/receivers are removed on release. Native microphone mute is independent of route changes.
- Application-scoped call ownership and the existing microphone foreground service continue across Home, Activity recreation, screen lock and chat navigation when Android permits; no resume hook creates/reconnects an engine. Task removal still ends the call. Process death/new incoming delivery remains outside this phase.
- Debug diagnostics use fixed enum event names only (creation, initialization, media state/recovery, network changes, cleanup, route changes, service stop). They omit identifiers, peer names, IP addresses, signaling, keys and audio, and are disabled in non-debuggable builds.

### Focused verification / manual test preparation

Native bridge/platform compilation, JNI signature review and the focused Android build are the automated checks for this update. No sanitizer, native race stress, real-account call, microphone capture, or audio hardware test has been run for Phase 2. Previous live-call evidence predates these changes. The native target compiled and linked successfully, including the generated Android platform adapter. WebRTC dependencies were reused. The single focused `:app:assembleDebug` passed in 1m 12s. The native library is stripped and its packaged SHA-256 matches the reviewed manifest. No unrelated test suites ran.

| Manual scenario | Required observations (not yet performed) |
| --- | --- |
| Wi-Fi to mobile and mobile to Wi-Fi, both call directions | Same call/emoji fingerprint and timer origin; Reconnecting only from native media state; Established resumes audio; no second native engine |
| Temporary loss then restoration | Engine may recover within its timeout; compare audible audio in both directions, no invented Connected status |
| Loss beyond the engine timeout | Failed/Ended, service/notification gone, RECORD_AUDIO not running, audio mode/focus restored |
| Local or remote hangup while reconnecting | Immediate local stop request, verification cleared, queued signaling canceled; repeat End safely |
| Cancel before createCall completes, then start another call | No old-call signaling/notification/end action applied to the replacement |
| Mute/unmute before and after handover | Second participant confirms actual silence/restored audio; same mute state through recovery |
| Speaker, earpiece, wired insertion/removal, Bluetooth connection/disconnection | UI shows actual route, no unintended simultaneous output, private fallback where available, mute preserved |
| Home, another app, lock/unlock, Activity recreation, return | Same active native session, foreground service remains, timer/emoji preserved; no extra microphone capture |
| All termination paths and repeated End | After native teardown settles, no microphone app-op running, no call service/notification, no route/network callbacks retained |

Limitations: transition recovery depends on the remote client, ICE/server reachability, Android network policy and the engine timeout. Callback/registry inspection is not dynamic proof against every native race; memory pressure/thread-creation failure and OEM audio behavior remain untested. Native internal teardown is asynchronous, so runtime microphone/privacy-indicator cleanup must be observed on a device. Immutable JVM/TDLib strings and engine-owned key copies still cannot be guaranteed erased. Telegram encryption, privacy routing permission and fingerprint source are unchanged; no unencrypted fallback or call recording is introduced.

Changed files in this phase: `TelegramVoiceCalls.kt`, `TgCallsBridge.kt`, `VoiceCallService.kt`, `VoiceCallUi.kt`, new `CallDiagnostics.kt`, `native/calling/bridge.cpp`, `native/calling/CMakeLists.txt`, new `native/calling/NetworkAwareAndroidInterface.h`, `native/calling-manifest.json`, and this document.

Android API references: [network callbacks](https://developer.android.com/develop/connectivity/network-ops/reading-network-state), [communication-device routing](https://developer.android.com/reference/android/media/AudioManager#setCommunicationDevice(android.media.AudioDeviceInfo)).
