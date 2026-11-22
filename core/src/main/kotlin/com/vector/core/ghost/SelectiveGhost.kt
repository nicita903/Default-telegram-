package com.vector.core.ghost

import com.vector.core.profile.ProfilePhoto
import kotlinx.coroutines.flow.Flow

data class GhostContact(val id: Long, val name: String, val username: String? = null, val photo: ProfilePhoto? = null, val premium: Boolean = false) {
    override fun toString() = "GhostContact([redacted])"
}
data class ContactPrivacy(val typing: Boolean = true, val recording: Boolean = true, val status: Boolean = false)
sealed interface PrivacyRule {
    data class DenyUsers(val ids: Set<Long>) : PrivacyRule { override fun toString() = "DenyUsers([redacted])" }
    data class AllowUsers(val ids: Set<Long>) : PrivacyRule { override fun toString() = "AllowUsers([redacted])" }
    data class Other(val json: String, val userCount: Int = 0) : PrivacyRule { override fun toString() = "PrivacyRule([redacted])" }
}
data class PrivacyAttempt(val before: List<PrivacyRule>, val after: List<PrivacyRule>, val target: Set<Long>)
data class PrivacyJournal(val baseline: List<PrivacyRule>? = null, val applied: List<PrivacyRule>? = null,
    val target: Set<Long> = emptySet(), val pending: PrivacyAttempt? = null)
data class GhostSaved(val enabled: Boolean = false, val contacts: Map<Long, ContactPrivacy> = emptyMap(), val journal: PrivacyJournal = PrivacyJournal()) {
    override fun toString() = "GhostSaved([redacted])"
    // The local indicator switch has no authority over an account-wide Telegram privacy setting.
    fun desiredStatus() = contacts.filterValues { it.status }.keys
}
enum class CapabilityStatus { Enabled, Disabled, Applying, Failed, Unsupported }
enum class GhostOperation { Idle, Loading, Saving, Applying }
data class GhostState(val authenticated: Boolean = false, val initialized: Boolean = false, val busy: Boolean = false,
    val contacts: List<GhostContact> = emptyList(), val saved: GhostSaved = GhostSaved(),
    val serverLoaded: Boolean = false, val confirmed: Boolean = false, val conflict: Boolean = false, val error: String? = null,
    val operation: GhostOperation = GhostOperation.Idle, val serverError: String? = null, val contactsLoaded: Boolean = false,
    val visibility: Map<Long, CapabilityStatus> = emptyMap()) {
    override fun toString() = "GhostState(authenticated=$authenticated, initialized=$initialized, busy=$busy, confirmed=$confirmed, conflict=$conflict)"
}
fun GhostState.localFilter(userId: Long, recording: Boolean = false): CapabilityStatus = when {
    !initialized -> if (error != null) CapabilityStatus.Failed else CapabilityStatus.Applying
    busy && operation == GhostOperation.Saving -> CapabilityStatus.Applying
    saved.enabled && saved.contacts[userId]?.let { if (recording) it.recording else it.typing } == true -> CapabilityStatus.Enabled
    else -> CapabilityStatus.Disabled
}
fun GhostState.serverFilter(userId: Long): CapabilityStatus = when {
    busy && operation == GhostOperation.Applying -> CapabilityStatus.Applying
    serverError != null || conflict -> CapabilityStatus.Failed
    !serverLoaded -> CapabilityStatus.Unsupported
    else -> visibility[userId] ?: CapabilityStatus.Unsupported
}
interface GhostStore {
    suspend fun load(accountId: Long): GhostSaved
    suspend fun save(accountId: Long, value: GhostSaved)
}
sealed interface GhostEvent {
    data class Rules(val rules: List<PrivacyRule>) : GhostEvent
    data class Contact(val contact: GhostContact, val present: Boolean) : GhostEvent
}
interface GhostClient {
    val events: Flow<GhostEvent>
    suspend fun accountId(): Long
    suspend fun contacts(): List<GhostContact>
    suspend fun rules(): List<PrivacyRule>
    suspend fun setRules(rules: List<PrivacyRule>)
    suspend fun chatAction(chatId: Long, action: String): Boolean
    /** peerId: >0 private user, 0 group/channel, -1 unresolved. Cancel always clears activity. */
    fun installActionPolicy(policy: (Long, String) -> Boolean)
}

fun GhostSaved.allowsAction(peerId: Long, action: String, initialized: Boolean = true): Boolean {
    if (action == "chatActionCancel") return true
    if (peerId == 0L) return true // Group actions cannot selectively hide activity from one member.
    if (peerId < 0 || !initialized) return false
    val selected = contacts[peerId]
    if (!enabled || selected == null) return true
    return when (action) {
        "chatActionTyping" -> !selected.typing
        "chatActionRecordingVoiceNote", "chatActionRecordingVideo", "chatActionRecordingVideoNote",
        "chatActionUploadingVoiceNote", "chatActionUploadingVideoNote", "chatActionUploadingVideo" -> !selected.recording
        else -> true
    }
}

/** Adds a first-match exclusion. Never rewrites unrelated allow/deny rules or unknown rule types. */
fun statusExceptions(baseline: List<PrivacyRule>, users: Set<Long>): List<PrivacyRule> {
    require(users.all { it > 0 })
    if (users.isEmpty() || excludesUsers(baseline, users)) return baseline
    val remaining = baseline.mapNotNull { rule -> when (rule) {
        is PrivacyRule.DenyUsers -> (rule.ids - users).takeIf { it.isNotEmpty() }?.let(PrivacyRule::DenyUsers)
        is PrivacyRule.AllowUsers -> (rule.ids - users).takeIf { it.isNotEmpty() }?.let(PrivacyRule::AllowUsers)
        is PrivacyRule.Other -> rule
    } }
    require(users.size + remaining.sumOf { it.userIds().size + if (it is PrivacyRule.Other) it.userCount else 0 } <= 1000) { "Privacy exception limit exceeded" }
    return listOf(PrivacyRule.DenyUsers(users.toSortedSet())) + remaining
}
private fun PrivacyRule.userIds(): Set<Long> = when (this) {
    is PrivacyRule.DenyUsers -> ids
    is PrivacyRule.AllowUsers -> ids
    else -> emptySet()
}
enum class VisibilityDecision { Restricted, Allowed, Unknown }
fun visibilityDecision(rules: List<PrivacyRule>, userId: Long, contact: Boolean? = null, premium: Boolean? = null, bot: Boolean = false): VisibilityDecision {
    for (rule in rules) {
        when (rule) {
            is PrivacyRule.DenyUsers -> if (userId in rule.ids) return VisibilityDecision.Restricted
            is PrivacyRule.AllowUsers -> if (userId in rule.ids) return VisibilityDecision.Allowed
            is PrivacyRule.Other -> when (rule.json) {
                "{\"@type\":\"userPrivacySettingRuleRestrictAll\"}" -> return VisibilityDecision.Restricted
                "{\"@type\":\"userPrivacySettingRuleAllowAll\"}" -> return VisibilityDecision.Allowed
                "{\"@type\":\"userPrivacySettingRuleAllowContacts\"}", "{\"@type\":\"userPrivacySettingRuleRestrictContacts\"}" -> {
                    if (contact == null) return VisibilityDecision.Unknown
                    if (contact) return if (rule.json.contains("RestrictContacts")) VisibilityDecision.Restricted else VisibilityDecision.Allowed
                }
                "{\"@type\":\"userPrivacySettingRuleAllowPremiumUsers\"}" -> {
                    if (premium == null) return VisibilityDecision.Unknown
                    if (premium) return VisibilityDecision.Allowed
                }
                "{\"@type\":\"userPrivacySettingRuleAllowBots\"}" -> if (bot) return VisibilityDecision.Allowed
                "{\"@type\":\"userPrivacySettingRuleRestrictBots\"}" -> if (bot) return VisibilityDecision.Restricted
                else -> return VisibilityDecision.Unknown
            }
        }
    }
    return VisibilityDecision.Restricted // TDLib's documented no-match default.
}
fun excludesUsers(rules: List<PrivacyRule>, users: Set<Long>) = users.all { visibilityDecision(rules, it) == VisibilityDecision.Restricted }

/** Accept harmless server normalization, but not lost/added unrelated user exceptions or changed defaults. */
fun preservesOtherUsers(before: List<PrivacyRule>, after: List<PrivacyRule>, selected: Set<Long>): Boolean {
    val beforeIds = before.flatMap { it.userIds() }.toSet() - selected
    val afterIds = after.flatMap { it.userIds() }.toSet() - selected
    if (beforeIds != afterIds) return false
    for (id in beforeIds + 0L) for (contact in listOf(false, true)) for (premium in listOf(false, true)) for (bot in listOf(false, true)) {
        val old = visibilityDecision(before, id, contact, premium, bot)
        val new = visibilityDecision(after, id, contact, premium, bot)
        if (old == VisibilityDecision.Unknown || new == VisibilityDecision.Unknown) return stripSelected(before, selected) == stripSelected(after, selected)
        if (old != new) return false
    }
    return true
}
private fun stripSelected(rules: List<PrivacyRule>, selected: Set<Long>) = rules.mapNotNull { rule -> when (rule) {
    is PrivacyRule.DenyUsers -> (rule.ids - selected).takeIf { it.isNotEmpty() }?.let(PrivacyRule::DenyUsers)
    is PrivacyRule.AllowUsers -> (rule.ids - selected).takeIf { it.isNotEmpty() }?.let(PrivacyRule::AllowUsers)
    is PrivacyRule.Other -> rule
} }
