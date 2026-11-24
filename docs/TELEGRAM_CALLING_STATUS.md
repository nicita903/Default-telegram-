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
- Minimal call UI exposes Answer/Decline, Mute, audio route and End; no final floating-call UI or video capture is implemented.

## Runtime evidence (2026-11-24)

An arm64 build was installed on Samsung SM-S9310 / Android API 36. The native engine emitted `Established`; Android reported the microphone foreground service and `MODE_IN_COMMUNICATION`. The user confirmed both participants could hear each other between Default and the official Telegram client for the user-authorized private contact. Mute/Unmute UI state changed during the call. Following hangup, the call service stopped, audio returned to `MODE_NORMAL`, and RECORD_AUDIO was no longer marked running. This is a live two-way-audio result, not a claim based only on compilation.

A compact-control clipping issue found during that test was fixed before the final build. A subsequent incoming call was displayed in Default, answered through the Samsung UI, reached native Established, and the user again confirmed both audio directions. After the call, RECORD_AUDIO was not running and the foreground service was absent. Local hangup, precise mute attenuation, speaker switching and Bluetooth still need dedicated repeatable audible checks; the mute/unmute UI transition alone is not proof of attenuation.

## Remaining limitations

Bluetooth headset routing, all older Android versions/ABIs, network interruptions, incoming calls after process death and sustained background behavior require additional device tests. Incoming call state is process-wide and independent of chat screens, but a native incoming CallStyle notification/ringtone/full-screen/Telecom integration is not included in this voice-first phase. An open Default UI can answer an incoming call. Force-stopped Android apps or unavailable Telegram push cannot be promised ringing. No video calling is implemented. The reference engine's traffic-stat getter returns empty counters, so these are not presented as two-way-audio evidence.
