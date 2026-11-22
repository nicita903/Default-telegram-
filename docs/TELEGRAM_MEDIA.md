# Telegram media and downloads

Subsequent [voice/audio work](VOICE_AUDIO.md) adds incoming voice/audio mapping, recording/sending, waveform controls and a shared playback engine reused by the video viewer. That report supersedes the voice/audio omissions in this stage snapshot.

The later [offline Music integration](OFFLINE_MUSIC.md) makes eligible completed audio downloads available privately in Music without copying or publishing them to MediaStore.

Implemented 2026-11-15 in the existing Default application. This extends the single TDLib client and existing conversation/Downloads surfaces. Authentication, message sending, Ghost policies, module navigation and Search recognizers were not rewritten. Existing uncommitted Ghost repairs and the previous audit report were preserved.

## Before this task

| Area | Existing behavior | Actual gap |
| --- | --- | --- |
| Avatar photos | Official downloadFile/updateFile, sampled IO decode, private TDLib cache | Working and retained; not a message-media pipeline |
| Message media | Type/caption text previews; no attachment metadata in real messages | Photos, videos, animations and documents could not be opened/downloaded from real conversations |
| Demo attachments | Local document picker and external ACTION_VIEW for selected content URIs | Demonstration only; not a Telegram transfer |
| Video/GIF | No existing player/animation engine | No reusable player to preserve |
| Downloads | Navigable empty-state screen | No file index, progress, cancel/retry or persistence |
| Storage | TDLib-owned private noBackup files and existing encrypted database | Retained; no broad-storage downloader or second disk cache introduced |

The gaps were missing integrations, rather than defects in the existing avatar pipeline. New race tests also exposed requirements for file-update precedence, cancellation ownership and promoting an in-flight preview to an explicit download. The new repository handles these without changing the messaging send/history lifecycle.

## Added behavior

### Attachments and file state

`TelegramMedia` is separate from the legacy demonstration Attachment URI model. Real message mapping retains photo sizes, video/animation/document descriptors, filename, MIME type, dimensions, duration, file ID, size and native local state. Exact size takes precedence over TDLib's estimated size. Content updates replace/remove media on an already-loaded message. Existing sensitive/self-destructing/ephemeral filtering is retained; spoiler/secret media is withheld rather than exposed through previews.

`TdlibFileClient` is an adapter over the existing `TdlibClientManager`, not another JNI client or service. A dedicated typed reducer subscribes to file and native-download-index updates through that same receive loop. It does not compete for the messaging queue's single consumer.

`MediaRepository` publishes StateFlow, serializes UI-facing mutations on the existing process reducer scope, and cancels session jobs on authentication changes. Concurrent file requests are deduplicated; a pending untracked preview can be promoted to one explicit indexed download. Newer updateFile completion wins over an older request response. UI state never declares a file locally usable until the adapter validates its canonical path, existence and readability under the TDLib files directory.

### Photos

- Lazy, sampled conversation thumbnails; a separate smaller photo size is selected near 320 pixels when available. No automatic original-size network download merely because a large photo is visible.
- Fullscreen viewer opens the original file explicitly and shows its thumbnail while downloading. Progress is driven by updateFile.
- Pinch zoom, bounded panning, double-tap zoom, horizontal previous/next navigation within the loaded message window, downward dismissal and tap-to-toggle controls. Explicit Previous/Next/Close actions provide alternatives.
- Pinch sequences and zoomed panning cannot dismiss or page the viewer. System-edge exclusion and Dialog Back preserve Android navigation. The viewer occupies a separate secure modal window, so module/Search recognizers do not own its touch sequence.
- Whole-image decode is sampled to 2048 pixels. Once zoomed, the visible original-resolution region is decoded off Main after a short quiet interval, with a four-million-pixel tile budget. This provides native pixels at sufficient zoom without allocating a whole oversized image. Max zoom is bounded at 64×.
- Progressive loading means thumbnail-to-original replacement, not decoding an incomplete JPEG stream. Adjacent histories are not fetched or preloaded.

### Video and animations

No player existed. Two matching Media3 1.8.0 artifacts were added (ExoPlayer and UI) without changing any existing toolchain/dependency versions. A single viewer player provides play/pause, seek, elapsed/total time, mute/unmute, audio focus and becoming-noisy handling. Android volume keys continue to control media audio. Local file buffering has an explicit target; player resources are released on disposal and playback pauses on lifecycle stop. Saveable position/mute state supports recreation; playback deliberately does not resume automatically in the background.

Telegram MP4 animations use the same player with looping and initial mute. Actual GIF files use the platform AnimatedImageDrawable decoder, sampled to at most 1024 pixels. Both require explicit Play. Inline rows display previews, not continuously animating media. GIF decode is keyed to the file, stops when disposed/backgrounded and does not repeat on progress changes. Unsupported codecs/animation formats show an error and may be opened externally when Telegram permits export.

### Documents and Downloads

- Compact native-index-backed rows show name, type/MIME, size, progress and actual queued/downloading/completed/paused/failed/available states.
- Pause/resume/retry, view, external Open, Share and Remove are functional commands. Managed downloads use native pause; temporary viewer/thumbnail requests use cancelDownloadFile. Native indexed demand and direct downloadFile demand are canceled independently as required by TDLib.
- Removing an entry uses removeFileFromDownloads with delete_from_cache=false. It removes list metadata and does not delete cached file bytes. No deleteFile or destructive cache-deletion action was added.
- Explicit exportable downloads use TDLib's persistent download index; completed cache files are reused. Native paging loads 50 entries at a time, with the list load-more control capped at 500 restored entries per session. Current-session additions/active transfers remain represented. Removing entries permits a smaller list; no entire message history is loaded.
- Native restoration and external added/paused/removed download updates are processed. Failures expose retry; an interrupted active file becomes Failed. No file-progress polling coroutine runs.

## Security and storage

TDLib continues to own disk storage in noBackupFilesDir/telegram/files. Database key handling, authentication and backups are unchanged. Downloaded media bytes are not claimed to be independently encrypted by the database key.

Protected messages can be viewed inside the secure viewer when supported, but are not added to the exportable download list. Open/Share re-fetch the current message and chat through TDLib and check can_be_saved, restrictions, media identity and has_protected_content. They validate the file again before creating a grant.

`TelegramContentProvider` is a custom narrow read-only content provider because the existing files reside in noBackupFilesDir. It avoids a broad filesystem FileProvider root or copying large files into a second export cache. It is not exported and requires explicit Android URI grants. URI tokens contain no filename, file ID, account ID or path. Grants expire after ten minutes, are bounded to 64, and are cleared on authentication-session changes/process death. Every open checks canonical containment under the TDLib file root. Metadata query only exposes the granted display name and size; write/delete operations are unavailable. An already-open external file descriptor cannot be recalled, so Share remains an explicit user action.

No new permission was requested. No credentials, message text, native errors or local paths are logged by the media components. Existing API configuration and native provenance pin remain unchanged.

## Performance boundaries

- 24 MiB LRU for sampled images; decode concurrency limited to two; no second disk image cache.
- Temporary file metadata is bounded separately from indexed download entries (256 unindexed files).
- Original-region decode is cancellable at coroutine boundaries and retains one visible tile, not a tiled full-history cache.
- Conversation attachments observe distinct per-file projections so unrelated transfer progress does not recompose every visible attachment.
- Native requests, file validation, bitmap decode, GIF decode and export validation run on IO. GPU transformations handle photo movement. Interactive offsets reuse Default's motion/preference system.
- Playback progress samples only while playing; this is a player clock, not TDLib download polling. No autoplay or playback service runs in the background.
- No FPS, heap, battery or cold-start improvement is claimed without profiling. Blocking native decoders/provider calls finish their current operation before coroutine cancellation can discard the result.

## Verification

Build/unit/lint command:

```powershell
.\gradlew.bat :core:test :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --max-workers=1 --console=plain
```

Result: BUILD SUCCESSFUL; debug APK and Android-test APK assembled. **95 JVM tests passed (61 core, 34 app), zero failures/skips**. This includes 12 new fake-client media tests. Lint: **zero errors, 14 warnings**, all target/dependency/tool currency notices (including the two newly added, deliberately pinned Media3 artifacts). JVM media tests cover native-index restoration, progress, completion precedence, deduplication, preview promotion, pause/resume, failure/retry, missing files, removal without deletion, account transitions, bounded metadata and viewer release/navigation decisions. Existing JVM tests were retained.

The Samsung SM-S9310 / Android 16 ran fixture-only mapping/content-provider/photo gesture tests successfully in the initial device run. One expanded run was interrupted because the device locked; it reported missing Compose hierarchies and is not treated as a code result. Its output is preserved in the ignored media-device-locked.log. An unlocked run then caught one existing navigation assertion expecting the signed-out “No downloads” empty state; the new manager retained that wording with its sign-in explanation. The test was not removed or weakened.

Final command:

```powershell
.\gradlew.bat :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:connectedDebugAndroidTest --max-workers=1 --console=plain
```

**BUILD SUCCESSFUL in 3m 31s. All 88 Android instrumentation tests passed, zero failures or skips**, including 12 new media tests. Physical fixture coverage includes two-finger pinch priority, double-tap zoom, zoomed-pan dismissal prevention, horizontal navigation/downward dismissal, Android Back, native MP4 decoder play/pause/mute/seek controls, platform animated-GIF start/pause, media mapping, file containment/missing files and read-only/revocable content grants. MP4 and GIF fixtures are generated locally; no media was fetched from a Telegram account. These tests do not measure visual quality, audio output, universal codec support, FPS or live transfer reliability.

The screen timeout was restored to its original 300000 ms. `git diff --check` passed. Final logs: ignored `media-final-checks.log`; earlier build/lint log: `media-checks.log`. Reports: `core/build/test-results/test`, `app/build/test-results/testDebugUnitTest`, `app/build/outputs/androidTest-results/connected`, `app/build/reports/lint-results-debug.txt`. APK: `app/build/outputs/apk/debug/app-debug.apk`.

## Files changed

Paths are relative to the repository root. `A` = app/src/main/java/com/vector/messenger; `C` = core/src/main/kotlin/com/vector/core.

| Files | Purpose |
| --- | --- |
| `C/Models.kt`, `C/media/TelegramMedia.kt` (new) | Real attachment/file/viewer models; demo Attachment unchanged |
| `C/media/MediaRepository.kt` (new) | Session-scoped transfer reducer/index/paging/commands |
| `C/messaging/TelegramMessaging.kt`, `C/messaging/SessionChatRepository.kt` | Media on content updates only; sending and chat synchronization unchanged |
| `A/telegram/TdlibMediaMapping.kt`, `A/telegram/TdlibFileClient.kt` (new) | Official JSON mapping, file/index calls, validation/export checks |
| `A/telegram/TdlibClientManager.kt` | Broadcast file/index events from existing receive loop |
| `A/di/RepositoryModule.kt`, `A/MainActivity.kt` | Singleton repository/Activity ViewModel provision |
| `A/feature/media/MediaViewModel.kt` (new) | Lifecycle/UI commands and per-file observation |
| `A/feature/media/MediaImages.kt` (new) | Bounded off-Main image decode/cache |
| `A/feature/media/MediaAttachmentView.kt` (new) | Inline preview and file metadata |
| `A/feature/media/MediaViewer.kt` (new) | Modal photo/media navigation and region zoom |
| `A/feature/media/MediaPlayback.kt` (new) | Single Media3 player and platform GIF presentation |
| `A/feature/media/DownloadManagerScreen.kt` (new), `A/feature/MusicScreen.kt` | Replace Downloads empty state; Music behavior unchanged |
| `A/feature/media/TelegramContentProvider.kt` (new), `app/src/main/AndroidManifest.xml` | Narrow read-only URI grants/provider declaration |
| `A/feature/ConversationScreen.kt` | Connect media previews and viewer to existing bubbles |
| `app/build.gradle.kts` | Only two new matching Media3 artifacts; existing versions preserved |
| `core/src/test/kotlin/com/vector/core/MediaRepositoryTest.kt` (new) | Fake-client transfer and viewer reducer tests |
| `app/src/androidTest/java/com/vector/messenger/TelegramMediaMappingTest.kt` (new) | Mapping, missing files, private-root and URI security tests |
| `app/src/androidTest/java/com/vector/messenger/MediaViewerUiTest.kt`, `SyntheticMedia.kt` (new) | Synthetic photo/video/GIF and physical UI regression fixtures |
| `README.md`, `docs/TELEGRAM_MESSAGING.md`, this document | Current capabilities, historical-stage scope and verification |

## Manual/live checks and remaining limits

Live Telegram download initiation/progress/cancellation/resumption and download-index restoration after a real process restart remain unverified. No service message, API credential or live privacy rule was changed during fixture tests. Test a photo/video/GIF/document from a consented account, compare native progress, disconnect/reconnect, pause/resume, relaunch, open/share to another app and verify protected content cannot be exported.

Test orientation changes/seek restoration, audio focus/headphone changes, low-memory/background transitions, very large photos, different codecs/HDR and multi-device touch accessibility. Automated synthetic playback is not a guarantee for every Telegram video/animation format. No background playback or persistent notification service is added.

Photo navigation covers the current bounded conversation window, not a server-wide media gallery. Spoiler/ephemeral/secret/paid media, stickers, audio/voice-note UI, streaming before full download, media upload/editing, Save to gallery/SAF, explicit cache deletion and the independent Music library/player are outside this change. Original-region decode depends on platform format support; sampled fallback remains available. Download failures are locally classified because updateFile does not provide a detailed transfer-error object; failures are not presented as successful completion.

## Official references

- [Pinned TDLib schema](https://github.com/tdlib/td/blob/c8828d50f5eac2fc7089ce1089d7ce9095c61b5e/td/generate/scheme/td_api.tl): File, media types, addFileToDownloads, toggleDownloadIsPaused, searchFileDownloads and non-destructive removal.
- [downloadFile](https://core.telegram.org/tdlib/docs/classtd_1_1td__api_1_1download_file.html): asynchronous file state and updateFile-driven progress.
- [cancelDownloadFile](https://core.telegram.org/tdlib/docs/classtd_1_1td__api_1_1cancel_download_file.html): direct request cancellation.
- [Media3 ExoPlayer setup and lifecycle](https://developer.android.com/media/media3/exoplayer/hello-world), [Media3 releases](https://developer.android.com/jetpack/androidx/releases/media3).
