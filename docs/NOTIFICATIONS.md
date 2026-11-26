# Background message delivery

## Confirmed configuration gap

At the reliability audit, neither app/google-services.json, root google-services.json, nor app/src/main/res/values/firebase-config.xml existed. Firebase dependencies/service declarations alone do not enable push. In that state there is no FCM token or confirmed Telegram push subscription: only the live process-owned TDLib connection can discover new messages. Android can suspend or terminate that process, leaving messages to be discovered on reconnect. No foreground service or polling loop is added to mask this gap.

## Required developer setup

1. Register Android package **com.vector.messenger** in your real Firebase project. Download its Android client google-services.json and place it at **app/google-services.json** (ignored by Git). The existing build now validates that package and generates standard early-initialization Firebase resources and fallback client assets; no Google Services plugin, toolchain upgrade, or placeholder credentials are needed. The root google-services.json location and the existing explicit Firebase resource configuration are also supported.
2. Rebuild/install in place. Firebase initializes independently of authentication, obtains its token, and enables FCM auto-init. A missing/invalid configuration is diagnosed; Telegram login continues using its existing database/key.
3. The matching sender/project must also be accepted and configured for the Telegram developer application/API ID. TDLib's DeviceToken documentation requires correct platform and server authentication data for the push provider. Confirm Telegram's supported provisioning route for your developer application; simply getting an FCM token or creating a Firebase project does not establish that association. Service-account credentials belong in authorized server/provider configuration, **never in this Android project/APK**.
4. Verify a real **registerDevice -> pushReceiverId** receipt. The app persists account-specific receiver ID, token hash (not the raw token), and confirmation time. It reuses a recent confirmed receipt, reconciles changed tokens, and invalidates the logged-out account's receipt. Transient failures retry after 15 seconds with exponential backoff capped at five minutes while the account/token/connection remain current. Logout, token changes and connection loss cancel the prior retry job. Rejected configuration (400/401/403) waits for a changed token or connection. Confirmed registrations renew after 24 hours; Activity resumes do not fetch/re-register a token each time.
5. Test real Telegram delivery. A registration receipt proves only that TDLib accepted registration, not that Telegram's sender configuration and FCM transport actually deliver.

## Execution paths and isolation

Foreground messaging retains the existing single TDLib manager, encrypted database directories and authentication flow. Android network callbacks notify that client via setNetworkType; they do not recreate it. TDLib connection state remains the source of truth. Notification initialization never gates setTdlibParameters or getMe.

Background FCM messages are handed to an encrypted atomic file in noBackupFilesDir. WorkManager stores only its opaque filename. Received high-priority pushes request expedited work, with an explicitly documented normal-job fallback when quota is unavailable; normal-priority pushes use ordinary work. The Firebase callback also allows up to four seconds of immediate processing after durable enqueue. A shared per-event gate and bounded persisted completion ledger prevent callback/worker duplicate processing. Workers have bounded processing and retry counts, not periodic polling.

getPushReceiverId is executed through the pinned official TDLib JSON API. The resulting subscription is matched to the current registered account before processPushNotification. Currently the product owns one Telegram session: another account's receiver is not silently fed into it or used to create a second client. Account-qualified receipt keys allow a future session registry to dispatch additional existing account managers. Encrypted payloads are interpreted/decrypted only by TDLib. Notification updates emitted during native initialization are buffered and epoch-filtered until account handling is ready. A drain barrier waits for notification rendering before background work is marked complete.

TDLib notification groups remain the sole message-alert producer. Push callbacks and updateNewMessage do not independently build alerts. Stable provider/account/chat tags, native message IDs, and persisted dismissal watermarks reconcile pushes, reconnects, edits, and removals. Existing Quick Reply, mark-read, mute, avatars, preview privacy and active-chat suppression remain intact. Chat entry cancels the exact native tag/id and removes TDLib notification groups without manufacturing read receipts.

Channels remain messages_v1, groups_v1 and mentions_v1. Existing user channel choices are preserved. Channel importance does not raise FCM transport priority. Telegram controls sender-side push priority; FCM/Android can defer normal-priority delivery in Doze, and expedited jobs are quota-limited. Explicit Android Force Stop generally prevents push restart until the user launches Default again. No bypass, battery exemption, or permanent connection foreground service is implemented.

## Safe diagnostics

Logcat tag **DefaultPush** records timestamps, generated/hash correlation references, and fixed stages: Firebase initialized/configuration missing; FCM token available; Telegram registration attempted/succeeded/failed; FCM push received and received priority; work enqueued/started; receiver resolved/unavailable; processing started/completed/rejected; Android notification posted; TDLib connection-state changes. Tokens, account IDs, native error descriptions, sender names, message text and payloads are excluded. Existing debug-only DefaultNotificationTrace remains available for renderer decisions.

## Verification still required after setup

- A: Foreground incoming messages synchronize; one notification at most.
- B: Launcher confirmed foreground; a real incoming message notifies without reopening Default; tap opens its chat.
- C: Ordinary process termination (not Force Stop); a real Telegram push starts the service/worker and posts without an Activity.
- D: Locked screen/Doze; record received FCM priority and actual receive-to-post latency.
- E: Wi-Fi/mobile/VPN changes; same client/session reconnects, without resets.
- F: Multiple messages consolidate by conversation and do not re-alert on push replacement.
- G: Open one of two notified chats; only that chat clears, normal read-state rules remain.
- H: Force Stop restriction documented; do not claim a bypass or disable it for testing.

No live background-delivery test is claimed before Firebase and Telegram sender provisioning are complete. Device audit found Google Play Services installed, standby bucket Active (10), and no explicit RUN_ANY_IN_BACKGROUND app-op denial. Those checks do not prove FCM delivery or rule out OEM/Doze restrictions.

Official references:
- https://core.telegram.org/tdlib/notification-api
- https://github.com/tdlib/td/blob/c8828d50f5eac2fc7089ce1089d7ce9095c61b5e/td/generate/scheme/td_api.tl
- https://firebase.google.com/docs/cloud-messaging/android/message-priority

## Background-delivery repair (26 November 2026)

The inspected APK has an empty generated Firebase configuration and zero saved Telegram push receiver IDs. It cannot obtain/register an FCM token in that configuration. Configure both the Firebase Android client and the corresponding Telegram API application's server-side push credentials; client JSON alone does not configure Telegram's sender. Never package a Firebase service-account private key in the APK.

Two client issues were also repaired: transient registration failures previously depended on another connection/resume event after a five-minute throttle, and pre-authorization notification updates could be discarded by intermediate session epochs. Registration now retries independently of Activities; early TDLib notification updates are queued until authorization is ready and discarded on logout. Existing TDLib groups/dismissal watermarks remain authoritative, with no history-message alert producer.

`adb logcat -s DefaultPush` traces monotonic time, PID, a per-process hashed account alias, current transport/validation and app foreground state. Stages cover client creation/closure, connection, FCM token availability/refresh, registration requests/results/retries, FCM receipt, TDLib push processing, notification updates and Android posting. Error logs retain numeric TDLib codes and fixed uppercase error symbols; all other native/SDK text is redacted. No profile, message, raw token or payload is logged. A successful posting call does not prove Android displayed an alert; inspect permission/channel settings as well.

Live FCM wakeup/process-death tests remain impossible until real configuration is supplied. Android force-stop generally blocks push delivery until the user launches the app again. No polling, permanent keepalive service, app-data reset or native-calling changes are used to mask that restriction.
