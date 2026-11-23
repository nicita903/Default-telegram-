# Native message notifications

Uses the existing official TDLib JSON client pinned to c8828d50f5eac2fc7089ce1089d7ce9095c61b5e. Only updateNotificationGroup/updateNotification/updateActiveNotifications create message notifications. updateHavePendingNotifications coordinates background completion. notification_group_count_max and notification_group_size_max are configured only after authorization and successful account loading. TDLib queues setOption requests before setTdlibParameters, so initialization must never await them first. Application starts authentication independently; notification/Firebase failures are isolated and logged using fixed, non-sensitive diagnostic messages. No second updateNewMessage alert path exists.

Channels: messages_v1, groups_v1, mentions_v1. Android owns sounds/vibration and lock-screen preferences. No fake calls or permanent connection service. Settings > Notifications explains and requests Android 13 notification access once; denial never blocks messaging. Hidden previews hide both content and identity, omit conversation shortcuts/avatars and disable notification actions that could disclose content.

Conversation tags and PendingIntent data include provider/account/chat. Native RemoteInput replies use the existing sendText adapter and require the original current Telegram account at the transport boundary. A consumed action nonce is persisted before sending: uncertain sends are never automatically repeated. TDLib success/failure events update reply state. Read calls viewMessages(force_read=true, messageSourceNotification); mute copies all existing chat notification fields and changes only mute_for/use_default_mute_for. Swiping away removes TDLib notification groups without marking chat read.

## Push setup required

The app contains FirebaseMessagingService, token refresh/registerDevice(encrypt=true), processPushNotification, and bounded expedited WorkManager handoffs. All queued payloads/reply text are encrypted with an Android Keystore AES-GCM key in noBackupFilesDir; only opaque filenames enter WorkManager. Authentication restores the same existing encrypted TDLib session. No polling or permanent foreground service is used. Firebase is optional and not initialized when configuration is absent.

1. Register Android package com.vector.messenger in your actual Firebase project. Obtain the real Android client configuration (google-services.json). No Google Services Gradle plugin is required for this project: create the ignored app/src/main/res/values/firebase-config.xml with the corresponding resources from that client:

```xml
<resources>
    <string name="google_app_id" translatable="false">YOUR mobilesdk_app_id</string>
    <string name="gcm_defaultSenderId" translatable="false">YOUR project_number</string>
    <string name="google_api_key" translatable="false">YOUR current_key</string>
    <string name="project_id" translatable="false">YOUR project_id</string>
</resources>
```

Use matching project_info/client values from your downloaded configuration, XML-escape them, and rebuild. Never put a service-account private key in the application. Client configuration ships in the APK; restrict the Firebase API key to the intended Android app.
2. Configure the matching Android push sender/server authorization for your Telegram application at my.telegram.org as required by Telegram. Firebase token registration alone is insufficient. Confirm Telegram accepts the sender configuration available for your developer application.
3. Run on a Google Play Services device, authenticate, allow notifications in Settings, and reopen Default. Token refresh registers the current account. Encrypted data pushes are passed only to TDLib; do not send Firebase notification-display payloads, which Android could display independently of TDLib/privacy settings.
4. Verify real messages with another Telegram account while foregrounded, backgrounded and after process termination. Android force-stop disables delivery until the user launches the app again. OEM power restrictions, connectivity and push quotas can delay execution.

Device testing has not been performed for this implementation. Verify direct replies and failure handling, mark-read across other clients, mute/unmute, permission denial, hidden previews/lock screen, native grouping, cached avatars, correct-account cold starts and push delivery before reporting end-to-end reliability. Existing cached small avatars use MediaImages; notifications never fetch full-size avatars. Without Firebase and Telegram sender configuration, live TDLib notifications work while its process/connection remains alive; process-death delivery is unavailable.

Official references: https://core.telegram.org/tdlib/options ; https://github.com/tdlib/td/blob/c8828d50f5eac2fc7089ce1089d7ce9095c61b5e/td/generate/scheme/td_api.tl ; https://firebase.google.com/docs/cloud-messaging/android/client
