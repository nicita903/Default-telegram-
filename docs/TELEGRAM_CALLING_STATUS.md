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
