# Telegram account profile

Account now displays the authenticated Telegram user's first name, last name, primary active username, phone number, account ID and small profile photo. Missing values have explicit empty-state labels; an absent or unavailable photo uses initials. No demonstration identity is used as a Telegram profile. Existing sample messaging remains separate.

## Architecture

- `TelegramModule` provides one singleton `TdlibClientManager` and process scope to both authentication and profile repositories. No second TDLib client or receive loop is created.
- The existing receive loop continues to own authorization and request correlation. It additionally routes current-user `updateUser`, matching-avatar `updateFile`, and connection status updates. Other users' profiles are not forwarded to the account repository.
- `getMe` establishes the current user's identity after authorization is ready. Request epochs reject profile publication from an older authorization session. Revisions prevent an older response from overwriting a newer user update. The core repository independently protects in-flight refreshes from newer profile updates.
- `DefaultProfileRepository` exposes StateFlow, observes authenticated-session changes and owns cancellable refresh/photo requests. Leaving Ready cancels session observation and clears profile state. Connection updates are presentation status only; they do not gate requests or infer authorization.
- `ProfileViewModel` exposes repository state and refresh. The existing Account dialog collects it lifecycle-aware. Composables never call TDLib.
- Avatar download uses `downloadFile` with `synchronous=false`; completion arrives through `updateFile`. Only a completed local file within the configured private TDLib files directory is exposed to the decoder. Bitmap decoding runs on IO with dimensions bounded by sampling. A changed account/photo discards the old image producer.
- The repository is instantiated when Account is first opened and follows the existing session thereafter. There is no new startup prefetch or polling loop. A manual refresh retries profile/photo requests.

## Official APIs

Implementation follows the official TDLib references:

- [getMe](https://core.telegram.org/tdlib/docs/classtd_1_1td__api_1_1get_me.html) returns the current user.
- [user](https://core.telegram.org/tdlib/docs/classtd_1_1td__api_1_1user.html) supplies names, phone, ID, optional usernames and optional photo.
- [usernames](https://core.telegram.org/tdlib/docs/classtd_1_1td__api_1_1usernames.html) defines the first active username as primary. `editable_username` may be disabled and is not used as a public-handle fallback.
- [profilePhoto](https://core.telegram.org/tdlib/docs/classtd_1_1td__api_1_1profile_photo.html), [downloadFile](https://core.telegram.org/tdlib/docs/classtd_1_1td__api_1_1download_file.html) and [TDLib update handling](https://core.telegram.org/tdlib/getting-started) define small avatars and file-completion updates.
- [updateConnectionState](https://core.telegram.org/tdlib/docs/classtd_1_1td__api_1_1update_connection_state.html) is used only to display human-readable connection status.

## Security and scope

The existing key vault, TDLib database encryption, credentials configuration, native binaries, backup exclusions, manifest and logout procedure are preserved. No encryption or protocol implementation was added. Authentication and profile share the original TDLib transport.

Profile text lives in repository memory, not SharedPreferences or SavedStateHandle. Photos remain in TDLib's existing private no-backup file storage; this change adds no public cache, external image service or separate encryption layer. Profile model string representations redact identity and paths. No account data, codes, passwords or raw TDLib errors are logged. Account remains inside its existing `FLAG_SECURE` dialog. Logout clearing and native storage cleanup follow the existing authorization lifecycle; this phase adds no independent storage wipe.

Messaging, module/search navigation, gestures, Appearance controls and Gradle versions/dependencies are unchanged by this phase. Account gains profile rendering and refresh; logout still uses the existing confirmation and authentication repository.

## Files changed in this phase

- `core/src/main/kotlin/com/vector/core/profile/Profile.kt` — models and contracts (new).
- `core/src/main/kotlin/com/vector/core/profile/DefaultProfileRepository.kt` — session-scoped reactive state (new).
- `core/src/test/kotlin/com/vector/core/ProfileRepositoryTest.kt` — deterministic repository tests (new).
- `app/src/main/java/com/vector/messenger/telegram/TdlibClientManager.kt` — profile requests, update routing and response guards.
- `app/src/main/java/com/vector/messenger/telegram/TelegramModule.kt` — shared singleton bindings.
- `app/src/main/java/com/vector/messenger/telegram/TdlibProfileMapping.kt` — JSON mapping and private-path validation (new).
- `app/src/main/java/com/vector/messenger/feature/ProfileViewModel.kt` (new).
- `app/src/main/java/com/vector/messenger/feature/ProfileScreen.kt` (new).
- `app/src/main/java/com/vector/messenger/feature/AuthenticationScreen.kt` — existing Account host displays profile state.
- `app/src/main/java/com/vector/messenger/MainActivity.kt` — obtains the profile ViewModel for Account.
- `app/src/androidTest/java/com/vector/messenger/TelegramProfileMappingTest.kt` (new).
- `app/src/androidTest/java/com/vector/messenger/TelegramProfileUiTest.kt` (new).
- This report. Previous stages' working-tree changes are preserved.

## Verification

Final command (Samsung transport selected with `ANDROID_SERIAL`):

```powershell
.\gradlew.bat :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:connectedDebugAndroidTest :app:compileReleaseKotlin --max-workers=1 --console=plain
```

**BUILD SUCCESSFUL** in 2m 33s. **56 JVM tests and 60 Samsung SM-S9310 / Android 16 tests passed**, zero failures or skips. Debug assembly and release Kotlin compilation passed. Lint: zero errors, 12 existing dependency/tool notices. `git diff --check` passed. Local output: `profile-final-checks.log`.

New tests use synthetic fixtures only: name/ID/phone mapping, active vs disabled usernames, missing/null fields, missing and completed photos, private paths, bitmap decoding/removal, authorization transitions, logout, pending refresh cancellation, reactive profile/photo updates and network failure/retry. The existing authentication, navigation, gesture, Search and Appearance regression suites also passed.

**No live Telegram account was used to verify this feature.** Automated state/rendering tests are not proof of service authentication, live profile retrieval or real avatar download.

Remaining manual checks with an authorized account:

1. Use the existing [TDLib setup](TDLIB_AUTH.md), then open Account and sign in normally. No new developer credentials are required beyond that setup.
2. Compare the displayed names, primary active username, phone, ID and avatar with the official Telegram app. Test an account with no username/photo where available.
3. Change name/username/photo in an official client and confirm live updates in Default.
4. Restart Default and open Account to verify restored-session profile retrieval. Disconnect networking and verify status, cached text and manual retry after reconnecting.
5. Confirm logout clears the profile and a subsequent account never displays the earlier identity/avatar. This is a live service operation and was not performed by automated fixture tests.
