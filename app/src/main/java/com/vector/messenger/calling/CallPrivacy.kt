package com.vector.messenger.calling

import com.vector.core.profile.ProfileRepository
import com.vector.messenger.telegram.*
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

// Only edits the default rule; opaque per-user/chat/premium exceptions stay in their original order.
data class CallPrivacyState(val base: String? = null, val exceptions: Int = 0, val busy: Boolean = false,
    val error: String? = null, internal val rules: String? = null)

@Singleton
class CallPrivacy @Inject constructor(private val profile: ProfileRepository, private val client: TdlibAvatarClient,
    private val manager: TdlibClientManager, private val scope: CoroutineScope) {
    private val mutable = MutableStateFlow(CallPrivacyState())
    val state = mutable.asStateFlow()
    private val guard = Mutex()
    private var account = 0L
    private var generation = 0L
    private val bases = mapOf("Everyone" to "userPrivacySettingRuleAllowAll", "Contacts" to "userPrivacySettingRuleAllowContacts",
        "Nobody" to "userPrivacySettingRuleRestrictAll")
    private val setting get() = request("userPrivacySettingAllowPeerToPeerCalls")
    init {
        scope.launch { profile.state.map { it.profile?.id?.takeIf { _ -> it.authenticated } }.distinctUntilChanged().collectLatest { id ->
            account = id ?: 0; generation++; mutable.value = CallPrivacyState()
            if (id != null) {
                refresh()
                manager.ghostUpdates.collect { update ->
                    if (update.optString("@type") == "updateUserPrivacySettingRules" &&
                        update.optJSONObject("setting")?.optString("@type") == "userPrivacySettingAllowPeerToPeerCalls") refresh()
                }
            }
        } }
    }
    private suspend fun fetch(id: Long) = client.call(request("getUserPrivacySettingRules", "setting" to setting), id).getJSONArray("rules")
    private fun confirmed(rules: JSONArray) {
        val values = (0 until rules.length()).map { rules.getJSONObject(it).optString("@type") }
        val baseRules = values.filter { it in bases.values }
        val label = if (baseRules == listOf("userPrivacySettingRuleAllowContacts", "userPrivacySettingRuleRestrictAll")) "Contacts"
            else bases.entries.firstOrNull { baseRules == listOf(it.value) }?.key
        mutable.value = CallPrivacyState(label,
            values.count { it !in bases.values }, rules = canonicalJson(rules))
    }
    fun refresh() { operate(null) }
    fun setDefault(label: String) { if (label in bases && !state.value.busy && state.value.rules != null) operate(label) }
    private fun operate(label: String?) {
        val id = account; val token = generation
        if (id == 0L) return
        val expected = state.value.rules
        scope.launch { guard.withLock {
            if (generation != token) return@withLock
            mutable.update { it.copy(busy = true, error = null) }
            try {
                val current = fetch(id)
                if (generation != token) return@withLock
                if (label != null) {
                    if (canonicalJson(current) != expected) {
                        confirmed(current)
                        mutable.update { it.copy(error = "Telegram rules changed. Review and try again.") }
                        return@withLock
                    }
                    val merged = JSONArray()
                    for (index in 0 until current.length()) {
                        val rule = current.getJSONObject(index)
                        if (rule.optString("@type") !in bases.values) merged.put(rule)
                    }
                    merged.put(request(bases.getValue(label)))
                    if (label == "Contacts") merged.put(request("userPrivacySettingRuleRestrictAll"))
                    client.call(request("setUserPrivacySettingRules", "setting" to setting,
                        "rules" to request("userPrivacySettingRules", "rules" to merged)), id)
                    val verified = fetch(id)
                    if (generation != token) return@withLock
                    confirmed(verified)
                    if (canonicalJson(verified) != canonicalJson(merged)) mutable.update { it.copy(error = "Telegram returned different rules. Showing confirmed state.") }
                } else confirmed(current)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (generation == token) mutable.update { it.copy(busy = false, error = "Unable to confirm Telegram call privacy. Retry when connected.") } }
        } }
    }
}
