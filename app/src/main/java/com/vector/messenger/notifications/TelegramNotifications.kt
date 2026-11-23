package com.vector.messenger.notifications

import android.app.*
import android.content.*
import android.graphics.Color
import android.net.Uri
import androidx.core.app.*
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.vector.core.auth.*
import com.vector.core.ConversationIdentity
import com.vector.core.profile.ProfilePhoto
import com.vector.core.profile.ProfileRepository
import com.vector.messenger.MainActivity
import com.vector.messenger.R
import com.vector.messenger.telegram.*
import com.vector.messenger.feature.media.MediaImages
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.json.JSONArray
import java.util.UUID

/** One consumer of TDLib's dedicated notification stream. Never synthesizes alerts from updateNewMessage. */
@Singleton
class TelegramNotifications @Inject constructor(@ApplicationContext private val context: Context,
    private val manager: TdlibClientManager, private val auth: AuthenticationRepository, private val profile: ProfileRepository,
    val preferences: NotificationPreferenceStore, private val scope: CoroutineScope) {
    private data class Group(val id: Int, val chat: Long, val mention: Boolean, var count: Int = 0,
        val messages: MutableMap<Int, JSONObject> = linkedMapOf(), var sound: Boolean = false, var settingsChat: Long = chat)
    private val groups = linkedMapOf<Int, Group>()
    private val chats = mutableMapOf<Long, JSONObject>()
    private val scopes = mutableMapOf<String, JSONObject>()
    private data class Sender(val name: String, val key: String, val photo: ProfilePhoto? = null)
    private val senders = mutableMapOf<String, Sender>()
    private val senderPhotos = mutableMapOf<String, android.graphics.Bitmap>()
    private val senderPhotoFiles = mutableMapOf<Int, String>()
    private val senderChats = mutableMapOf<String, MutableSet<Long>>()
    private val dismissedThrough = mutableMapOf<String, Int>()
    private val readThrough = mutableMapOf<Long, Long>()
    private val renderVersions = mutableMapOf<Long, Long>()
    private val tappedGroups = mutableMapOf<ConversationIdentity, IntArray>()
    private val photos = mutableMapOf<Long, android.graphics.Bitmap>()
    private val photoFiles = mutableMapOf<Int, Long>()
    private val posted = mutableSetOf<Long>()
    private val notices = mutableMapOf<Long, String>()
    private val replies = mutableMapOf<Long, Long>()
    private val replyResults = linkedMapOf<Long, Boolean>()
    private val actionGuard = Mutex()
    private val storage = context.getSharedPreferences("notification_actions", Context.MODE_PRIVATE)
    private val system = context.getSystemService(NotificationManager::class.java)
    private var owner = 0L
    private var started = false
    private var pending = MutableStateFlow(false)
    private var revision = 0L
    private val account = MutableStateFlow(0L)
    val accountId = account.asStateFlow()
    var foreground = false
        set(value) {
            if (field == value) return
            field = value
            if (value) activeConversation?.let { dismissConversation(it) }
        }
    var activeConversation: ConversationIdentity? = null
        set(value) {
            if (field == value) return
            field = value
            if (foreground && value != null) dismissConversation(value)
        }
    private val destination = MutableStateFlow<NotificationDestination?>(null)
    val destinationState = destination.asStateFlow()
    private val pushToken = MutableStateFlow<Pair<String, Long>?>(null)
    val pushState = MutableStateFlow("Push configuration required")
    private var registered: Pair<Long, String>? = null
    private fun tag(chat: Long, account: Long = owner) = "default:telegram:$account:$chat"
    private fun shortcut(chat: Long) = "telegram:$owner:$chat"
    private fun groupKey() = "default:telegram:$owner"
    fun start() {
        if (started) return
        started = true
        scope.launch(Dispatchers.IO) {
          try {
            listOf(Triple("messages_v1", "Messages", NotificationManager.IMPORTANCE_DEFAULT),
            Triple("groups_v1", "Groups", NotificationManager.IMPORTANCE_DEFAULT),
            Triple("mentions_v1", "Mentions and replies", NotificationManager.IMPORTANCE_HIGH)).forEach { (id, name, importance) ->
            if (system.getNotificationChannel(id) == null) system.createNotificationChannel(NotificationChannel(id, name, importance).apply { lockscreenVisibility = Notification.VISIBILITY_PRIVATE })
        }
          } catch (_: Exception) { diagnostic("Channel setup failed; message synchronization is unaffected") }
        }
        // The manager buffers updates. Account-dependent work starts only after getMe succeeds.
        scope.launch {
            profile.state.map { if (it.authenticated) it.profile?.id else null }.distinctUntilChanged().collectLatest { id ->
                if (owner != 0L && owner != id) {
                    try { clearNative() }
                    catch (_: Exception) { diagnostic("Native notification cleanup failed") }
                }
                owner = id ?: 0; account.value = owner
                groups.clear(); chats.clear(); scopes.clear(); senders.clear(); senderPhotos.clear(); senderPhotoFiles.clear(); senderChats.clear()
                dismissedThrough.clear(); readThrough.clear(); renderVersions.clear(); photos.clear(); photoFiles.clear(); posted.clear(); notices.clear(); replies.clear(); replyResults.clear(); registered = null
                if (id == null) return@collectLatest
                // Options are optional account work, never prerequisites for setTdlibParameters/getMe.
                try {
                    for (name in listOf("notification_group_count_max", "notification_group_size_max"))
                        checked(request("setOption", "name" to name, "value" to request("optionValueInteger", "value" to 25)), id)
                } catch (cancelled: CancellationException) { throw cancelled }
                  catch (_: Exception) { diagnostic("TDLib notification options failed; account remains available") }
                manager.notificationUpdates.collect { update ->
                    try { reduce(update) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { diagnostic("Notification update failed") }
                }
            }
        }
        scope.launch { preferences.state.collect {
            if (owner != 0L) try { renderAll(false) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { diagnostic("Notification preference refresh failed") }
        } }
        scope.launch { combine(profile.state, pushToken) { account, token -> account.profile?.id?.takeIf { account.authenticated } to token }
            .distinctUntilChanged().collectLatest { (account, tokenEvent) ->
                val token = tokenEvent?.first
                if (account != null && token != null && registered != (account to token)) {
                    try {
                        checked(request("registerDevice", "device_token" to request("deviceTokenFirebaseCloudMessaging", "token" to token, "encrypt" to true), "other_user_ids" to JSONArray()), account)
                        registered = account to token; pushState.value = "Registered · device verification required"
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { diagnostic("Push registration failed"); pushState.value = "Push registration failed · reopen Default to retry" }
                }
            }
        }
        // Application/AuthenticationRepository owns the shared session startup.
        scope.launch { profile.state.map { it.authenticated && it.profile != null }.distinctUntilChanged().collect { ready -> if (ready) refreshPushToken() } }
    }
    private fun diagnostic(message: String) { android.util.Log.w("DefaultNotifications", message) }
    fun refreshPushToken() {
        if (!profile.state.value.authenticated || profile.state.value.profile == null) return
        try {
            if (com.google.firebase.FirebaseApp.getApps(context).isEmpty()) return
            com.google.firebase.messaging.FirebaseMessaging.getInstance().apply {
                isAutoInitEnabled = true
                token.addOnSuccessListener { token(it) }.addOnFailureListener {
                    pushState.value = "Push token unavailable"; diagnostic("Push token fetch failed")
                }
            }
        } catch (_: Exception) { pushState.value = "Push unavailable"; diagnostic("Firebase initialization failed") }
          catch (_: LinkageError) { pushState.value = "Push unavailable"; diagnostic("Firebase dependency unavailable") }
    }
    fun token(value: String) { pushToken.update { value to ((it?.second ?: 0) + 1) } }
    fun accept(intent: Intent?) {
        if (intent?.action != "com.vector.messenger.OPEN_CONVERSATION") return
        val account = intent.getLongExtra("account", 0); val chat = intent.getLongExtra("chat", 0)
        if (account != 0L && chat != 0L) {
            intent.getIntArrayExtra("groups")?.let { tappedGroups[identity(chat, account)] = it }
            destination.value = NotificationDestination(account, chat, intent.getStringExtra("nonce") ?: UUID.randomUUID().toString())
        }
    }
    fun destinationHandled(value: NotificationDestination) { if (destination.value == value) destination.value = null }
    suspend fun resolveDestination(value: NotificationDestination): Boolean = withContext(Dispatchers.Main.immediate) {
        val account = awaitAccount() ?: return@withContext false
        if (account != value.account) return@withContext false
        try {
            val chat = checked(request("getChat", "chat_id" to value.chat), account)
            if (owner != account) return@withContext false
            chats[value.chat] = chat
            dismissConversation(identity(value.chat, account))
            true
        }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { false }
    }
    private suspend fun awaitAccount(): Long? = withTimeoutOrNull(20_000) {
        profile.state.first { it.authenticated && it.profile != null }.profile?.id
    }
    private fun clearNative() {
        system.activeNotifications.filter { it.tag?.startsWith("default:telegram:") == true }.forEach { system.cancel(it.tag, it.id) }
        ShortcutManagerCompat.removeLongLivedShortcuts(context, ShortcutManagerCompat.getDynamicShortcuts(context).filter { it.id.startsWith("telegram:") }.map { it.id })
    }
    private fun identity(chat: Long, account: Long = owner) = ConversationIdentity("telegram", account.toString(), chat.toString())
    private fun viewing(chat: Long, account: Long = owner) = foreground && activeConversation == identity(chat, account)
    private fun dismissalKey(account: Long, group: Int) = "dismissed:telegram:$account:$group"
    private fun dismissed(group: Int): Int {
        val key = dismissalKey(owner, group)
        return dismissedThrough.getOrPut(key) { storage.getInt(key, 0) }
    }
    private fun rememberDismissal(account: Long, group: Int, through: Int): Boolean {
        if (through <= 0) return false
        val key = dismissalKey(account, group)
        val previous = dismissedThrough[key] ?: storage.getInt(key, 0)
        if (through <= previous) return false
        dismissedThrough[key] = through
        storage.edit().putInt(key, through).apply()
        return true
    }
    /** Dismissal is not a read receipt. Native removal is immediate; TDLib cleanup is optional async work. */
    private fun dismissConversation(target: ConversationIdentity, sync: Boolean = true) {
        val account = target.accountId.toLongOrNull() ?: return
        val chat = target.subjectId.toLongOrNull() ?: return
        if (target.providerId != "telegram" || account == 0L || account != owner) return
        renderVersions[chat] = (renderVersions[chat] ?: 0) + 1
        try {
            val native = system.activeNotifications.firstOrNull { it.tag == tag(chat, account) && it.id == 1 }
            val known = groups.values.filter { it.chat == chat }.map { it.id to (it.messages.keys.maxOrNull() ?: 0) }
            val tapped = tappedGroups.remove(target)
            val tracked = chat in posted
            val restored = listOfNotNull(native?.notification?.extras?.getIntArray("default.notification.groups"), tapped)
                .flatMap { it.toList().chunked(2) }.filter { it.size == 2 }.map { it[0] to it[1] }
            val maxima = (known + restored).groupBy({ it.first }, { it.second }).mapValues { it.value.max() }
            val newlyDismissed = maxima.filter { (group, through) -> rememberDismissal(account, group, through) }
            cancel(chat, native != null)
            if (!tracked && native == null && tapped != null) summary(chat)
            if (sync && newlyDismissed.isNotEmpty()) scope.launch {
                for ((group, through) in newlyDismissed) {
                    if (owner != account) return@launch
                    try { checked(request("removeNotificationGroup", "notification_group_id" to group, "max_notification_id" to through), account) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { diagnostic("Conversation notification dismissal could not sync; local dismissal retained") }
                }
            }
        } catch (_: Exception) { diagnostic("Conversation notification dismissal failed") }
    }
    private fun visibleNotification(group: Group, raw: JSONObject): Boolean = raw.optInt("id") > dismissed(group.id) &&
        messageId(raw).let { it == 0L || it > (readThrough[group.chat] ?: 0L) }
    private suspend fun checked(value: JSONObject, account: Long = owner): JSONObject {
        val result = manager.messagingRequest(value, account)
        check(result.optString("@type") != "error") { "Notification operation unavailable" }
        return result
    }
    private fun isSupported(type: JSONObject?) = type?.optString("@type") in setOf("notificationGroupTypeMessages", "notificationGroupTypeMentions")
    private suspend fun reduce(value: JSONObject) {
        when (value.optString("@type")) {
            "updateHavePendingNotifications" -> pending.value = value.optBoolean("have_delayed_notifications") || value.optBoolean("have_unreceived_notifications")
            "updateActiveNotifications" -> {
                groups.clear()
                value.optJSONArray("groups")?.objects()?.filter { isSupported(it.optJSONObject("type")) }?.forEach { raw ->
                    val g = Group(raw.optInt("id"), raw.optLong("chat_id"), raw.optJSONObject("type")?.optString("@type") == "notificationGroupTypeMentions", raw.optInt("total_count"))
                    raw.optJSONArray("notifications")?.objects()?.forEach { g.messages[it.optInt("id")] = it }; groups[g.id] = g
                }
                val valid = groups.values.map { tag(it.chat) }.toSet()
                system.activeNotifications.filter { it.tag?.startsWith("default:telegram:") == true && it.tag !in valid }.forEach { system.cancel(it.tag, it.id) }
                renderAll(false)
            }
            "updateNotificationGroup" -> {
                if (!isSupported(value.optJSONObject("type"))) return
                val id = value.optInt("notification_group_id"); val chat = value.optLong("chat_id")
                val mention = value.optJSONObject("type")?.optString("@type") == "notificationGroupTypeMentions"
                groups[id]?.takeIf { it.chat != chat || it.mention != mention }?.let { old -> groups.remove(id); render(old.chat, false) }
                val g = groups.getOrPut(id) { Group(id, chat, mention) }
                val added = value.optJSONArray("added_notifications")?.objects().orEmpty()
                val existing = groups.values.filter { it.chat == chat }.flatMap { it.messages.values }.map(::messageId).toSet()
                val unseen = added.any { messageId(it) !in existing }
                value.optJSONArray("removed_notification_ids")?.let { ids -> for (i in 0 until ids.length()) g.messages.remove(ids.optInt(i)) }
                added.forEach { g.messages[it.optInt("id")] = it }; g.count = value.optInt("total_count")
                g.sound = value.optLong("notification_sound_id") != 0L
                g.settingsChat = value.optLong("notification_settings_chat_id").takeIf { it != 0L } ?: chat
                if (g.count == 0) groups.remove(id)
                revision++; render(chat, unseen && g.sound && added.any { !it.optBoolean("is_silent") })
            }
            "updateNotification" -> {
                val g = groups[value.optInt("notification_group_id")] ?: return
                value.optJSONObject("notification")?.let { g.messages[it.optInt("id")] = it }; revision++; render(g.chat, false)
            }
            "updateChatReadInbox" -> {
                val chat = value.optLong("chat_id")
                readThrough[chat] = maxOf(readThrough[chat] ?: 0L, value.optLong("last_read_inbox_message_id"))
                if (value.optInt("unread_count") == 0) dismissConversation(identity(chat), sync = false)
                else render(chat, false)
            }
            "updateUser" -> value.optJSONObject("user")?.let { user ->
                val key = "user:${user.optLong("id")}"
                if (key !in senders) return
                senders.remove(key); senderPhotos.remove(key)
                senderChats[key]?.toList()?.forEach { render(it, false) }
            }
            "updateScopeNotificationSettings" -> {
                val type = value.optJSONObject("scope")?.optString("@type")
                if (type != null) value.optJSONObject("notification_settings")?.let { scopes[type] = it }
                chats.clear(); renderAll(false)
            }
            "updateChatNotificationSettings", "updateChatPhoto" -> { chats.clear(); photos.clear(); renderAll(false) }
            "updateFile" -> value.optJSONObject("file")?.let { file ->
                val fileId = file.optInt("id")
                if (fileId !in photoFiles && fileId !in senderPhotoFiles) return
                val photo = withContext(Dispatchers.IO) { manager.messagingPhoto(file) } ?: return
                if (photo.localPath == null) return
                photoFiles[file.optInt("id")]?.let { chat -> chats.remove(chat); render(chat, false) }
                senderPhotoFiles[file.optInt("id")]?.let { key ->
                    senders[key]?.let { senders[key] = it.copy(photo = photo) }
                    senderChats[key]?.toList()?.forEach { render(it, false) }
                }
            }
            "updateMessageSendSucceeded", "updateMessageSendFailed" -> {
                val id = value.optLong("old_message_id"); val message = value.optJSONObject("message")
                val succeeded = value.optString("@type") == "updateMessageSendSucceeded"
                val chat = replies.remove(id) ?: message?.optLong("id")?.let { replies.remove(it) }
                if (chat == null) { replyResults[id] = succeeded; while (replyResults.size > 64) replyResults.remove(replyResults.keys.first()); return }
                notices[chat] = if (succeeded) "Reply sent" else "Reply failed · open Default to retry"
                render(chat, false)
            }
        }
    }
    private suspend fun optional(value: JSONObject, timeout: Long): JSONObject? = try {
        withTimeoutOrNull(timeout) { checked(value) }
    } catch (cancelled: CancellationException) { throw cancelled }
      catch (_: Exception) { null }
    private suspend fun scopeSettings(type: String): JSONObject? = scopes[type] ?: optional(
        request("getScopeNotificationSettings", "scope" to request(type)), 2_000)?.also { scopes[type] = it }
    private suspend fun renderAll(alert: Boolean) { (groups.values.map { it.chat } + posted).distinct().forEach { render(it, alert) } }
    private suspend fun sender(value: JSONObject?, chat: Long): Sender {
        value ?: return Sender("Someone", "unknown")
        val user = value.optString("@type") == "messageSenderUser"
        val id = value.optLong(if (user) "user_id" else "chat_id"); val key = "${if (user) "user" else "chat"}:$id"
        senderChats.getOrPut(key) { mutableSetOf() }.add(chat)
        return senders[key] ?: run {
            val json = optional(request(if (user) "getUser" else "getChat", (if (user) "user_id" else "chat_id") to id), 2_000)
            val name = if (user) listOf(json?.optString("first_name").orEmpty(), json?.optString("last_name").orEmpty()).filter { it.isNotBlank() }.joinToString(" ") else json?.optString("title").orEmpty()
            val small = json?.optJSONObject(if (user) "profile_photo" else "photo")?.optJSONObject("small")?.let(manager::messagingPhoto)
            Sender(name.ifBlank { "Someone" }, key, small).also {
                if (name.isNotBlank()) senders[key] = it
                small?.let { photo -> senderPhotoFiles[photo.fileId] = key }
            }
        }
    }
    private fun preview(content: JSONObject?): String {
        content ?: return "New message"
        val type = content.optString("@type").removePrefix("message").removePrefix("pushMessageContent")
        if (type == "Text") return content.optJSONObject("text")?.optString("text") ?: content.optString("text", "New message")
        return when (type) { "Photo" -> "Photo"; "Video" -> "Video"; "VoiceNote" -> "Voice message"; "VideoNote" -> "Video message"; "Audio" -> "Audio file"; "Document" -> "Document"; "Sticker", "AnimatedEmoji" -> "Sticker"; "Animation" -> "GIF"; "Contact" -> "Contact"; "Location", "Venue" -> "Location"; "Poll" -> "Poll"; else -> "New message" }
    }
    private fun messageId(raw: JSONObject): Long = raw.optJSONObject("type")?.let { it.optJSONObject("message")?.optLong("id") ?: it.optLong("message_id") } ?: 0
    private suspend fun render(chatId: Long, alert: Boolean) {
        if (owner == 0L) return
        val account = owner
        val version = (renderVersions[chatId] ?: 0) + 1
        renderVersions[chatId] = version
        if (viewing(chatId, account)) { dismissConversation(identity(chatId, account)); return }
        val selected = groups.values.filter { it.chat == chatId && it.count > 0 }
        val prefs = preferences.state.value
        if (selected.isEmpty() || !prefs.enabled || !NotificationManagerCompat.from(context).areNotificationsEnabled()) { cancel(chatId); return }
        val chat = chats[chatId] ?: optional(request("getChat", "chat_id" to chatId), 3_000)?.also { chats[chatId] = it }
        if (account != owner) return
        val type = chat?.optJSONObject("type")
        val privateChat = type?.optString("@type") == "chatTypePrivate" || (type == null && chatId > 0)
        if (!privateChat && !prefs.groups) { cancel(chatId); return }
        val mention = selected.any { it.mention }
        val settings = chat?.optJSONObject("notification_settings")
        val inherited = if (settings?.optBoolean("use_default_mute_for") == true || settings?.optBoolean("use_default_show_preview") == true)
            scopeSettings(if (privateChat) "notificationSettingsScopePrivateChats" else if (type?.optBoolean("is_channel") == true) "notificationSettingsScopeChannelChats" else "notificationSettingsScopeGroupChats") else null
        val muted = (if (settings?.optBoolean("use_default_mute_for") == true) inherited?.optInt("mute_for") else settings?.optInt("mute_for")) ?: 0
        if (muted > 0 && !mention) { cancel(chatId); return }
        var serverPreview = if (settings?.optBoolean("use_default_show_preview") == true) inherited?.optBoolean("show_preview") == true else settings?.optBoolean("show_preview") == true
        // Mentions may use a different conversation's privacy settings, as specified by TDLib.
        selected.map { it.settingsChat }.distinct().filter { it != chatId }.forEach { settingsId ->
            val other = chats[settingsId] ?: optional(request("getChat", "chat_id" to settingsId), 2_000)?.also { chats[settingsId] = it }
            val otherSettings = other?.optJSONObject("notification_settings")
            val otherScope = if (otherSettings?.optBoolean("use_default_show_preview") == true)
                scopeSettings(if (other?.optJSONObject("type")?.optString("@type") == "chatTypePrivate") "notificationSettingsScopePrivateChats" else "notificationSettingsScopeGroupChats") else null
            serverPreview = serverPreview && (if (otherSettings?.optBoolean("use_default_show_preview") == true) otherScope?.optBoolean("show_preview") == true else otherSettings?.optBoolean("show_preview") == true)
        }
        val rows = mutableListOf<DefaultMessageNotification>()
        selected.flatMap { group -> group.messages.values.filter { visibleNotification(group, it) } }.forEach { raw ->
            val notification = raw.optJSONObject("type") ?: return@forEach
            val message = notification.optJSONObject("message")
            if (notification.optString("@type") !in setOf("notificationTypeNewMessage", "notificationTypeNewPushMessage") || (message ?: notification).optBoolean("is_outgoing")) return@forEach
            val allow = serverPreview && !prefs.hideContent && (if (message != null) notification.optBoolean("show_preview") else true)
            val from = if (allow) sender(message?.optJSONObject("sender_id") ?: notification.optJSONObject("sender_id"), chatId) else Sender("Default", "private")
            rows += DefaultMessageNotification("telegram", account.toString(), chatId, message?.optLong("id") ?: notification.optLong("message_id"),
                if (allow) notification.optString("sender_name").ifBlank { from.name } else "Default",
                if (allow) preview(message?.optJSONObject("content") ?: notification.optJSONObject("content")) else "New message", raw.optLong("date") * 1000, allow,
                senderKey = from.key, senderPhoto = from.photo)
        }
        val messages = rows.distinctBy { it.messageId }.sortedBy { it.timestamp }.takeLast(25)
        if (messages.isEmpty()) { cancel(chatId); return }
        if (owner != account || renderVersions[chatId] != version) return
        val visible = !prefs.hideContent && messages.all { it.showPreview }
        val title = if (visible) chat?.optString("title")?.ifBlank { messages.last().senderName } ?: messages.last().senderName else "Default"
        val avatar = if (visible) photos[chatId] else null
        val person = Person.Builder().setName(if (visible) profile.state.value.profile?.displayName ?: "You" else "Default").setKey("telegram:$account:self").build()
        val style = NotificationCompat.MessagingStyle(person).setGroupConversation(!privateChat)
        if (!privateChat && visible) style.setConversationTitle(title)
        messages.forEach { m ->
            val from = Person.Builder().setName(if (visible) m.senderName else "Default").setKey(if (visible) "telegram:$account:${m.senderKey}" else "private")
            val senderAvatar = if (visible) senderPhotos[m.senderKey] ?: if (privateChat) avatar else null else null
            senderAvatar?.let { from.setIcon(IconCompat.createWithBitmap(it)) }
            style.addMessage(if (visible) m.preview else "New message", m.timestamp, from.build())
        }
        val open = openIntent(account, chatId)
        val content = PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val channel = if (mention) "mentions_v1" else if (privateChat) "messages_v1" else "groups_v1"
        val builder = NotificationCompat.Builder(context, channel).setSmallIcon(R.drawable.ic_notification).setColor(Color.rgb(64, 191, 255))
            .setContentTitle(title).setContentText(if (visible) messages.last().preview else "New message").setStyle(style)
            .setWhen(messages.last().timestamp).setShowWhen(true).setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(if (visible) NotificationCompat.VISIBILITY_PRIVATE else NotificationCompat.VISIBILITY_SECRET)
            .setPublicVersion(NotificationCompat.Builder(context, channel).setSmallIcon(R.drawable.ic_notification).setContentTitle("Default").setContentText("New message").build())
            .setContentIntent(content).setAutoCancel(true).setNumber(messages.size).setGroup(groupKey())
            .addExtras(android.os.Bundle().apply {
                putIntArray("default.notification.groups", selected.flatMap { listOf(it.id, it.messages.keys.maxOrNull() ?: 0) }.toIntArray())
            })
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN).setOnlyAlertOnce(!alert)
            .setSilent(!alert).setDeleteIntent(actionIntent(account, chatId, "dismiss", false, messages.map { it.messageId }))
        notices[chatId]?.let(builder::setSubText)
        if (visible) {
            val reply = RemoteInput.Builder("reply").setLabel("Reply").build()
            if (chat?.optJSONObject("permissions")?.optBoolean("can_send_basic_messages") == true && type?.optBoolean("is_channel") != true) builder.addAction(NotificationCompat.Action.Builder(0, "Reply", actionIntent(account, chatId, "reply", true, messages.map { it.messageId })).addRemoteInput(reply).setAllowGeneratedReplies(true).setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY).build())
            builder.addAction(NotificationCompat.Action.Builder(0, "Mark read", actionIntent(account, chatId, "read", false, messages.map { it.messageId })).setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ).build())
            builder.addAction(NotificationCompat.Action.Builder(0, "Mute 1h", actionIntent(account, chatId, "mute", false, emptyList())).setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MUTE).build())
            avatar?.let(builder::setLargeIcon)
            val shortcut = ShortcutInfoCompat.Builder(context, shortcut(chatId)).setShortLabel(title).setLongLived(true).setIntent(open).setPerson(Person.Builder().setName(title).setKey("telegram:$account:$chatId").apply { if (avatar != null) setIcon(IconCompat.createWithBitmap(avatar)) }.build()).apply { if (avatar != null) setIcon(IconCompat.createWithBitmap(avatar)) }.build()
            ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
            builder.setShortcutId(shortcut.id)
        } else ShortcutManagerCompat.removeLongLivedShortcuts(context, listOf(shortcut(chatId)))
        if (account != owner || preferences.state.value != prefs || renderVersions[chatId] != version) return
        if (viewing(chatId, account)) { dismissConversation(identity(chatId, account)); return }
        NotificationManagerCompat.from(context).notify(tag(chatId), 1, builder.build()); posted += chatId
        if (visible && avatar == null) chat?.optJSONObject("photo")?.optJSONObject("small")?.let { file ->
            val photo = manager.messagingPhoto(file)
            if (photo != null) {
                photoFiles[photo.fileId] = chatId
                val localPath = photo.localPath
                if (localPath != null) {
                    val image = MediaImages.load(localPath, 128, account)?.bitmap
                    if (image != null && owner == account) { photos[chatId] = image; render(chatId, false) }
                }
                // Cached thumbnails only: notification delivery never downloads full profile photos.
            }
        }
        if (visible) {
            var changed = false
            for (m in messages.distinctBy { it.senderKey }) {
                if (senderPhotos.containsKey(m.senderKey)) continue
                val path = m.senderPhoto?.localPath ?: continue
                val bitmap = MediaImages.load(path, 128, account)?.bitmap ?: continue
                if (account != owner || preferences.state.value != prefs) return
                senderPhotos[m.senderKey] = bitmap
                while (senderPhotos.size > 32) senderPhotos.remove(senderPhotos.keys.first())
                changed = true
            }
            if (changed) { render(chatId, false); return }
        }
        summary()
    }
    private fun cancel(chat: Long, nativeKnown: Boolean? = null) {
        val tracked = posted.remove(chat)
        val exists = nativeKnown ?: if (tracked) true else system.activeNotifications.any { it.tag == tag(chat) && it.id == 1 }
        if (tracked || exists) { system.cancel(tag(chat), 1); summary(chat) }
    }
    private fun summary(excluding: Long? = null) {
        val summaryTag = "default:telegram:$owner:summary"
        // Reconcile the native set too: a tap may have auto-cancelled a child after process death.
        val active = system.activeNotifications.filter { it.id == 1 && it.tag?.startsWith("default:telegram:$owner:") == true }.mapNotNull { it.tag?.substringAfterLast(':')?.toLongOrNull() }.toSet()
        posted.addAll(active.filter { it != excluding })
        if (excluding != null) posted.remove(excluding)
        if (posted.size < 2) { system.cancel(summaryTag, 2); return }
        val b = NotificationCompat.Builder(context, "messages_v1").setSmallIcon(R.drawable.ic_notification).setContentTitle("Default")
            .setContentText("${posted.size} conversations").setGroup(groupKey()).setGroupSummary(true).setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN).setSilent(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        if (NotificationManagerCompat.from(context).areNotificationsEnabled()) NotificationManagerCompat.from(context).notify(summaryTag, 2, b.build())
    }
    private fun openIntent(account: Long, chat: Long) = Intent(context, MainActivity::class.java).setAction("com.vector.messenger.OPEN_CONVERSATION")
        .setData(Uri.parse("default://telegram/$account/$chat")).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        .putExtra("account", account).putExtra("chat", chat).putExtra("nonce", UUID.randomUUID().toString())
        .putExtra("groups", groups.values.filter { it.chat == chat }.flatMap { listOf(it.id, it.messages.keys.maxOrNull() ?: 0) }.toIntArray())
    private fun actionIntent(account: Long, chat: Long, action: String, mutable: Boolean, ids: List<Long>): PendingIntent {
        val intent = Intent(context, NotificationActionReceiver::class.java).setAction(action).setData(Uri.parse("default://telegram/$account/$chat/$action"))
            .putExtra("account", account).putExtra("chat", chat).putExtra("messages", ids.toLongArray()).putExtra("nonce", UUID.randomUUID().toString())
            .putExtra("groups", groups.values.filter { it.chat == chat }.flatMap { listOf(it.id, it.messages.keys.maxOrNull() ?: 0) }.toIntArray())
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE)
    }
    suspend fun action(intent: Intent) = withContext(Dispatchers.Main.immediate) { actionGuard.withLock {
        val account = intent.getLongExtra("account", 0); val chat = intent.getLongExtra("chat", 0)
        val nonce = intent.getStringExtra("nonce") ?: return@withLock
        val consumed = storage.getStringSet("consumed", emptySet()).orEmpty()
        if (nonce in consumed || chat == 0L || account == 0L) return@withLock
        if (awaitAccount() != account || owner != account) return@withLock
        if (!withContext(Dispatchers.IO) { storage.edit().putStringSet("consumed", (consumed.toList().takeLast(127) + nonce).toSet()).commit() }) return@withLock
        val ids = intent.getLongArrayExtra("messages")?.toList().orEmpty()
        try {
            when (intent.action) {
                "reply" -> {
                    if (preferences.state.value.hideContent) return@withLock
                    val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence("reply")?.toString()?.trim().orEmpty()
                    if (text.isBlank() || text.length > 4096) return@withLock
                    val result = TdlibMessagingClient(manager, account).sendText(chat, text, null)
                    val outcome = replyResults.remove(result.id)
                    if (outcome == null && result.delivery == com.vector.core.Delivery.Sending) replies[result.id] = chat
                    notices[chat] = when { outcome == false || result.delivery == com.vector.core.Delivery.Failed -> "Reply failed · open Default to retry"; outcome == true || result.delivery != com.vector.core.Delivery.Sending -> "Reply sent"; else -> "Reply sending…" }
                    render(chat, false)
                }
                "read" -> { checked(request("viewMessages", "chat_id" to chat, "message_ids" to JSONArray(ids), "source" to request("messageSourceNotification"), "force_read" to true), account) }
                "mute" -> {
                    val info = checked(request("getChat", "chat_id" to chat), account)
                    val settings = info.getJSONObject("notification_settings")
                    settings.put("use_default_mute_for", false).put("mute_for", 3600)
                    checked(request("setChatNotificationSettings", "chat_id" to chat, "notification_settings" to settings), account); chats.remove(chat); render(chat, false)
                }
                "dismiss" -> {
                    intent.getIntArrayExtra("groups")?.toList()?.chunked(2)?.filter { it.size == 2 }?.forEach {
                        rememberDismissal(account, it[0], it[1])
                        checked(request("removeNotificationGroup", "notification_group_id" to it[0], "max_notification_id" to it[1]), account)
                    }
                    render(chat, false)
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { notices[chat] = "Action failed · open Default to retry"; try { render(chat, false) } catch (_: Exception) { } }
    } }
    suspend fun push(payload: String): Boolean {
        start()
        val response = withTimeoutOrNull(25_000) { manager.processNotificationPush(payload) } ?: return false
        if (response.optString("@type") == "error" && response.optInt("code") != 406) return false
        // 406 requires the restored TDLib connection; do not turn the push payload into a second alert.
        if (awaitAccount() == null) return auth.state.value.step !is AuthStep.Ready
        if (response.optInt("code") == 406) {
            withTimeoutOrNull(20_000) { manager.connection.first { it == com.vector.core.profile.ProfileConnection.Ready } } ?: return false
        }
        manager.flushNotificationUpdates()
        withTimeoutOrNull(15_000) { pending.first { !it } } ?: return false
        return true
    }
}
