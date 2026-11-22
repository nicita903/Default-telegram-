# Default offline Music

Implemented 2026-11-15 in the existing app. Authentication, Telegram synchronization/text sending, Ghost policies, module-history architecture and Search recognizers were preserved. Existing uncommitted media/voice/Ghost work was retained. There is one ExoPlayer construction site and one active music media session, not a player per song or a second audio engine.

## Existing implementation and integration

| Area | Before | Current implementation |
| --- | --- | --- |
| Music module | Library links and empty section screens | Real library sections, playlists, recent history, continue-listening and Now Playing |
| Music Search | Real MediaStore songs/albums/artists/system playlists; external ACTION_VIEW playback | Same shared catalogue plus local playlists/history/Telegram audio; internal shared-player playback and filtering off Main |
| Audio engine | Shared foreground voice/video Media3 owner | Extended with music queue and coordinated ownership; original voice/video controls retained |
| Downloads | Native TDLib file index, validated cache paths and progress | Eligible completed audio is referenced in place; no second downloader or file copying |
| Persistence | Appearance/private Ghost mechanisms, no music lists/queue | Private identifier-based playlists/history and separate lightweight playback-state storage |
| Background | No music playback service | One Media3 MediaSessionService with notification, focus and system transports; idle teardown |

The existing MediaStore repository was extended and made a singleton. Music, Music Search and active playback share its StateFlow/ContentObserver source. Device refreshes occur on subscription, explicit refresh, foreground resume, permission result or audio/playlist provider change, with event coalescing. There is no recurring filesystem scan.

## Library and metadata

Sections: All Songs, Albums, Artists, Playlists, Folders, Recently Added and Recently Played. Section/collection filters and sorting are saveable independently; list/collection filtering runs on Dispatchers.Default. Querying, metadata extraction, file validation, artwork decoding and preference serialization run off Main. Lazy song rows use stable source keys, including storage volume identity.

MediaStore provides title, artist, album, duration, track number, folder, date added, format and album-art reference. Unknown metadata stays absent or is labeled unavailable; no fake artist/album/song is created. MP3, FLAC, AAC, M4A, OGG, OPUS and WAV containers are eligible. Codec-family availability is checked against the device's decoders, with PCM/WAV handled by the backend. This is not pre-decoding every file: corrupt data or an unsupported codec inside an otherwise eligible container produces a playback error and an available Next/other-track action.

Original read-only MediaStore playlists are retained where the provider supports them. User-created playlists are app-local: create, rename, delete, add/remove songs, reorder, play and shuffle. Missing references are retained in the saved playlist and reported as unavailable rather than replaced with fabricated tracks. Deleting a playlist never deletes audio files. Local playlists are limited to 200 lists/1,000 unique references each; queues are bounded to 1,000 tracks.

Artwork is read lazily from local media, sampled to at most 512 pixels, with two concurrent decoders and an 8-MiB LRU. The UI uses a neutral music icon when no embedded art is available. All artwork is local; remote Telegram cover URLs are not fetched.

## Private Telegram audio

The catalogue merges export-eligible, completed Audio/downloadable-audio Document entries from the existing native download repository. Voice notes are not treated as songs. Native file identity and account ID scope the key, preventing repeated entries for the same indexed file and cross-account reuse. Separate physical copies in MediaStore remain separate files; no expensive content-hash scan is performed.

Private files remain in TDLib's existing noBackup file area. Embedded metadata is extracted locally with a bounded cache. No move, export, public MediaStore insertion or additional media download occurs merely from discovering a Telegram song.

Playback revalidates the source through the existing message/chat restrictions and file-validation layer on the Media3 loader thread. This also applies when automatically advancing to a queued private track. The library/account observer removes unavailable private items from the native queue, including after the Activity has gone away; local music can continue across authentication changes. A private current track is stopped when access is lost.

Media session sources use opaque app-local aliases resolved by the shared engine. Controllers are not handed unrestricted private filesystem URLs. The session accepts the app/trusted system controllers, with arbitrary playlist replacement disabled; item resolution only recognizes the current known queue. The service's exported declaration is required for standard MediaSessionService clients, not a general file/network endpoint.

Telegram availability follows the native download index/cache. Removing a download-list entry withdraws its Music catalogue reference without deleting the cached bytes. Cache eviction/deleted messages/changed restrictions can make a track unavailable. This is not a durable second copy of Telegram media. Live-account offline cache behavior still needs validation; no successful private-account playback was asserted from synthetic tests.

## Playback, ownership and lifecycle

- Shared native queue: play/pause, previous/next, seek, shuffle, repeat one/all/off, queue reordering and 0.75×/1×/1.25×/1.5×/2× speed. Audio is not transcoded or passed through an app-defined audio effect.
- Native state drives duration, position, completion and errors. Shuffle starts at the requested/random selection and includes the other queue entries once before termination/repeat. Queue normalization preserves the selected identity when duplicates are removed.
- Music is parked when voice, recording or video takes ownership. The queue, position and options survive; **music never automatically resumes after voice/video**. Resume is an explicit user action. Native playback focus/noisy handling remains enabled, with no forced speaker/Bluetooth route.
- One conditional global controller gives voice priority and otherwise shows music. It reserves Scaffold space above conversation controls and excludes module gestures. Music's mini player has preference-aware reveal/dismiss motion; Now Playing is a modal workspace, not a new main module or permanent navigation bar.
- Now Playing includes local artwork, title/artist/album, progress/seek, transports, speed, shuffle/repeat and reorderable queue. Restored queues show available songs before playback is restarted. If the saved current file is missing, another available song starts at zero rather than inheriting the missing song's position.
- Media3's foreground playback service provides a system media notification/session. Session metadata and controls update with the native player; replacing a queue retargets the existing session rather than creating another session.
- Music survives Activity backgrounding/task removal while play is requested. Voice/video remain foreground-only. Paused/completed/failed music is released after a 30-second idle grace period; stopping dismisses the session/service immediately. No always-running idle service is created.
- After idle teardown/process death, continue listening through the app. Automatic system playback resumption/boot restart is not implemented. Hardware transports operate while the session is active; long-idle resumption from headset buttons needs a separate resumption implementation if desired.

## Local storage and performance

Playlists/recent identifiers use MODE_PRIVATE `music_library_v1` preferences. Playback keys/index/position/options use the separate `music_playback_v1` file. No Telegram credential, phone number or private file path is persisted in these music stores. Existing application backup exclusions remain unchanged.

Position is checkpointed every 15 seconds while playing, and queue/option/track changes or pauses save immediately. This avoids rewriting a potentially large playlist file for every progress sample. A crash can lose up to the last checkpoint interval; normal handoff/pause preserves the current position.

Progress sampling runs only during playback (music 250 ms, existing voice 100 ms). The catalogue is observed while visible or relevant to a retained queue, with change-triggered refreshes rather than polling. Group/search work runs off Main; metadata/bitmaps remain bounded and lazy. No cold-start, battery, heap or guaranteed-frame-rate measurement was made, so no numerical performance gain is claimed.

The single new dependency is matching `androidx.media3:media3-session:1.8.0`. Existing Media3 versions, Gradle/Kotlin/AGP/KSP versions and native TDLib provenance remain unchanged. New normal permissions are FOREGROUND_SERVICE and FOREGROUND_SERVICE_MEDIA_PLAYBACK; existing scoped audio-media permission is requested only for device music. No unrestricted storage, Bluetooth-access or analytics permission was added. Media-session notifications use the platform's media exemption rather than an unnecessary general notification-permission prompt.

## Defects found and corrected during integration

1. Placeholder Music and external-player Search were connected to the shared real catalogue/player.
2. Search/group filtering formerly ran in composition; it now uses background flow transforms.
3. A foreground service started without an explicit controller needs its session registered; addSession now registers it directly. Queue/player replacement updates the existing session.
4. Private queued sources are rechecked on load and revoked when the account/catalogue changes; local playback is not globally reset on login/logout.
5. Compact playlist actions could be clipped horizontally. They now wrap, and the create/rename/delete UI test passes without removing its assertions.
6. Playback progress storage was split from playlist storage to avoid large repeated writes.
7. A missing saved current song could transfer its position to another song; pure queue restoration now resets that fallback position and has a dedicated regression.
8. The WAV test initially assumed MediaStore would preserve inserted artist labels. Android rescans and may replace them, so the fixture test checks the provider's actual metadata, including unknown fields.

## Verification

Full command (Samsung USB transport selected; app APKs retained after instrumentation):

```powershell
.\gradlew.bat :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:connectedDebugAndroidTest '-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true' --max-workers=1 --console=plain
```

Full device run: **BUILD SUCCESSFUL in 2m 25s; 94 Android test cases, 56 passed, 38 skipped by existing configured-account/demo/native-smoke guards, zero failures.** The skipped cases are not counted as physical verification. A separate run interrupted by device locking is preserved in `music-locked-device.log` and is not used as a code result. No test was deleted; the navigation state assertion now checks the Music section identity instead of assuming every device has an empty playlist catalogue.

Samsung SM-S9310 / Android 16 fixture tests verified actual MediaStore scanning, provider metadata, native Media3 playback/session title, seek, next, shuffle/repeat/speed commands, playback continuing after Home, pause, voice ownership/queue preservation, missing-file resolution, preference roundtrip, denied-permission behavior and local playlist create/rename/delete UI. Synthetic WAV files were silent, deleted afterward, and never sent to Telegram. Real playlists/settings were preserved; CRUD tests used isolated preferences.

After the full device run, the pure missing-current restoration edge was fixed and the JVM/build/lint checks were rerun with `:core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug`. **BUILD SUCCESSFUL in 1m 26s; 119 JVM tests passed, zero failures or skips; lint reported zero errors and 21 warnings.** The warnings concern SDK/dependency notices, KTX suggestions and the intentionally exported media-session service. The new music tests cover queue identity/limits/reordering, seeded shuffle, interruption with a fake transport, format eligibility, missing-current restoration, metadata filtering/grouping and missing playlist references. Existing voice/media/auth/messaging tests remain in the suite. The Samsung suite preceded this final pure restoration fix; its new regression was verified on the JVM.

The original user-0 audio permission was restored to denied; microphone permission remained at its original granted state. Screen timeout was restored to 300000 ms. Credentials, live privacy rules and messaging were not changed. `git diff --check` passed.

Logs: `music-checks.log`, `music-device-tests.log`, `music-ui-tests.log`, `music-final-checks.log`, `music-queue-checks.log` (ignored). Reports: `core/build/test-results/test`, `app/build/test-results/testDebugUnitTest`, `app/build/outputs/androidTest-results/connected`, `app/build/reports/lint-results-debug.txt`. APK: `app/build/outputs/apk/debug/app-debug.apk`.

## Modified files

Paths are relative to the repository root; A = `app/src/main/java/com/vector/messenger`.

| Files | Purpose |
| --- | --- |
| `core/.../music/Music.kt` (new) | Track/playlist/queue models, format/filter/shuffle/restoration and interruption policy |
| `A/feature/search/MusicLibraryRepository.kt` | Shared MediaStore/native-download catalogue, metadata, permission and playlist persistence commands |
| `A/feature/music/MusicPreferences.kt`, `MusicPlaybackState.kt`, `MusicCoordinator.kt` (new) | Local storage, queue ownership and native controls |
| `A/feature/music/MusicPlaybackService.kt` (new) | Single media session/foreground lifecycle and trusted controller entry point |
| `A/feature/music/MusicViewModel.kt`, `MusicArtwork.kt`, `MusicLibraryUi.kt`, `NowPlaying.kt` (new) | Library/playlist/now-playing/mini-player UI and bounded artwork |
| `A/feature/audio/SharedMediaPlayer.kt`, `AudioViewModel.kt`, `VoiceUi.kt` | Extend the existing owner, preserve voice/video and render one global player |
| `A/feature/MusicScreen.kt`, `A/MainActivity.kt` | Connect existing Music destinations, player modal and notification intent |
| `A/feature/search/SearchViewModel.kt`, `SearchWorkspace.kt` | Music-only search/catalogue/player connection; Chat Search and recognizers retained |
| `core/.../media/TelegramMedia.kt`, `A/telegram/TdlibMediaMapping.kt` | Audio performer/album metadata fields |
| `app/build.gradle.kts`, manifest | Matching session artifact and media-playback service declarations |
| `core/src/test/.../MusicTest.kt`, `app/src/test/.../MusicFilteringTest.kt` (new) | Deterministic core and filtering regressions |
| `app/src/androidTest/.../MusicDeviceTest.kt`, `MusicUiTest.kt` (new) | Synthetic native/session/storage/permission/UI verification |
| `app/src/androidTest/.../SearchScrollRestorationUiTest.kt`, `NavigationGestureUiTest.kt` | Constructor lifecycle scope and real section identity adaptation |
| README, voice/media reports, this report | Capability/evidence updates |

## Remaining manual checks

Test real private Telegram downloads offline, changed restrictions/account switching during a queued private load, all codec/container variants, a large personal catalogue, external tag/art edits, cold process restoration, locked-screen playback and actual Bluetooth/wired headset controls on representative devices. Home/background playback is verified; it is not proof of every locked-screen/OEM or hardware-control scenario. General messenger push notifications, music cloud sync, audio effects, public exports/import copies and automatic long-idle system resumption were not added.

To use device music: Music → All Songs → Allow audio access. Private Telegram songs appear only while the corresponding account is authenticated and its eligible completed download remains available.

## Official references

- [Media3 background playback](https://developer.android.com/media/media3/session/background-playback) and [media-session control](https://developer.android.com/media/media3/session/control-playback).
- [Android shared media storage](https://developer.android.com/training/data-storage/shared/media).
- [Media3 supported formats](https://developer.android.com/media/media3/exoplayer/supported-formats).
- [Pinned official TDLib schema](https://github.com/tdlib/td/blob/c8828d50f5eac2fc7089ce1089d7ce9095c61b5e/td/generate/scheme/td_api.tl) for native audio/download metadata; existing restriction checks remain in the file adapter.
