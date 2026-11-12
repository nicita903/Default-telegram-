# Phase 1 delivery notes

## Change inventory

Modified: root and app Gradle configuration, Gradle settings, `.gitignore`, Android manifest, wrapper properties, and README.

Created: `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, the `core` module with models/contracts/demo repositories and tests, Hilt Application and DI module, replacement Activity in `com.vector.messenger`, design tokens/themes, navigation graph, feature screens, two ViewModels, app-name resource, adaptive launcher icon, backup/transfer exclusions, JVM tests, Compose device tests, and this document.

Replaced: the original `dev.vector.messenger.MainActivity` monolithic prototype. Device-specific `local.properties` and unrelated files were preserved. Existing prototype build output is generated data; rebuild/install the new application ID to test this milestone. The old `dev.vector.messenger` installation, if present, is a separate app and is not removed.

## Initial problems resolved

- Missing wrapper scripts and JAR.
- Compose BOM requiring a newer SDK/AGP than configured.
- Incorrect application ID and Android minimum/target compared with the specification.
- UI, fixtures, navigation, and mutable feature state mixed in one Activity.
- No settings destination, repository boundary, dependency injection, or automated tests.
- Decorative console labels and misleading placeholder privacy options.

## Verification

Verified on November 12, 2026:

- Gradle Wrapper scripts and JAR generated successfully using Gradle 8.13.
- Full rebuild with `--rerun-tasks`: successful. Final incremental validation after removing the obsolete empty resource directory also succeeded.
- `:app:assembleDebug`: successful; APK is `app/build/outputs/apk/debug/app-debug.apk`, application ID `com.vector.messenger`, minimum API 29.
- `:app:assembleDebugAndroidTest`: successful.
- `:core:test`: 5 tests passed, zero failures/errors/skips.
- `:app:testDebugUnitTest`: 4 tests passed, zero failures/errors/skips.
- `:app:connectedDebugAndroidTest`: 2 Compose interaction tests passed on the connected Samsung SM-S9310 running Android 16, zero failures/errors/skips. These cover searching/opening a conversation, sending a local message, returning to chats, menu navigation, selecting a light theme, toggling a Ghost Mode setting independently, and selecting a music section.
- `:app:lintDebug`: zero errors and 11 warnings, all concerning newer SDK/tool/dependency versions. The compatible toolchain is intentionally pinned; warnings are retained rather than suppressed. Backup-rule and launcher-icon findings were fixed.
- Merged debug manifest reviewed: no INTERNET permission, no cleartext traffic, backups/device transfers excluded. AndroidX contributes a signature-level internal receiver permission, a permission-protected profile installer receiver, and a debug UI-test activity. Review release manifest separately before shipping.

The initial build exposed the prototype Compose/SDK mismatch. A sandboxed compiler run also encountered a denied Kotlin cache write; successful verification used the approved Gradle execution environment. Offline resolution in the sandbox is not currently available for every dependency; first builds require repository access.

Reports: `core/build/reports/tests/test/index.html`, `app/build/reports/tests/testDebugUnitTest/index.html`, `app/build/reports/androidTests/connected/debug/index.html`, and `app/build/reports/lint-results-debug.html`.

## Remaining work

Phase 2 is official TDLib initialization, protected configuration, authorization flows, login UI, session restoration, and logout. No Phase 2 functionality has been implemented here.

Real synchronization, media, notifications, persistent privacy enforcement, music import/Room/Media3 playback, advanced gestures, and stabilization belong to later phases. Demo settings are not durable user privacy preferences. Authentication and playlist management tests will accompany those features.

No benchmark, battery profile, comprehensive dependency vulnerability audit, or independent security review has been performed. Large-font, screen-reader, reduced-motion, and broad device compatibility testing remain necessary.
