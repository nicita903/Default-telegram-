# Voice messages and shared audio

The subsequent [offline Music implementation](OFFLINE_MUSIC.md) extends this same engine with a music queue and MediaSessionService. Voice/video retain the foreground-only behavior described here. Music is paused and its queue preserved during voice/video ownership; resumption is explicitly manual.

Implemented 2026-11-15. Default retains its current AMOLED/blue compact interface, authentication, encrypted TDLib database, chat synchronization, text composer, module history, Search recognition and Ghost settings. This work extends the media/download infrastructure and centralizes its existing Media3 player construction; no audio library or toolchain version was changed.

## Existing implementation inspected

| Component | Before this task | Action |
| --- | --- | --- |
| Media3 | Video viewer owned one ExoPlayer; audio focus/noisy handling and lifecycle pause/dispose already worked | Reused through a single SharedMediaPlayer factory/coordinator; no player per voice row |
| Downloads | Shared TDLib client, native file/index updates, validated private paths, pause/retry and native persistence | Reused for voice/audio download-before-play and upload progress metadata |
| Voice messages | Only text labels in real messages, no voice descriptor, recorder, permission flow or send method | Added supported mapping, capture and native send path |
| Composer | Existing text/reply/send and demo attachments | Added an isolated microphone and recording panel; text draft survives recording errors/permission denial |
| Ghost | Shared sendChatAction gate already covered recording-voice-note and upload-action types; no recorder emitted them | Recorder uses the same gateway. Only capability text was updated; policies/settings/rules are unchanged |
| Background audio | No playback service | Remains foreground-only: pause/release on stop; no unrequested service, notification or route forcing |

## Capture and gesture behavior

- A real touch must remain on the microphone for 180 ms before capture is requested. Releasing, adding another pointer, losing ownership or moving over 12 dp before activation cancels preparation.
- Once recording: release sends; dragging left 72 dp cancels; dragging up 64 dp locks. Cancel and lock are one-shot directional decisions. Locked release does not send; explicit Send/Cancel remains visible.
- A release while the native recorder is still preparing cancels it. Lock during preparation is remembered. Preparing, finalizing and submission states prevent duplicate sends.
- The microphone region and recording controls exclude module gestures. The waveform consumes only its own seek region. No competing full-screen recognizer was added.
- Accessibility activation starts locked recording, with explicit Send/Cancel. Permission acquisition never starts recording automatically; the user must activate again afterward.
- Runtime RECORD_AUDIO is the only new permission. Denial leaves text input usable and provides an explanation plus an Android app-settings action.
- Duration and thin blue amplitude bars reflect MediaRecorder.getMaxAmplitude samples. Final waveforms compress measured peaks into at most 100 five-bit samples. Incoming waveform bytes are decoded from Telegram; missing waveform data produces a neutral timeline, not invented/random audio bars.

Android MediaRecorder writes **mono Opus at 48 kHz / 32 kbps in an Ogg container** using platform APIs supported by the project's API-29 minimum. Capture preparation, amplitude reads, stop/release, rename and deletion run on IO under one recorder mutex. Recordings shorter than 800 ms are rejected; recording is capped at ten minutes. Silence remains silence in waveform data.

Audio-focus loss, Android input silencing, recorder errors, changed input route, leaving the conversation, Activity stop and ViewModel disposal cancel capture and clean up. An exclusive transient recording focus request is abandoned on release. No Bluetooth/wired/speaker route is forced. Calls and MODE_IN_COMMUNICATION block startup rather than competing for their microphone.

## Telegram integration and real delivery state

The installed reviewed TDLib pin is `c8828d50f5eac2fc7089ce1089d7ce9095c61b5e`. Its InputMessageVoiceNote contains a nested InputVoiceNote, rather than assuming an older flattened API:

```text
sendMessage
  input_message_content: inputMessageVoiceNote
    voice_note: inputVoiceNote
      voice_note: inputFileLocal
      duration: actual recorded seconds
      waveform: packed five-bit bytes (JSON Base64)
```

`TdlibMessagingClient.sendVoice` uses the existing manager and official sendMessage. `SessionChatRepository.sendVoice` applies live-session, conversation permission, input and duplicate-submission checks, then projects the returned **native** Message. Acceptance does not invent delivery: pending remains Sending; updateMessageSendSucceeded/Failed and existing ReadOutbox handling control the bubble. Failed accepted messages reuse the existing native resendMessages flow where can_retry/retry_after permit it. Network/timeout uncertainty is surfaced without automatic resubmission.

Real messageVoiceNote and messageAudio metadata now use the existing media domain. Remote uploaded_size/is_uploading_active are displayed where supplied, while updateFile drives download progress. Source duration, actual player duration and Telegram waveform data are retained. Existing restrictions/ephemeral filtering and export checks remain in force.

Reply IDs are forwarded using the existing inputMessageReplyToMessage shape. A confirmed native acceptance clears only the matching reply context; newly changed reply/text input is preserved.

## One shared playback owner

`SharedMediaPlayer` is the sole ExoPlayer.Builder call in production. Voice/audio playback and the existing video viewer acquire it; acquiring video stops/releases voice ownership first. Rows subscribe to distinct projections of the active item rather than allocating players or updating the entire conversation on every position sample. The old video controls continue to bind a PlayerView and release their lease on disposal. Test-only/unprovided surfaces can construct the same coordinator locally, preserving standalone component tests.

- Play/pause, duration, completion/replay, seeking and 1× / 1.5× / 2× playback speeds.
- Validated download-before-play through MediaRepository. Loading/error states are explicit; a bounded timeout ends waiting without polling file state. Dismissal, account changes and recording/video takeover prevent a stale pending request from starting audio.
- Compact active-item controller, position/progress, play/pause/stop and return-to-conversation. It is present only for a selected audio item; it is not a permanent navigation bar.
- Waveform tapping/dragging and accessibility setProgress seek the active message. Unknown audio envelopes remain a neutral timeline. Idle messages are activated through Play before seeking.
- Media3 manages playback focus using speech/media attributes and handles becoming-noisy events. Android selects the route. Pause/release occurs on app backgrounding; no automatic background resume or service is introduced.
- Playback clock work runs only while playing (100-ms samples). At most 16 voice positions are retained in memory, and media buffers reuse the existing bounded configuration. No decoding/recording work is performed per chat row.

## Privacy, file ownership and cancellation

Recording drafts are private in noBackupFilesDir/voice-recordings with UUID filenames. Explicit cancellation, interrupted/short recordings and pre-submission account transitions delete their own files. Abandoned drafts from a previous process are pruned when the recorder is initialized for use/cleanup. No broad storage permission, external audio analytics, additional network destination or recorded-content logging exists.

Before submission, a completed file is atomically moved to the existing private TDLib file area under outgoing-voice. Once a request is issued, it must not be deleted while native upload/retry may reference it. Accepted files become part of the TDLib cache; the app does not eagerly remove the source behind TDLib. A definitively rejected local draft can be retried or explicitly canceled and deleted. If the response is uncertain, the panel says so, offers Close rather than claiming cancellation, and retains the potentially uploading source for safety. This bounded-size file may remain in native storage even if no response is recovered; automatic orphan reconciliation for unknown outcomes is not implemented. Do not call that state “sent” or “canceled.”

The recording controller is testable core logic over VoiceCapture and the existing repositories; the Android ViewModel delegates to it. Cleanup jobs are ordered, await preceding cleanup before a new capture, and use the existing process cleanup scope so ViewModel cancellation cannot leave the microphone open. Finalization/handoff results are accounted for before cancellation cleanup. A regression exposed a session-mismatch exit which needed unconditional pre-submission cleanup; that path is fixed and tested.

The Ghost recording action is emitted every four seconds only while actual capture is active, and ChatActionCancel is emitted on stop. Every action passes through GhostRepository → TdlibGhostClient → the original manager's policy gate immediately before JNI. Suppressing activity does not suppress sendMessage or its upload. This does not promise invisibility in other clients, shared-group activity, server online exceptions or read receipts. Two-account observation is still required for live peer visibility.

## Verification

The focused Samsung SM-S9310 / Android 16 test passed with explicit user approval for a short local microphone check. It verified physical hold/left-cancel/up-lock controls, explicit Send to a **fake repository**, real OggS/mono audio/opus encoding, local Media3 playback, seek/pause/1.5× and transfer of ownership to the video engine. Recordings were deleted in test cleanup; no audio was sent to Telegram.

JVM tests cover five-bit encoding vectors, measured silence/normalization, cancel/lock/release decisions, preparation races, duplicate submissions, native acceptance, failed retry/discard, uncertainty, late-finalize cleanup, account transitions, lifecycle cancellation and Ghost action filtering without blocking actual submission. Messaging regression coverage checks real repository pending-state projection and logged-out rejection. Android mapping tests check the installed nested request shape, waveform roundtrip and upload metadata.

Final command:

```powershell
.\gradlew.bat :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:connectedDebugAndroidTest --max-workers=1 --console=plain
```

**BUILD SUCCESSFUL in 3m 45s. 107 JVM tests passed (73 core, 34 app), and all 90 Samsung instrumentation tests passed. Zero failures or skips.** This includes 11 new core voice tests, one new native-state messaging regression and two new Android voice/audio tests. Lint reports zero errors and 15 warnings: the prior 14 SDK/dependency/tool currency notices plus one optional UseKtx URI-style suggestion. `git diff --check` passed. Existing tests were retained; no failing assertion was removed. The cleanup regression was corrected to require one discarded owned file at either valid stage (private draft or already-renamed native staging), with no send, rather than assuming one race ordering.

The original 300000-ms screen timeout was restored and confirmed. The device then disconnected during microphone-permission cleanup; revocation is not confirmed. The user chose to finish without further device checks. If the app still has the temporary microphone grant, it can be reset in Android Settings → Apps → Default → Permissions → Microphone. Test recordings were deleted; the native tests never called Telegram sendMessage or changed live privacy rules. Local logs are ignored: `voice-checks.log`, `voice-device-tests.log`, `voice-final-checks.log`; the final APK is `app/build/outputs/apk/debug/app-debug.apk`. Machine-readable reports remain under `core/build/test-results/test`, `app/build/test-results/testDebugUnitTest` and `app/build/outputs/androidTest-results/connected`.

## Changed files in this phase

Paths are relative to the repository root. `A` means app/src/main/java/com/vector/messenger; `C` means core/src/main/kotlin/com/vector/core.

| Files | Purpose |
| --- | --- |
| `C/audio/Voice.kt`, `C/audio/VoiceRecordingController.kt` (new) | Descriptor, real waveform conversion, deterministic gestures and recording/send ownership |
| `C/Repositories.kt`, `C/messaging/TelegramMessaging.kt`, `C/messaging/SessionChatRepository.kt` | Voice send contracts and native-state projection; text send code retained |
| `C/media/TelegramMedia.kt` | Voice/audio kinds, waveform and native upload metadata |
| `A/telegram/TdlibVoiceMapping.kt` (new), `TdlibMessagingClient.kt`, `TdlibMediaMapping.kt` | Official pinned request and incoming voice/audio mapping |
| `A/feature/audio/VoiceRecorder.kt` (new) | Platform capture, focus/input interruptions, private file lifecycle |
| `A/feature/audio/VoiceViewModel.kt`, `AudioViewModel.kt` (new) | Lifecycle wrappers and download/playback coordination |
| `A/feature/audio/SharedMediaPlayer.kt`, `VoiceUi.kt` (new) | Shared Media3 owner, waveform, microphone/panel and floating controls |
| `A/feature/media/MediaPlayback.kt`, `MediaViewer.kt` | Reuse shared engine and show voice/audio items |
| `A/feature/ConversationScreen.kt`, `A/navigation/DefaultApp.kt` | Composer/bubble integration, active audio controller and return action |
| `A/MainActivity.kt`, `A/di/RepositoryModule.kt`, `A/design/VectorIcons.kt`, manifest | Lifecycle/providers, original microphone icon and RECORD_AUDIO permission |
| `A/feature/SelectiveGhostScreen.kt` | Capability text only; existing Ghost policies/settings unchanged |
| `core/src/test/kotlin/com/vector/core/VoiceTest.kt` (new), `MessagingRepositoryTest.kt` | Deterministic capture/waveform/send regressions |
| `app/src/androidTest/java/com/vector/messenger/VoiceAudioDeviceTest.kt` (new) | User-authorized real encoder/UI/playback check with fake send and protocol mapping |
| README and media/messaging/Ghost documents; this report | Updated capability and evidence boundaries |

## Remaining live/manual validation

No successful voice-message exchange with Telegram is claimed. Use consented test accounts to check incoming/outgoing notes, upload failure/retry, network interruptions, process restart during upload, real peer recording visibility and native cache/source retention. Text-message uncertainty remains the earlier audit's separate reliability issue.

Incoming calls, Bluetooth/wired changes, competing-app focus, Android microphone privacy toggles, OEM encoders, accessibility, rotation/low-memory interruption and route-specific audio output require broader manual coverage. The Samsung test proves local functionality on that device, not every codec/device or guaranteed frame rate. Background playback, notifications, music playlists, speech transcription and server bypasses are outside this feature.

## Primary API references

- [Pinned official TDLib schema](https://github.com/tdlib/td/blob/c8828d50f5eac2fc7089ce1089d7ce9095c61b5e/td/generate/scheme/td_api.tl): inputVoiceNote/inputMessageVoiceNote, messageVoiceNote, sendMessage and native sending states.
- [Android MediaRecorder output formats](https://developer.android.com/reference/android/media/MediaRecorder.OutputFormat), [audio encoders](https://developer.android.com/reference/android/media/MediaRecorder.AudioEncoder): platform Ogg/Opus support.
- [Android audio focus](https://developer.android.com/media/optimize/audio-focus): Media3 automatic playback focus management.
