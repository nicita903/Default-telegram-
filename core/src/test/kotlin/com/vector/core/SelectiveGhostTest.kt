package com.vector.core

import com.vector.core.auth.*
import com.vector.core.demo.DemoGhostRepository
import com.vector.core.ghost.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SelectiveGhostTest {
    private val allow = listOf(PrivacyRule.Other("{\"@type\":\"userPrivacySettingRuleAllowAll\"}"))
    private class Auth : AuthenticationRepository {
        override val state = MutableStateFlow(AuthState(AuthStep.Phone))
        override fun start() = Unit
        override suspend fun submit(action: AuthAction) = Unit
        fun ready(value: Boolean) { state.value = AuthState(if (value) AuthStep.Ready else AuthStep.LoggingOut) }
    }
    private class Store : GhostStore {
        val saved = mutableMapOf<Long, GhostSaved>()
        var fail = false
        override suspend fun load(accountId: Long): GhostSaved { check(!fail); return saved[accountId] ?: GhostSaved() }
        override suspend fun save(accountId: Long, value: GhostSaved) { check(!fail); saved[accountId] = value }
    }
    private class Client(initial: List<PrivacyRule>) : GhostClient {
        override val events = MutableSharedFlow<GhostEvent>(extraBufferCapacity = 16)
        var account = 1L
        val ruleSets = mutableMapOf(1L to initial, 2L to initial)
        var gate: CompletableDeferred<Unit>? = null
        var failBefore = false; var failAfter = false
        var failRules = false
        var rulesGate: CompletableDeferred<Unit>? = null
        var normalize: (List<PrivacyRule>) -> List<PrivacyRule> = { it }
        var setCalls = 0; var actions = 0
        var contactsGate: CompletableDeferred<Unit>? = null
        var actionPolicy: (Long, String) -> Boolean = { _, _ -> false }
        override suspend fun accountId() = account
        override suspend fun contacts(): List<GhostContact> {
            contactsGate?.await()
            return listOf(GhostContact(10, "Fixture A"), GhostContact(11, "Fixture B"))
        }
        override suspend fun rules(): List<PrivacyRule> {
            val snapshot = ruleSets.getValue(account)
            rulesGate?.await(); check(!failRules)
            return snapshot
        }
        override suspend fun setRules(rules: List<PrivacyRule>) {
            setCalls++; gate?.await(); check(!failBefore)
            val applied = normalize(rules)
            ruleSets[account] = applied; events.emit(GhostEvent.Rules(applied)); check(!failAfter)
        }
        override suspend fun chatAction(chatId: Long, action: String): Boolean {
            if (!actionPolicy(chatId, action)) return false
            actions++; return true
        }
        override fun installActionPolicy(policy: (Long, String) -> Boolean) { actionPolicy = policy }
    }
    @Test fun selectionPersistsPerAccountAndUnknownContactsCannotBeInvented() = runTest {
        val auth = Auth(); val client = Client(allow); val store = Store()
        val repo = SelectiveGhostRepository(auth, client, store, DemoGhostRepository(), backgroundScope)
        runCurrent(); assertFalse(repo.state.value.authenticated)
        auth.ready(true); runCurrent(); assertEquals(listOf(10L, 11L), repo.state.value.contacts.map { it.id })
        repo.select(999); runCurrent(); assertTrue(repo.state.value.saved.contacts.isEmpty())
        repo.select(10); runCurrent(); repo.enabled(true); runCurrent()
        assertTrue(store.saved.getValue(1).enabled); assertEquals(setOf(10L), store.saved.getValue(1).contacts.keys)
        auth.ready(false); runCurrent(); assertTrue(repo.state.value.contacts.isEmpty()); assertTrue(repo.state.value.saved.contacts.isEmpty())
        client.account = 2; auth.ready(true); runCurrent(); assertTrue(repo.state.value.saved.contacts.isEmpty())
        auth.ready(false); runCurrent(); client.account = 1; auth.ready(true); runCurrent()
        assertEquals(setOf(10L), repo.state.value.saved.contacts.keys); assertTrue(repo.state.value.saved.enabled)
        repo.remove(10); runCurrent(); assertTrue(store.saved.getValue(1).contacts.isEmpty())
    }
    @Test fun typingAndAllApplicableRecordingIndicatorsAreSuppressedOnlyForSelectedDirectUsers() = runTest {
        val auth = Auth(); val client = Client(allow); val repo = SelectiveGhostRepository(auth, client, Store(), DemoGhostRepository(), backgroundScope)
        auth.ready(true); runCurrent(); repo.select(10); runCurrent(); repo.enabled(true); runCurrent()
        for (action in listOf("chatActionTyping", "chatActionRecordingVoiceNote", "chatActionRecordingVideo", "chatActionRecordingVideoNote", "chatActionUploadingVoiceNote", "chatActionUploadingVideoNote", "chatActionUploadingVideo")) {
            assertFalse(client.actionPolicy(10, action)); assertTrue(client.actionPolicy(11, action)); assertTrue(client.actionPolicy(0, action))
            repo.sendAction(10, action); runCurrent()
        }
        assertEquals(0, client.actions); assertTrue(client.actionPolicy(10, "chatActionCancel"))
        assertFalse(client.actionPolicy(-1, "chatActionTyping"))
        assertTrue(client.actionPolicy(10, "chatActionUploadingDocument")) // Not a media-transfer interception.
        repo.policy(10, ContactPrivacy(typing = false)); runCurrent(); assertTrue(client.actionPolicy(10, "chatActionTyping"))
        assertFalse(client.actionPolicy(10, "chatActionRecordingVoiceNote"))
        repo.enabled(false); runCurrent(); assertTrue(client.actionPolicy(10, "chatActionRecordingVoiceNote"))
        auth.ready(false); runCurrent(); assertFalse(client.actionPolicy(10, "chatActionTyping"))
    }
    @Test fun orderedRulesPreserveUnrelatedAndUnknownRulesAndRestoreOnlyWhenUnchanged() = runTest {
        val original = listOf(PrivacyRule.Other("{\"@type\":\"userPrivacySettingRuleAllowUsers\",\"user_ids\":[99]}", 1),
            PrivacyRule.Other("{\"@type\":\"futureRule\",\"unknown\":true}"), allow.single())
        val auth = Auth(); val client = Client(original); val repo = SelectiveGhostRepository(auth, client, Store(), DemoGhostRepository(), backgroundScope)
        auth.ready(true); runCurrent(); repo.select(10); runCurrent(); repo.policy(10, ContactPrivacy(status = true)); runCurrent(); repo.enabled(true); runCurrent()
        assertEquals(0, client.setCalls) // Local edits never silently change account-wide privacy.
        repo.applyPrivacy(); runCurrent()
        assertEquals(listOf(PrivacyRule.DenyUsers(setOf(10L))) + original, client.rules())
        assertTrue(repo.state.value.confirmed)
        repo.applyPrivacy(); runCurrent(); assertEquals(1, client.setCalls)
        repo.enabled(false); runCurrent(); assertTrue(repo.state.value.confirmed) // Local filters do not rewrite server intent.
        repo.applyPrivacy(); runCurrent(); assertEquals(1, client.setCalls)
        repo.policy(10, ContactPrivacy(status = false)); runCurrent(); assertFalse(repo.state.value.confirmed)
        repo.applyPrivacy(); runCurrent(); assertEquals(original, client.rules()); assertEquals(2, client.setCalls)
    }
    @Test fun externalPrivacyEditsCauseConflictWithoutOverwritingAnyRules() = runTest {
        val auth = Auth(); val client = Client(allow); val repo = SelectiveGhostRepository(auth, client, Store(), DemoGhostRepository(), backgroundScope)
        auth.ready(true); runCurrent(); repo.select(10); runCurrent(); repo.policy(10, ContactPrivacy(status = true)); runCurrent(); repo.enabled(true); runCurrent(); repo.applyPrivacy(); runCurrent()
        val external = listOf(PrivacyRule.DenyUsers(setOf(11L, 99L))) + allow
        client.ruleSets[1] = external; client.events.emit(GhostEvent.Rules(external)); runCurrent()
        assertTrue(repo.state.value.conflict)
        repo.remove(10); runCurrent(); repo.applyPrivacy(); runCurrent()
        assertEquals(1, client.setCalls); assertEquals(external, client.rules())
        repo.useCurrentRules(); runCurrent(); repo.applyPrivacy(); runCurrent()
        assertEquals(external, client.rules()); assertEquals(1, client.setCalls)
    }
    @Test fun errorAfterServerWriteIsReconciledWithoutDuplicatePrivacyWrites() = runTest {
        val auth = Auth(); val client = Client(allow); val store = Store(); val repo = SelectiveGhostRepository(auth, client, store, DemoGhostRepository(), backgroundScope)
        auth.ready(true); runCurrent(); repo.select(10); runCurrent(); repo.policy(10, ContactPrivacy(status = true)); runCurrent(); repo.enabled(true); runCurrent()
        client.failAfter = true; repo.applyPrivacy(); runCurrent()
        assertFalse(repo.state.value.confirmed); assertNotNull(repo.state.value.error); assertNotNull(store.saved.getValue(1).journal.pending)
        client.failAfter = false; repo.refresh(); runCurrent(); assertTrue(repo.state.value.confirmed)
        repo.applyPrivacy(); runCurrent(); assertEquals(1, client.setCalls)
    }
    @Test fun definiteServerFailureDoesNotClaimSuccessAndCanBeRetried() = runTest {
        val auth = Auth(); val client = Client(allow); val repo = SelectiveGhostRepository(auth, client, Store(), DemoGhostRepository(), backgroundScope)
        auth.ready(true); runCurrent(); repo.select(10); runCurrent(); repo.policy(10, ContactPrivacy(status = true)); runCurrent(); repo.enabled(true); runCurrent()
        client.failBefore = true; repo.applyPrivacy(); runCurrent(); assertEquals(allow, client.rules()); assertFalse(repo.state.value.confirmed)
        client.failBefore = false; repo.refresh(); runCurrent(); repo.applyPrivacy(); runCurrent(); assertTrue(repo.state.value.confirmed)
    }
    @Test fun duplicateOperationsAreDroppedAndLogoutCancelsPendingPrivacyWork() = runTest {
        val auth = Auth(); val client = Client(allow); val repo = SelectiveGhostRepository(auth, client, Store(), DemoGhostRepository(), backgroundScope)
        auth.ready(true); runCurrent(); repo.select(10); runCurrent(); repo.policy(10, ContactPrivacy(status = true)); runCurrent(); repo.enabled(true); runCurrent()
        client.gate = CompletableDeferred(); repo.applyPrivacy(); runCurrent(); repo.applyPrivacy(); runCurrent()
        assertEquals(1, client.setCalls); auth.ready(false); runCurrent(); client.gate!!.complete(Unit); runCurrent()
        assertFalse(repo.state.value.authenticated); assertTrue(repo.state.value.contacts.isEmpty()); assertEquals(allow, client.rules())
        client.account = 2; client.gate = null; auth.ready(true); runCurrent(); assertTrue(repo.state.value.saved.contacts.isEmpty())
    }
    @Test fun missingSecurePreferencesFailClosedForPrivateIndicatorsWithoutPrivacyWrites() = runTest {
        val auth = Auth(); val client = Client(allow); val store = Store().apply { fail = true }
        val repo = SelectiveGhostRepository(auth, client, store, DemoGhostRepository(), backgroundScope)
        auth.ready(true); runCurrent(); assertFalse(repo.state.value.initialized); assertNotNull(repo.state.value.error)
        assertFalse(client.actionPolicy(10, "chatActionTyping")); assertEquals(0, client.setCalls)
        store.fail = false; repo.refresh(); runCurrent(); assertTrue(repo.state.value.initialized)
    }
    @Test fun existingDenialsAreNotDuplicatedAndTheServerExceptionLimitIsRespected() {
        val denied = listOf(PrivacyRule.DenyUsers(setOf(10L, 99L))) + allow
        assertEquals(denied, statusExceptions(denied, setOf(10L)))
        assertEquals(allow, statusExceptions(allow, emptySet()))
        assertThrows(IllegalArgumentException::class.java) { statusExceptions(listOf(PrivacyRule.DenyUsers((1L..1000L).toSet())) + allow, setOf(1001)) }
        assertFalse(excludesUsers(allow + denied, setOf(10))) // First match controls visibility.
    }
    @Test fun incrementalContactChangesWinOverAnInflightContactSnapshot() = runTest {
        val auth = Auth(); val client = Client(allow).apply { contactsGate = CompletableDeferred() }
        val repo = SelectiveGhostRepository(auth, client, Store(), DemoGhostRepository(), backgroundScope)
        auth.ready(true); runCurrent()
        client.events.emit(GhostEvent.Contact(GhostContact(10, "Removed fixture"), false))
        client.events.emit(GhostEvent.Contact(GhostContact(12, "Added fixture"), true)); runCurrent()
        client.contactsGate!!.complete(Unit); runCurrent()
        assertEquals(setOf(11L, 12L), repo.state.value.contacts.map { it.id }.toSet())
        assertTrue(repo.state.value.initialized); assertFalse(repo.state.value.busy)
    }
    @Test fun multipleServerExclusionsApplyWithLocalFiltersOffAndAcceptSafeNormalization() = runTest {
        val original = listOf(PrivacyRule.AllowUsers(setOf(99L)), PrivacyRule.DenyUsers(setOf(20L))) + allow
        val auth = Auth(); val client = Client(original); val repo = SelectiveGhostRepository(auth, client, Store(), DemoGhostRepository(), backgroundScope)
        auth.ready(true); runCurrent()
        for (id in listOf(10L, 11L)) { repo.select(id); runCurrent(); repo.policy(id, ContactPrivacy(status = true)); runCurrent() }
        client.normalize = { listOf(PrivacyRule.AllowUsers(setOf(99L)), PrivacyRule.DenyUsers(setOf(10L, 11L, 20L))) + allow }
        repo.applyPrivacy(); runCurrent()
        assertFalse(repo.state.value.saved.enabled)
        assertTrue(repo.state.value.confirmed)
        assertEquals(CapabilityStatus.Enabled, repo.state.value.serverFilter(10))
        assertEquals(CapabilityStatus.Enabled, repo.state.value.serverFilter(11))
        assertEquals(CapabilityStatus.Disabled, repo.state.value.localFilter(10))
        assertEquals(VisibilityDecision.Allowed, visibilityDecision(client.rules(), 99))
        assertEquals(VisibilityDecision.Restricted, visibilityDecision(client.rules(), 20))
        client.normalize = { it }; repo.remove(10); runCurrent(); repo.remove(11); runCurrent(); repo.applyPrivacy(); runCurrent()
        assertEquals(original, client.rules())
    }
    @Test fun effectiveRulesSkipUnrelatedAllowsAndUseKnownContactMembership() {
        val rules = listOf(PrivacyRule.AllowUsers(setOf(99L)), PrivacyRule.DenyUsers(setOf(10L, 11L))) + allow
        assertTrue(excludesUsers(rules, setOf(10L, 11L)))
        assertFalse(excludesUsers(rules, setOf(99L)))
        val contactsOnly = listOf(PrivacyRule.Other("{\"@type\":\"userPrivacySettingRuleRestrictContacts\"}")) + allow
        assertEquals(VisibilityDecision.Restricted, visibilityDecision(contactsOnly, 10, contact = true))
        assertEquals(VisibilityDecision.Allowed, visibilityDecision(contactsOnly, 10, contact = false))
        assertEquals(VisibilityDecision.Unknown, visibilityDecision(contactsOnly, 10))
        val premium = listOf(PrivacyRule.DenyUsers(setOf(10L)), PrivacyRule.Other("{\"@type\":\"userPrivacySettingRuleAllowPremiumUsers\"}")) + allow
        assertEquals(VisibilityDecision.Restricted, visibilityDecision(premium, 10, premium = true))
    }
    @Test fun mergeMovesOnlySelectedExceptionsAndDoesNotExceedTheLimitThroughDuplicates() {
        val baseline = listOf(PrivacyRule.AllowUsers((1L..1000L).toSet())) + allow
        val merged = statusExceptions(baseline, setOf(10L, 11L))
        assertEquals(PrivacyRule.DenyUsers(setOf(10L, 11L)), merged.first())
        assertEquals((1L..1000L).toSet() - setOf(10L, 11L), (merged[1] as PrivacyRule.AllowUsers).ids)
        assertEquals(PrivacyRule.AllowUsers((1L..1000L).toSet()), baseline.first())
    }
    @Test fun readbackMustPreserveUnrelatedExplicitExceptionsBeforeReportingEnabled() = runTest {
        val original = listOf(PrivacyRule.AllowUsers(setOf(99L))) + allow
        val auth = Auth(); val client = Client(original); val repo = SelectiveGhostRepository(auth, client, Store(), DemoGhostRepository(), backgroundScope)
        auth.ready(true); runCurrent(); repo.select(10); runCurrent(); repo.policy(10, ContactPrivacy(status = true)); runCurrent()
        client.normalize = { listOf(PrivacyRule.DenyUsers(setOf(10L))) + allow }
        repo.applyPrivacy(); runCurrent()
        assertFalse(repo.state.value.confirmed); assertTrue(repo.state.value.conflict)
        assertEquals(CapabilityStatus.Failed, repo.state.value.serverFilter(10))
    }
    @Test fun failedServerRefreshRemainsFailedAfterLocalPreferenceChanges() = runTest {
        val auth = Auth(); val client = Client(allow); val repo = SelectiveGhostRepository(auth, client, Store(), DemoGhostRepository(), backgroundScope)
        auth.ready(true); runCurrent(); repo.select(10); runCurrent(); repo.policy(10, ContactPrivacy(status = true)); runCurrent(); repo.applyPrivacy(); runCurrent()
        client.failRules = true; repo.refresh(); runCurrent()
        assertEquals(CapabilityStatus.Failed, repo.state.value.serverFilter(10))
        repo.policy(10, ContactPrivacy(typing = false, status = true)); runCurrent()
        assertFalse(repo.state.value.confirmed); assertEquals(CapabilityStatus.Failed, repo.state.value.serverFilter(10))
        client.failRules = false; repo.refresh(); runCurrent()
        assertEquals(CapabilityStatus.Enabled, repo.state.value.serverFilter(10))
    }
    @Test fun aNewerPrivacyUpdateWinsOverAnInflightStaleRefresh() = runTest {
        val auth = Auth(); val client = Client(allow); val repo = SelectiveGhostRepository(auth, client, Store(), DemoGhostRepository(), backgroundScope)
        auth.ready(true); runCurrent(); repo.select(10); runCurrent()
        client.rulesGate = CompletableDeferred(); repo.refresh(); runCurrent()
        val changed = listOf(PrivacyRule.DenyUsers(setOf(10L))) + allow
        client.ruleSets[1] = changed; client.events.emit(GhostEvent.Rules(changed)); runCurrent()
        client.rulesGate!!.complete(Unit); runCurrent()
        assertEquals(CapabilityStatus.Enabled, repo.state.value.serverFilter(10)) // Existing, freshly observed exclusion.
    }
    @Test fun contactMembershipChangesReevaluateEffectiveServerStateWithoutChangingRules() = runTest {
        val rules = listOf(PrivacyRule.Other("{\"@type\":\"userPrivacySettingRuleRestrictContacts\"}")) + allow
        val auth = Auth(); val client = Client(rules); val repo = SelectiveGhostRepository(auth, client, Store(), DemoGhostRepository(), backgroundScope)
        auth.ready(true); runCurrent(); repo.select(10); runCurrent()
        assertEquals(CapabilityStatus.Enabled, repo.state.value.serverFilter(10))
        client.events.emit(GhostEvent.Contact(GhostContact(10, "Former contact"), false)); runCurrent()
        assertEquals(CapabilityStatus.Disabled, repo.state.value.serverFilter(10))
        assertEquals(0, client.setCalls); assertEquals(rules, client.rules())
    }
}
