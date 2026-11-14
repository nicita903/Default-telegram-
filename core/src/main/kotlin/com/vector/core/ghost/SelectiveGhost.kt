package com.vector.core.ghost

import com.vector.core.profile.ProfilePhoto
import kotlinx.coroutines.flow.Flow

data class GhostContact(val id: Long, val name: String, val username: String? = null, val photo: ProfilePhoto? = null) {
    override fun toString() = "GhostContact([redacted])"
}
data class ContactPrivacy(val typing: Boolean = true, val recording: Boolean = true, val status: Boolean = false)
sealed interface PrivacyRule {
    data class DenyUsers(val ids: Set<Long>) : PrivacyRule { override fun toString() = "DenyUsers([redacted])" }
    data class Other(val json: String, val userCount: Int = 0) : PrivacyRule { override fun toString() = "PrivacyRule([redacted])" }
}
data class PrivacyAttempt(val before: List<PrivacyRule>, val after: List<PrivacyRule>, val target: Set<Long>)
data class PrivacyJournal(val baseline: List<PrivacyRule>? = null, val applied: List<PrivacyRule>? = null,
    val target: Set<Long> = emptySet(), val pending: PrivacyAttempt? = null)
data class GhostSaved(val enabled: Boolean = false, val contacts: Map<Long, ContactPrivacy> = emptyMap(), val journal: PrivacyJournal = PrivacyJournal()) {
    override fun toString() = "GhostSaved([redacted])"
    fun desiredStatus() = if (enabled) contacts.filterValues { it.status }.keys else emptySet()
}
data class GhostState(val authenticated: Boolean = false, val initialized: Boolean = false, val busy: Boolean = false,
    val contacts: List<GhostContact> = emptyList(), val saved: GhostSaved = GhostSaved(),
    val serverLoaded: Boolean = false, val confirmed: Boolean = false, val conflict: Boolean = false, val error: String? = null) {
    override fun toString() = "GhostState(authenticated=$authenticated, initialized=$initialized, busy=$busy, confirmed=$confirmed, conflict=$conflict)"
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
        "chatActionUploadingVoiceNote", "chatActionUploadingVideoNote" -> !selected.recording
        else -> true
    }
}

/** Adds a first-match exclusion. Never rewrites unrelated allow/deny rules or unknown rule types. */
fun statusExceptions(baseline: List<PrivacyRule>, users: Set<Long>): List<PrivacyRule> {
    require(users.all { it > 0 })
    if (users.isEmpty() || excludesUsers(baseline, users)) return baseline
    require(users.size + baseline.sumOf { when (it) { is PrivacyRule.DenyUsers -> it.ids.size; is PrivacyRule.Other -> it.userCount } } <= 1000) { "Privacy exception limit exceeded" }
    return listOf(PrivacyRule.DenyUsers(users.toSortedSet())) + baseline
}
fun excludesUsers(rules: List<PrivacyRule>, users: Set<Long>): Boolean {
    val remaining = users.toMutableSet()
    if (remaining.isEmpty()) return true
    for (rule in rules) {
        if (rule is PrivacyRule.DenyUsers) remaining.removeAll(rule.ids)
        else if (rule is PrivacyRule.Other && rule.json == "{\"@type\":\"userPrivacySettingRuleRestrictAll\"}") return true
        else return false // Don't infer membership/premium/unknown-rule effects.
        if (remaining.isEmpty()) return true
    }
    return false
}
