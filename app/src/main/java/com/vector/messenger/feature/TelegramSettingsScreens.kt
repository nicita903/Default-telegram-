package com.vector.messenger.feature

import android.app.Activity
import android.content.Intent
import android.provider.Settings
import android.view.WindowManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.*
import com.vector.core.Chat
import com.vector.messenger.design.*
import com.vector.messenger.calling.CallIcons
import com.vector.messenger.telegram.canonicalJson
import kotlinx.coroutines.delay
import org.json.JSONObject
import org.json.JSONArray

internal data class TelegramSettingLink(val route: String, val title: String, val group: String, val words: String)
internal val telegramSettingLinks = listOf(
    TelegramSettingLink("tg_blocked", "Blocked Users", "Privacy", "block unblock contact spam"),
    TelegramSettingLink("tg_privacy_phone", "Phone Number", "Privacy", "number discovery find contacts"),
    TelegramSettingLink("tg_privacy_seen", "Last Seen", "Privacy", "last seen online activity visibility exceptions"),
    TelegramSettingLink("tg_privacy_photos", "Profile Photos", "Privacy", "avatar picture visibility"),
    TelegramSettingLink("tg_privacy_invites", "Groups & Channels", "Privacy", "invite add exceptions"),
    TelegramSettingLink("tg_privacy_calls", "Calls", "Privacy", "who can call me privacy audience"),
    TelegramSettingLink("tg_privacy_p2p", "Peer-to-peer", "Privacy", "peer-to-peer calls p2p direct ip relay"),
    TelegramSettingLink("tg_privacy_forward", "Forwarded Messages", "Privacy", "forward link account"),
    TelegramSettingLink("tg_password", "Two-Step Verification", "Security", "password recovery email code"),
    TelegramSettingLink("tg_passcode", "Passcode Lock", "Security", "local lock biometric fingerprint auto lock"),
    TelegramSettingLink("devices", "Devices", "Security", "active sessions devices terminate sessions"),
    TelegramSettingLink("tg_ghost", "Ghost Mode", "Default", "ghost typing recording status"),
    TelegramSettingLink("tg_notifications_private", "Private chats", "Notifications", "telegram notifications alerts private messages preview mute sound"),
    TelegramSettingLink("tg_notifications_groups", "Groups", "Notifications", "telegram notifications alerts group preview mute sound"),
    TelegramSettingLink("tg_notifications_channels", "Channels", "Notifications", "telegram notifications alerts channel preview mute sound"),
    TelegramSettingLink("tg_downloads", "Automatic downloads", "Storage", "automatic media downloads mobile wifi roaming download limits"),
    TelegramSettingLink("tg_cache", "Clear Downloaded Thumbnails", "Storage", "cache clear storage thumbnails")
)

@Composable internal fun TelegramPrivacyIndex(onNavigate: (String) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp), contentPadding = PaddingValues(bottom = 28.dp)) {
        val groups = listOf(
            "Who can see me?" to listOf("tg_privacy_seen", "tg_privacy_phone", "tg_privacy_photos", "tg_privacy_forward"),
            "Who can contact me?" to listOf("tg_privacy_invites", "tg_privacy_calls", "tg_blocked"),
            "Security" to listOf("tg_password", "tg_passcode", "devices"),
            "Default" to listOf("tg_ghost"))
        groups.forEach { (group, routes) ->
            item("heading:$group") { SettingsHeading(group) }
            items(routes, key = { it }) { route ->
                val link = telegramSettingLinks.first { it.route == route }
                CompactSettingsRow(link.title, icon = SettingsIcons.route(route)) { onNavigate(SettingsRoutes.category(route)) }
            }
        }
    }
}

@Composable internal fun SettingsNote(text: String) { Text(text, color = DefaultDesign.colors.secondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp)) }

@Composable internal fun TelegramSettingsPage(page: String, viewModel: PreferencesViewModel, onNavigate: (String) -> Unit = {}) {
    val repo = viewModel.telegram
    val state by repo.state.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    val refreshPage = if (page.startsWith("tg_notifications_")) "tg_notifications" else page
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { repo.refresh(refreshPage) }
    LaunchedEffect(page, state.account) {
        repo.refresh(refreshPage)
        while (true) { delay(30_000); if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) repo.refresh(refreshPage) }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        if (page == "tg_passcode") { PasscodeSettings(viewModel.appLock); return@Column }
        if (state.account == 0L) {
            SettingsNote("Sign in to manage your Telegram account settings.")
            val account = com.vector.messenger.feature.avatar.LocalAccountAction.current
            DefaultAction("Telegram account", account)
            return@Column
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(if (state.busy) "Updating…" else "", color = DefaultDesign.colors.secondary, style = MaterialTheme.typography.labelSmall)
            DefaultIconControl(VectorIcons.Refresh, "Refresh account settings", { repo.refresh(refreshPage) }, enabled = !state.busy, quiet = true)
        }
        state.error?.let { SettingsNote(it) }
        when {
            page.startsWith("tg_privacy_") -> PrivacyEditor(page, repo, state, onNavigate)
            page == "tg_blocked" -> BlockedUsers(repo, state)
            page == "devices" -> ActiveSessions(repo, state)
            page == "tg_password" -> PasswordSettings(repo, state)
            page.startsWith("tg_notifications") -> TelegramNotificationScopes(repo, state, page.removePrefix("tg_notifications_"))
            page == "tg_downloads" -> DownloadSettings(repo, state)
            page == "tg_cache" -> ThumbnailCache(viewModel, repo, state)
        }
    }
}

@Composable private fun PrivacyEditor(page: String, repo: TelegramSettingsRepository, state: TelegramSettingsState, onNavigate: (String) -> Unit) {
    var picker by remember { mutableStateOf<Pair<PrivacySetting, Boolean>?>(null) }
    var removal by remember { mutableStateOf<Triple<PrivacySetting, Boolean, BlockedAccount>?>(null) }
    var pending by remember { mutableStateOf<Triple<PrivacySetting, Boolean, BlockedAccount>?>(null) }
    var exceptions by remember { mutableStateOf<Pair<PrivacySetting, Boolean>?>(null) }
    val settings = telegramPrivacy.filter { it.id == page.removePrefix("tg_privacy_") || (page == "tg_privacy_phone" && it.id == "discovery") }
    LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
        for (setting in settings) {
            val raw = state.privacy[setting.id] ?: continue
            item("${setting.id}:heading") { SettingsHeading(when (setting.id) {
                "seen" -> "Who can see my activity?"; "phone" -> "Who can see my number?"; "discovery" -> "Who can find me by my number?"
                "photos" -> "Who can see my photos?"; "invites" -> "Who can add me?"; "calls" -> "Who can call me?"
                "p2p" -> "Allow direct connections with"; "forward" -> "Who can link to my account?"; else -> setting.title
            }) }
            item("${setting.id}:audience") {
                Column(Modifier.selectableGroup()) {
                    listOf("Everybody", "My Contacts", "Nobody").filter { !setting.contactsOnly || it != "Nobody" }.forEach { label ->
                        SettingsRadioRow(label, PrivacyRules.audience(raw) == label, enabled = !state.busy) { repo.setAudience(setting.id, raw, label) }
                    }
                }
                if (PrivacyRules.audience(raw) == "Custom rules") SettingsNote("Custom Telegram rules are active.")
                if (setting.description.isNotEmpty()) SettingsNote(setting.description)
            }
            if (!setting.contactsOnly) item("${setting.id}:exceptions") { SettingsHeading("Exceptions") }
            if (!setting.contactsOnly) for (allow in listOf(true, false)) {
                item("${setting.id}:$allow") {
                    val prefix = "userPrivacySettingRule" + if (allow) "Allow" else "Restrict"
                    val count = JSONArray(raw).jsonObjects().filter { it.optString("@type") in listOf(prefix + "Users", prefix + "ChatMembers") }
                        .sumOf { (it.optJSONArray("user_ids") ?: it.optJSONArray("chat_ids"))?.length() ?: 0 }
                    CompactSettingsRow(if (allow) "Always Allow" else "Never Allow", count.toString(), icon = if (allow) SettingsIcons.User else SettingsIcons.Block,
                        enabled = !state.busy) { exceptions = setting to allow }
                }
            }
            item("${setting.id}:extra") {
                val special = JSONArray(raw).jsonObjects().filter { it.optString("@type") !in PrivacyRules.baseTypes && it.optJSONArray("user_ids") == null && it.optJSONArray("chat_ids") == null }
                if (special.isNotEmpty()) SettingsExplanation("Other Telegram rules", special.joinToString { it.optString("@type").removePrefix("userPrivacySettingRule").replace(Regex("([a-z])([A-Z])"), "$1 $2") } + ". These rules are preserved.")
                if (setting.id == "seen") SettingsExplanation("Ghost Mode & activity", "Ghost Mode also manages last-seen exceptions. Review both before changing shared Telegram rules.")
                if (setting.id == "calls") CompactSettingsRow("Peer-to-peer", icon = CallIcons.Phone) { onNavigate(SettingsRoutes.category("tg_privacy_p2p")) }
            }
        }
    }
    exceptions?.let { (setting, allow) ->
        val raw = state.privacy[setting.id].orEmpty().ifBlank { "[]" }
        val prefix = "userPrivacySettingRule" + if (allow) "Allow" else "Restrict"
        val peers = JSONArray(raw).jsonObjects().filter { it.optString("@type") in listOf(prefix + "Users", prefix + "ChatMembers") }.flatMap { rule ->
            val user = rule.optJSONArray("user_ids") != null
            val ids = rule.getJSONArray(if (user) "user_ids" else "chat_ids")
            (0 until ids.length()).map { index -> val id = ids.getLong(index); state.peers["$user:$id"] ?: BlockedAccount(id, user, if (user) "Account $id" else "Group $id") }
        }.distinctBy { it.user to it.id }
        DefaultDialog(if (allow) "Always Allow" else "Never Allow", { exceptions = null }) {
            LazyColumn(Modifier.heightIn(max = 320.dp)) {
                if (peers.isEmpty()) item { SettingsNote("No exceptions yet.") }
                items(peers, key = { "${it.user}:${it.id}" }) { peer ->
                    CompactSettingsRow(peer.name, icon = SettingsIcons.User, subtitle = if (peer.user) "Remove exception" else "Group members · remove", enabled = !state.busy,
                        leading = { TelegramAvatar(peer.name, null, 36.dp, peer.id, peer.user) }) { exceptions = null; removal = Triple(setting, allow, peer) }
                }
            }
            DefaultAction("Add people or groups", { exceptions = null; picker = setting to allow; repo.loadContacts() }, enabled = !state.busy)
            DefaultAction("Done", { exceptions = null })
        }
    }
    picker?.let { (setting, allow) ->
        SettingsContactPicker(repo, state, includeGroups = true, onDismiss = { picker = null }) { peer -> picker = null; pending = Triple(setting, allow, peer) }
    }
    (pending ?: removal)?.let { (setting, allow, peer) ->
        val add = pending != null
        ConfirmSettingsAction(if (add) "Add exception?" else "Remove exception?", "${peer.name} · ${if (allow) "Always Allow" else "Never Allow"}", { pending = null; removal = null }) {
            state.privacy[setting.id]?.let { repo.setException(setting.id, it, allow, !peer.user, peer.id, add) }
            pending = null; removal = null
        }
    }
}

@Composable private fun SettingsContactPicker(repo: TelegramSettingsRepository, state: TelegramSettingsState, includeGroups: Boolean = false, onDismiss: () -> Unit, onSelect: (BlockedAccount) -> Unit) {
    var query by remember { mutableStateOf("") }
    val groups by repo.groups.collectAsStateWithLifecycle(emptyList())
    val candidates = (state.contacts + if (includeGroups) groups else emptyList()).filter { it.name.contains(query, true) && it.id != state.account }
    DefaultDialog("Choose ${if (includeGroups) "contact or group" else "contact"}", onDismiss) {
        DefaultInput(query, { query = it }, "Search contacts", framed = false)
        if (state.busy) SettingsNote("Loading Telegram contacts…")
        if (candidates.isEmpty() && !state.busy) SettingsNote("No matching contacts. Add contacts in Telegram first.")
        Box(Modifier.heightIn(max = 360.dp)) {
            // Reuses the same contact selector as new conversations; row IDs are local indices only.
            ContactsScreen(candidates.map { peer -> Chat(peer.id, peer.name, "", "", isContact = peer.user, userId = peer.id.takeIf { peer.user }) }) { row ->
                candidates.firstOrNull { it.id == row }?.let(onSelect)
            }
        }
        DefaultAction("Cancel", onDismiss)
    }
}

@Composable private fun BlockedUsers(repo: TelegramSettingsRepository, state: TelegramSettingsState) {
    var query by remember { mutableStateOf("") }
    var selecting by remember { mutableStateOf(false) }
    var confirmation by remember { mutableStateOf<Pair<BlockedAccount, Boolean>?>(null) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DefaultInput(query, { query = it }, "Search blocked users", Modifier.weight(1f), framed = false)
        DefaultIconControl(VectorIcons.Plus, "Block a contact", { selecting = true; repo.loadContacts() }, enabled = !state.busy, quiet = true)
    }
    state.blockedTotal?.let { SettingsNote(if (state.blocked.size < it) "${state.blocked.size} of $it · search covers loaded accounts" else "$it blocked") }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 28.dp)) {
        items(state.blocked.filter { it.name.contains(query, true) }, key = { "${it.user}:${it.id}" }) { peer ->
            CompactSettingsRow(peer.name, subtitle = "Unblock", enabled = !state.busy,
                leading = { TelegramAvatar(peer.name, null, 40.dp, peer.id, peer.user) }) { confirmation = peer to false }
        }
        if (state.blockedTotal != null && state.blockedOffset < state.blockedTotal) item { DefaultAction("Load more", repo::moreBlocked, enabled = !state.busy) }
        if (state.blockedTotal == 0 && !state.busy) item { SettingsNote("No blocked accounts.") }
    }
    if (selecting) SettingsContactPicker(repo, state, onDismiss = { selecting = false }) { selecting = false; confirmation = it to true }
    confirmation?.let { (peer, block) -> ConfirmSettingsAction(if (block) "Block contact?" else "Unblock account?", peer.name, { confirmation = null }) { repo.block(peer, block); confirmation = null } }
}

@Composable private fun ActiveSessions(repo: TelegramSettingsRepository, state: TelegramSettingsState) {
    var terminate by remember { mutableStateOf<JSONObject?>(null) }
    var all by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf<JSONObject?>(null) }
    val sessions = state.sessions?.let { JSONObject(it).getJSONArray("sessions").jsonObjects() }.orEmpty()
    LazyColumn(contentPadding = PaddingValues(bottom = 28.dp)) {
        for (current in listOf(true, false)) {
            item { SettingsHeading(if (current) "This device" else "Other sessions") }
            items(sessions.filter { it.optBoolean("is_current") == current }, key = { it.getLong("id") }) { session ->
                val active = session.optLong("last_active_date").takeIf { it > 0 }?.let {
                    java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(it * 1000))
                } ?: "Not provided"
                val platform = session.optString("platform").lowercase()
                CompactSettingsRow(session.optString("device_model").ifBlank { session.optString("platform").ifBlank { "Telegram session" } },
                    icon = if (listOf("windows", "mac", "linux", "web").any { it in platform }) SettingsIcons.Devices else SettingsIcons.Phone,
                    subtitle = "${session.optString("application_name")}\n${if (current) "This device" else "Last active $active"}", subtitleLines = 2,
                    enabled = !state.busy) { details = session }
            }
        }
        if (sessions.any { !it.optBoolean("is_current") }) item { DefaultAction("Terminate all other sessions", { all = true }, enabled = !state.busy, destructive = true) }
        if (sessions.isNotEmpty() && sessions.none { !it.optBoolean("is_current") }) item { SettingsNote("No other active sessions.") }
    }
    details?.let { session ->
        DefaultDialog("Device details", { details = null }) {
            Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                listOf("device_model", "application_name", "application_version", "platform", "system_version", "location", "ip_address").forEach { field ->
                    session.optString(field).takeIf(String::isNotBlank)?.let { SettingsNote(it) }
                }
                val timestamp = session.optLong("last_active_date")
                if (timestamp > 0) SettingsNote("Last active " + java.text.DateFormat.getDateTimeInstance().format(java.util.Date(timestamp * 1000)))
                if (session.optBoolean("is_password_pending")) SettingsNote("Password verification is pending on this device.")
            }
            if (!session.optBoolean("is_current")) DefaultAction("Terminate session", { details = null; terminate = session }, destructive = true, enabled = !state.busy)
            DefaultAction("Done", { details = null })
        }
    }
    if (all || terminate != null) ConfirmSettingsAction("Terminate ${if (all) "all other sessions" else "session"}?", "This signs out ${if (all) "your other Telegram devices" else terminate?.optString("device_model").orEmpty()}. This device stays signed in.", { all = false; terminate = null }) {
        repo.terminate(if (all) null else terminate!!.getLong("id")); all = false; terminate = null
    }
}

@Composable internal fun ConfirmSettingsAction(title: String, description: String, dismiss: () -> Unit, confirm: () -> Unit) {
    DefaultDialog(title, dismiss) {
        SettingsNote(description)
        DefaultAction("Confirm", confirm, emphasized = true)
        DefaultAction("Cancel", dismiss)
    }
}

/** Credential inputs are deliberately never saveable, logged or sent to an autofill service. */
@Composable internal fun SecretInput(value: String, change: (String) -> Unit, label: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        SettingsNote(label)
        BasicTextField(value, change, Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(8.dp), singleLine = true,
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = DefaultDesign.colors.text), cursorBrush = SolidColor(DefaultDesign.colors.accent))
    }
}

@Composable internal fun SecureSettingsWindow() {
    val window = (LocalContext.current as? Activity)?.window
    DisposableEffect(window) {
        if (window != null) SettingsSecureWindows.acquire(window)
        onDispose { if (window != null) SettingsSecureWindows.release(window) }
    }
}

private object SettingsSecureWindows {
    private val leases = java.util.WeakHashMap<android.view.Window, Pair<Int, Boolean>>()
    fun acquire(window: android.view.Window) {
        val prior = leases[window] ?: (0 to (window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0))
        leases[window] = (prior.first + 1) to prior.second
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
    fun release(window: android.view.Window) {
        val prior = leases[window] ?: return
        if (prior.first > 1) leases[window] = (prior.first - 1) to prior.second
        else { leases.remove(window); if (!prior.second) window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}

@Composable private fun PasswordSettings(repo: TelegramSettingsRepository, state: TelegramSettingsState) {
    SecureSettingsWindow()
    var mode by remember { mutableStateOf("overview") }
    var old by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    var hint by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf(false) }
    fun clear() { old = ""; password = ""; repeat = ""; hint = ""; email = ""; code = ""; mode = "overview" }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { clear() }
    val info = state.password?.let(::JSONObject)
    val enabled = info?.optBoolean("has_password") == true
    val pending = info?.optJSONObject("recovery_email_address_code_info")
    fun submit() {
        when (mode) {
            "password" -> repo.passwordAction("setPassword", listOf("old_password" to old, "new_password" to password, "new_hint" to hint,
                "set_recovery_email_address" to email.isNotBlank(), "new_recovery_email_address" to email), ::clear)
            "disable" -> repo.passwordAction("setPassword", listOf("old_password" to old, "new_password" to "", "new_hint" to "", "set_recovery_email_address" to false, "new_recovery_email_address" to ""), ::clear)
            "email" -> repo.passwordAction("setRecoveryEmailAddress", listOf("password" to old, "new_recovery_email_address" to email), ::clear)
            "verify" -> repo.passwordAction("checkRecoveryEmailAddressCode", listOf("code" to code), ::clear)
            "recover" -> repo.passwordAction("recoverPassword", listOf("recovery_code" to code, "new_password" to password, "new_hint" to hint), ::clear)
        }
    }
    LazyColumn(contentPadding = PaddingValues(bottom = 28.dp)) {
        item {
            SettingsHeading("Telegram account password")
            if (info == null) { SettingsNote("Loading password state…"); return@item }
            SettingsNote(if (enabled) "Two-step verification is enabled." else "Two-step verification is disabled.")
            if (enabled && info.optString("password_hint").isNotBlank()) SettingsNote("Hint: ${info.optString("password_hint")}")
            if (info.optInt("pending_reset_date") > 0) SettingsNote("A Telegram password reset is pending. Manage it from an established Telegram client.")
            if (mode == "overview") {
                DefaultAction(if (enabled) "Change password" else "Enable two-step verification", { mode = "password" }, enabled = !state.busy)
                if (enabled) {
                    DefaultAction("Recovery email", { mode = "email" }, enabled = !state.busy)
                    DefaultAction("Disable two-step verification", { mode = "disable" }, enabled = !state.busy, destructive = true)
                    if (info.optBoolean("has_recovery_email_address")) DefaultAction("Recover password by email", { repo.passwordAction("requestPasswordRecovery", onConfirmed = { mode = "recover" }) }, enabled = !state.busy)
                }
                if (pending != null) {
                    SettingsNote("Confirm recovery email: ${pending.optString("email_address_pattern")}")
                    DefaultAction("Enter email confirmation code", { mode = "verify" }, enabled = !state.busy)
                    DefaultAction("Resend email code", { repo.passwordAction("resendRecoveryEmailAddressCode") }, enabled = !state.busy)
                    DefaultAction("Cancel email verification", { mode = "cancelEmail"; confirm = true }, enabled = !state.busy)
                }
                if (state.recovery != null) DefaultAction("Enter recovery code", { mode = "recover" }, enabled = !state.busy)
            } else {
                if (enabled && mode in listOf("password", "disable", "email")) SecretInput(old, { old = it }, "Current Telegram password")
                if (mode in listOf("password", "recover")) {
                    if (mode == "recover") {
                        SettingsNote("Recovery code sent to ${state.recovery?.let { JSONObject(it).optString("email_address_pattern") }.orEmpty()}")
                        SecretInput(code, { code = it }, "Recovery code")
                    }
                    SecretInput(password, { password = it }, "New Telegram password")
                    SecretInput(repeat, { repeat = it }, "Repeat password")
                    DefaultInput(hint, { hint = it }, "Password hint (optional)", framed = false)
                }
                if (mode == "password" || mode == "email") {
                    DefaultInput(email, { email = it }, if (mode == "email") "Recovery email (empty removes it)" else "Recovery email (optional)", framed = false)
                    if (mode == "password" && email.isBlank()) SettingsNote("Without a recovery email, a forgotten password may prevent account access.")
                }
                if (mode == "verify") SecretInput(code, { code = it }, "Email confirmation code")
                if (mode != "cancelEmail") DefaultAction("Continue", {
                    if (mode == "disable" || (mode == "email" && email.isBlank())) confirm = true else submit()
                }, enabled = !state.busy && (!enabled || mode !in listOf("password", "disable", "email") || old.isNotEmpty()) &&
                    (mode !in listOf("password", "recover") || (password.isNotEmpty() && password == repeat)) && (mode !in listOf("verify", "recover") || code.isNotEmpty()))
                DefaultAction("Cancel", ::clear, enabled = !state.busy)
            }
        }
    }
    if (confirm) ConfirmSettingsAction("Confirm security change", when (mode) {
        "disable" -> "Remove your Telegram account password? This reduces protection for future sign-ins."
        "cancelEmail" -> "Cancel the pending recovery email verification?"
        else -> "Remove your recovery email? Password recovery will no longer be available through it."
    }, { confirm = false }) {
        confirm = false
        if (mode == "cancelEmail") repo.passwordAction("cancelRecoveryEmailAddressVerification", onConfirmed = ::clear) else submit()
    }
}

@Composable private fun TelegramNotificationScopes(repo: TelegramSettingsRepository, state: TelegramSettingsState, selection: String) {
    val context = LocalContext.current
    var error by remember { mutableStateOf<String?>(null) }
    LazyColumn(contentPadding = PaddingValues(bottom = 28.dp)) {
        TelegramSettingsRepository.notificationScopes.forEachIndexed { index, key ->
            if (selection in listOf("private", "groups", "channels") && index != listOf("private", "groups", "channels").indexOf(selection)) return@forEachIndexed
            val raw = state.notifications[key] ?: return@forEachIndexed
            val value = JSONObject(raw)
            item(key) {
                SettingsHeading(listOf("Private chats", "Groups", "Channels")[index])
                CompactSettingsRow("Notifications", icon = VectorIcons.Chat, checked = value.optInt("mute_for") == 0, enabled = !state.busy) {
                    repo.notification(key, raw, "mute_for", if (value.optInt("mute_for") == 0) Int.MAX_VALUE else 0)
                }
                CompactSettingsRow("Message previews", icon = VectorIcons.Chat, checked = value.optBoolean("show_preview"), enabled = !state.busy) { repo.notification(key, raw, "show_preview", !value.optBoolean("show_preview")) }
                CompactSettingsRow("Sound & vibration", icon = VectorIcons.Music, subtitle = "Android channel · this device") {
                    try { context.startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        .putExtra(Settings.EXTRA_CHANNEL_ID, if (index == 0) "messages_v1" else "groups_v1")) }
                    catch (_: Exception) { error = "Android could not open this channel. Open System Notification Settings instead." }
                }
            }
        }
        item { SettingsExplanation("About alerts", "These defaults sync with Telegram. Individual chat overrides still apply. Device delivery and hidden-preview preferences are under Alerts. Groups and channels share the existing Android sound channel.") }
        error?.let { message -> item { SettingsNote(message) } }
    }
}

@Composable private fun DownloadSettings(repo: TelegramSettingsRepository, state: TelegramSettingsState) {
    val presets = state.downloads?.let(::JSONObject)
    var editing by remember { mutableStateOf<Triple<String, String, String>?>(null) }
    LazyColumn(contentPadding = PaddingValues(bottom = 28.dp)) {
        listOf(Triple("Mobile data", "medium", "networkTypeMobile"), Triple("Wi-Fi", "high", "networkTypeWiFi"), Triple("Roaming", "low", "networkTypeMobileRoaming")).forEach { (title, slot, network) ->
            val value = presets?.optJSONObject(slot) ?: return@forEach
            val raw = canonicalJson(value)
            item(slot) {
                SettingsHeading(title)
                CompactSettingsRow("Download automatically", icon = VectorIcons.Download, checked = value.optBoolean("is_auto_download_enabled"), enabled = !state.busy) { repo.download(network, raw, "is_auto_download_enabled", !value.optBoolean("is_auto_download_enabled")) }
                listOf("Photos" to "max_photo_file_size", "Videos" to "max_video_file_size", "Other files" to "max_other_file_size").forEach { (label, field) ->
                    CompactSettingsRow(label, if (value.optLong(field) == 0L) "Off" else android.text.format.Formatter.formatFileSize(LocalContext.current, value.optLong(field)),
                        icon = if (field == "max_photo_file_size") SettingsIcons.User else VectorIcons.Download, enabled = !state.busy) { editing = Triple(slot, network, field) }
                }
            }
        }
        item { SettingsExplanation("About downloads", "Synced Telegram policies apply when media enters a visible conversation. Metered Wi-Fi uses mobile limits; unknown networks use roaming limits. Manual downloads and preview thumbnails remain available. Transfers already started continue if the network changes.") }
    }
    editing?.let { (slot, network, field) ->
        val current = presets?.optJSONObject(slot)
        if (current != null) DefaultDialog("Download size limit", { editing = null }) {
            Column(Modifier.selectableGroup()) {
                val sizes = listOf(0L, 1_048_576L, if (field == "max_photo_file_size") 10_485_760L else 52_428_800L).plus(current.optLong(field)).distinct().sorted()
                sizes.forEach { limit -> SettingsRadioRow(if (limit == 0L) "Off" else android.text.format.Formatter.formatFileSize(LocalContext.current, limit), current.optLong(field) == limit, !state.busy) {
                    repo.download(network, canonicalJson(current), field, limit); editing = null
                } }
            }
        }
    }
}

@Composable private fun ThumbnailCache(viewModel: PreferencesViewModel, repo: TelegramSettingsRepository, state: TelegramSettingsState) {
    var confirm by remember { mutableStateOf(false) }
    Column(Modifier.verticalScroll(rememberScrollState())) {
        SettingsStoragePanel(viewModel)
        SettingsNote("Remove downloaded Telegram thumbnails older than one hour through TDLib. Secret-chat thumbnails are retained. Databases, authentication, original files, and unsent messages are excluded.")
        DefaultAction("Clear downloaded thumbnails", { confirm = true }, enabled = !state.busy, destructive = true)
    }
    if (confirm) ConfirmSettingsAction("Clear thumbnail cache?", "Thumbnails can be downloaded again when needed.", { confirm = false }) { confirm = false; repo.clearThumbnails(viewModel::refreshStorage) }
}
