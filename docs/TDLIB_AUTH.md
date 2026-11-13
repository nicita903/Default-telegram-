# Phase 3 — official TDLib and authentication

DEFAULT now has an official TDLib JNI JSON integration behind the existing Account button. The current chat list, conversations, module stacks, gestures, music, and Ghost Mode foundations are retained. Telegram message synchronization is deliberately outside this phase; signing in does not turn the demonstration chats into real Telegram conversations.

## Developer setup

1. Obtain your own Telegram application API ID and API hash at [my.telegram.org/apps](https://my.telegram.org/apps). Follow [Telegram's application registration instructions](https://core.telegram.org/api/obtaining_api_id).
2. Copy `telegram.properties.example` to the ignored `telegram.properties` at the project root. Set `api.id` and `api.hash` there. Alternatively set `TELEGRAM_API_ID` and `TELEGRAM_API_HASH` in your local build environment. Nonblank local properties take precedence. Do not paste these values into chats or commit the local file.
3. Build the Android app:

   ```powershell
   .\gradlew.bat :app:assembleDebug --console=plain
   ```

4. Install `app/build/outputs/apk/debug/app-debug.apk` on an ARM64 Android device. Open the existing module menu → Account. Enter your phone number with country code, then verification information and two-step password when Telegram requests them. QR login is available from the phone step. Telegram can also require an email address/code; those steps are supported.
5. To test restoration, close/relaunch the app without logging out, then open Account. Only an actual TDLib `authorizationStateReady` update produces the signed-in screen.
6. To log out, open Account → Log out and confirm. Keep connectivity available. Local TDLib data is removed by official TDLib logout; the wrapped storage key is removed only after the logging-out state and closed-state confirmation.

Verification codes and account passwords belong only in the device login UI. Never put them in source, build properties, logs, screenshots, or development prompts.

The API ID/hash identify this developer's Telegram client. They must be available to TDLib on the device and are therefore extractable from the APK. Ignoring the local source file and avoiding a build cache protects the development workflow; it cannot make an Android application API hash an unextractable secret. The generated asset lives under `app/build/generated/telegramAssets/` and is not committed. Treat build outputs containing your configuration accordingly.

## Native build and provenance

This integration does not download an unofficial TDLib AAR. It compiles the [official TDLib source](https://github.com/tdlib/td) using the official Android CMake target and JSONJava JNI interface.

| Component | Reviewed pin |
|---|---|
| TDLib | 1.8.70; commit `c8828d50f5eac2fc7089ce1089d7ce9095c61b5e` |
| Interface | `example/android`, `TD_ANDROID_JSON_JAVA=ON`; official `org.drinkless.tdlib.JsonClient` |
| OpenSSL | 3.5.9, compiled from its official release source; static linkage |
| OpenSSL source SHA-256 | `603f5602e2eef00d77fbd429d34dcd5822bb301757a1bc9cdb24c670f1eb859a` |
| NDK | r28c / 28.2.13676358; official Windows archive SHA-1 `086bba43ff2f5eb0e387b15c8278bb4e0d89ba1d` |
| Android target | Native minimum API 29; ARM64 in the delivered build; static libc++ |
| Memory reduction | Official `SplitSource.php`; no custom protocol or crypto source changes |

The Java bridge is copied from that same commit. Its original source SHA-256 is `0cecc3b8957f680c3706e3b9d062238a2e0eb467195acef797ead10bad630d8b`. Local changes remove the upstream `printStackTrace()`/swallowed load failure and add `@Keep` annotations to preserve JNI names. A load failure is surfaced through a generic setup state instead of logs.

`native/tdlib-manifest.json` records the packaged library hash. The Gradle verification task rejects mismatched, additional, or differently pinned native binaries. A checksum is an integrity check against the reviewed manifest, not an independent audit or signature. Review manifest changes when rebuilding; do not accept a replacement binary and self-supplied manifest as proof of trusted provenance.

Native build downloads and objects are isolated under ignored `.native/`. Packaged `.so` files under `app/src/main/jniLibs/` are also ignored. A fresh source checkout therefore needs a native build before Account can initialize TDLib. Missing native files do not break the demo UI's Kotlin build; Account reports the missing setup clearly. The delivered APK's native build result is recorded in the verification section below.

For a fresh Windows native build:

1. Install Git, CMake, Ninja, and an MSYS2 environment with MinGW-w64 GCC/G++, Bash, Perl, GNU Make, tar and zstd. Keep the native workspace/MSYS paths free of problematic shell characters. No changes to the Android project's Gradle/Kotlin/KSP versions are needed.
2. Run the supplied recipe, for example:

   ```powershell
   .\scripts\Build-TdlibAndroid.ps1 -MsysRoot C:\msys64 -Jobs 1
   ```

   It fetches pinned source/tool archives from the official TDLib, OpenSSL, Android and PHP origins, plus GNU gperf from the official MSYS2 repository. Archive checksums are checked. Downloads/extraction stay in `.native/`; system SDK and Gradle installations are not replaced. Existing TDLib checkouts with a different commit are rejected rather than reset.

3. Review `native/tdlib-manifest.json`, then run the normal Android build. To build additional architectures, supply the complete desired set, for example `-Abis arm64-v8a,x86_64`. Old extra binaries are not silently deleted; the Gradle inventory check requires them to agree with the manifest.

The recipe defaults to one native compiler worker because large translation units can exhaust Windows commit memory. Increasing `-Jobs` is optional and requires sufficient memory. Native compilation can take substantially longer than the Kotlin app build. The initial environment used MSYS2 from `E:\programs\mysys`, CMake 4.3.1, NDK r28c, and the existing JDK 21. The script was syntax-checked; actual compilation commands and source preparation were exercised in this environment. A second clean bootstrap of every downloaded tool was not repeated.

Reference build instructions: [official TDLib Android example](https://github.com/tdlib/td/tree/c8828d50f5eac2fc7089ce1089d7ce9095c61b5e/example/android), [official JSON Java bridge](https://github.com/tdlib/td/blob/c8828d50f5eac2fc7089ce1089d7ce9095c61b5e/example/java/org/drinkless/tdlib/JsonClient.java), and [pinned API schema](https://github.com/tdlib/td/blob/c8828d50f5eac2fc7089ce1089d7ce9095c61b5e/td/generate/scheme/td_api.tl). License texts are included in APK assets under `licenses/`.

## Architecture

| Responsibility | Implementation |
|---|---|
| Auth states/actions/contracts | `core/.../auth/Authentication.kt` |
| StateFlow, validation, duplicate requests, stale errors | `core/.../auth/DefaultAuthenticationRepository.kt` |
| JNI ownership, receive loop, correlated requests, initialization | `app/.../telegram/TdlibClientManager.kt` |
| Official auth API request/update mapping and sanitized errors | `app/.../telegram/TdlibAuthMapping.kt` |
| API configuration loading | `app/.../telegram/TelegramConfiguration.kt` |
| Keystore-wrapped storage key | `app/.../telegram/TdlibKeyVault.kt` |
| Process-owned repository dependency | `app/.../telegram/TelegramModule.kt` |
| Lifecycle ViewModel | `app/.../feature/AuthenticationViewModel.kt` |
| Secure themed login/account dialog | `app/.../feature/AuthenticationScreen.kt` |

There is one process-owned TDLib client and one blocking official receive loop. TDLib work runs off Main. StateFlow collection is lifecycle-aware. State updates come from `updateAuthorizationState`, not optimistic assumptions after an `ok` result. Request IDs correlate responses; timeouts and late errors cannot overwrite a newer auth step. A closed instance can be recreated for another sign-in without duplicating event collectors.

The existing demo chat repository remains explicitly separate. No Composable calls JNI or TDLib. Opening Account neither recreates the modular app nor changes its module visit history. The only existing navigation edits connect the Account callback and correct the demo chat subtitle, which must remain accurate even when authentication succeeds.

## Security decisions

- A random 32-byte TDLib database key is encrypted with an Android Keystore AES-256/GCM wrapping key. Only the authenticated ciphertext envelope is stored, using `AtomicFile` in `noBackupFilesDir`.
- TDLib database/files directories also live in private `noBackupFilesDir`. Existing application backup and transfer exclusions remain enabled; backups are disabled. No external storage path is used for session data.
- Missing, corrupted or invalidated key material never causes silent replacement of an existing database key or automatic deletion of existing session files. Recovery requires deliberate developer/user action; lost keys cannot decrypt the old database.
- The wrapping key does not require biometric confirmation on every use, allowing session restoration. This is storage protection, not an app-lock implementation.
- TDLib's official database encryption is used. No Telegram crypto, transport, TLS validation, or certificate checks are reimplemented or weakened. Media encryption should not be inferred from database encryption.
- Native logging is set to an empty stream before any credentials are sent. Application code does not log requests, raw errors, QR links, API hashes, verification codes, passwords, or key bytes. TDLib error 406 is handled without inspecting its message. Other error text is mapped to fixed user-facing descriptions rather than displayed raw.
- Login inputs are masked as appropriate, kept only in transient Compose memory, excluded from saved state, and cleared before submission. Authentication APIs necessarily receive transient strings in memory; immutable JVM strings cannot be reliably wiped. Database-key byte arrays are cleared after use.
- The Account dialog uses `FLAG_SECURE`. It does not request camera, SMS, phone, contact, or broad storage permissions. The only new manifest permission is INTERNET; no activity/service/provider was added or exported.
- Server logout and local key deletion are separate operations. Offline logout may wait for connectivity. Ordinary process recreation does not delete keys or log out.
- Existing debug test components remain development-only. Review merged release manifests and release signing before distribution. This is not an independent security audit.

## Verification

- Official ARM64 native compilation and JNI linking succeeded. Packaged library: 33,270,624 bytes; SHA-256 is recorded in `native/tdlib-manifest.json`.
- `gradlew.bat :app:assembleDebug --console=plain`: successful with the preserved Gradle 8.13 / AGP 8.13.2 / Kotlin 2.3.20 / JDK 21 toolchain.
- JVM tests: 28 passed, including 7 new fake-client authentication tests for phone/code/password/QR/email transitions, restoration, logout/restart, validation, redaction, and stale errors. The existing repository, ViewModel, settings and gesture/history tests also pass.
- Physical-device tests: 19 passed on Samsung SM-S9310 / Android 16, zero failures/errors/skips in the final run. This includes actual official JNI create/receive/close calls without credentials, Android Keystore key restoration and lost/unreadable-database fail-closed behavior, auth input masking/clearing, a real secure Account window, and existing UI/navigation/gesture regressions.
- Lint: zero errors, 12 dependency/tool/SDK version-update warnings. No toolchain upgrades or warning suppressions were made.
- Static ELF inspection: all native LOAD segments use `0x4000` (16 KB) alignment. APK `zipalign -c -P 16 -v 4` verification passed. This is a static packaging check, not a test on a device operating with a 16 KB page size.
- Native/source logging review found no request/credential/code/password/key logging. Existing backup exclusions were retained. INTERNET is the only new permission. `git diff --check` passed for the changed build/entry-point files.
- Native and JVM builds initially hit Windows commit-memory limits. The successful native build used the official source splitter and reduced concurrency. A temporary, command-line-only 1 GB Gradle heap/in-process Kotlin compilation was used during test preparation; persistent Gradle settings were not changed.

There are no live Telegram service authentication results. A fake Ready state, native library loading, and Keystore tests do not establish a successful account login, service session restoration, or logout.

Reports are under `core/build/reports/tests/test/`, `app/build/reports/tests/testDebugUnitTest/`, `app/build/reports/androidTests/connected/debug/`, and `app/build/reports/lint-results-debug.html`.

## Changed files in this phase

Prior uncommitted navigation/design work was preserved. The authentication changes are:

| Area | Files |
|---|---|
| Core authentication | `core/src/main/kotlin/com/vector/core/auth/Authentication.kt`, `DefaultAuthenticationRepository.kt` |
| Android integration | `app/src/main/java/com/vector/messenger/telegram/TdlibClientManager.kt`, `TdlibAuthMapping.kt`, `TelegramConfiguration.kt`, `TdlibKeyVault.kt`, `TelegramModule.kt` |
| Official Java bridge | `app/src/main/java/org/drinkless/tdlib/JsonClient.java` |
| Auth ViewModel/UI | `app/src/main/java/com/vector/messenger/feature/AuthenticationViewModel.kt`, `AuthenticationScreen.kt` |
| Existing entry points | `app/src/main/java/com/vector/messenger/MainActivity.kt`, `navigation/DefaultApp.kt`, `navigation/VectorModuleMenu.kt` |
| Build/configuration | `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `.gitignore`, `telegram.properties.example` |
| Native recipe/provenance/license | `scripts/Build-TdlibAndroid.ps1`, `native/tdlib-manifest.json`, `native/TDLIB_LICENSE.txt`, `app/src/main/assets/licenses/` |
| Tests | `core/src/test/kotlin/com/vector/core/AuthenticationRepositoryTest.kt`, `app/src/androidTest/java/com/vector/messenger/AuthenticationUiTest.kt`, `telegram/TdlibIntegrationTest.kt`, plus the Account-window check in `FoundationUiTest.kt` |
| Documentation | `README.md`, `docs/TDLIB_AUTH.md` |

The generated/ignored native artifact is `app/src/main/jniLibs/arm64-v8a/libtdjsonjava.so`. No API credentials, passwords, verification codes or session databases were added to Git. No commit/reset was performed.

## Manual service checks and limitations

No developer API credentials or real account verification information were supplied for this work. A successful phone/code/password login, QR scan, real authenticated-session restoration, and server-confirmed logout must still be tested manually against Telegram using your own configured build.

Telegram controls code delivery, resend cooldowns, regional/account restrictions, and session authorization. Third-party applications may not receive SMS codes. QR support depends on the server/account; QR refreshes follow TDLib updates. Account creation, Premium purchase flows, passkeys, password recovery, and platform-specific app-verification challenges are not implemented; unsupported states are explained without bypasses. Secret Chats are not enabled by this authentication-only integration.

Only ARM64 is packaged in the initial native artifact. Other ABIs require a source build. UI/gesture regressions, fake auth transitions and offline native checks do not replace service integration testing, a security audit, or a broad Android-device compatibility matrix.
