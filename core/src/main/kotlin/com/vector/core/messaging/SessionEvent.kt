package com.vector.core.messaging

/** Fixed non-sensitive timing markers, independent of Android and account data. */
enum class SessionEvent { ProcessStarted, ActivityCreated, AppForegrounded, AppBackgrounded, ActivityStopped,
    ClientCreated, ClientClosed, ReceiverStopped, NetworkChanged, AuthorizationReady, ConnectionConnecting,
    ConnectionUpdating, ConnectionWaiting, ConnectionReady, CachedChatsAvailable, CachedMessagesAvailable,
    ChatUiVisible, ConversationUiVisible, FreshMessagesApplied, DispatchDelayed }
