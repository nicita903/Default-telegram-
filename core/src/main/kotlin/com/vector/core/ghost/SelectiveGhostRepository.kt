package com.vector.core.ghost

import com.vector.core.*
import com.vector.core.auth.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex

/** Privacy operations are serialized separately from authentication and message requests. */
class SelectiveGhostRepository(authentication: AuthenticationRepository, private val client: GhostClient,
    private val store: GhostStore, private val demo: GhostRepository, private val scope: CoroutineScope) : GhostRepository {
    private val mutable = MutableStateFlow(GhostState())
    override val state = mutable.asStateFlow()
    override val preferences = demo.preferences
    private var sessionScope: CoroutineScope? = null
    private var account: Long? = null
    private var currentRules: List<PrivacyRule>? = null
    private var generation = 0L
    private var contactRevision = 0L
    private val contactVersions = mutableMapOf<Long, Long>()
    private val contactCache = mutableMapOf<Long, GhostContact>()
    private var contactPublication: Job? = null
    private val requests = Mutex()

    init {
        client.installActionPolicy { peer, action ->
            val current = mutable.value
            current.saved.allowsAction(peer, action, current.authenticated && current.initialized)
        }
        scope.launch {
            authentication.state.map { it.step == AuthStep.Ready }.distinctUntilChanged().collectLatest { ready ->
                generation++; account = null; currentRules = null; contactVersions.clear(); contactCache.clear(); contactPublication = null; contactRevision = 0
                mutable.value = GhostState(authenticated = ready, busy = ready)
                if (!ready) return@collectLatest
                coroutineScope {
                    sessionScope = this
                    try {
                        launch(start = CoroutineStart.UNDISPATCHED) {
                            client.events.collect { event -> when (event) {
                                is GhostEvent.Rules -> {
                                    currentRules = event.rules
                                    if (!mutable.value.busy) reconcileDisplay()
                                }
                                is GhostEvent.Contact -> {
                                    contactRevision++; contactVersions[event.contact.id] = contactRevision
                                    val changed = if (event.present) contactCache.put(event.contact.id, event.contact) != event.contact
                                        else contactCache.remove(event.contact.id) != null
                                    if (changed && contactPublication?.isActive != true) contactPublication = launch {
                                        yield()
                                        val contacts = contactCache.values.sortedBy { it.name.lowercase() }
                                        mutable.update { it.copy(contacts = contacts) }
                                    }
                                }
                            } }
                        }
                        initialize()
                        awaitCancellation()
                    } finally { sessionScope = null }
                }
            }
        }
    }
    private suspend fun initialize() {
        try {
            val id = client.accountId(); currentCoroutineContext().ensureActive(); account = id
            val saved = store.load(id); currentCoroutineContext().ensureActive()
            mutable.update { it.copy(saved = saved, initialized = true) }
            try { val contacts = clientContacts(); mutable.update { it.copy(contacts = contacts) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.update { it.copy(error = "Unable to load Telegram contacts. Refresh to retry.") } }
            try { currentRules = client.rules(); reconcilePending(id); reconcileDisplay() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.update { it.copy(error = "Unable to read Telegram privacy rules. Local controls remain separate.") } }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { mutable.update { it.copy(error = "Private preferences could not be loaded. Outgoing private-chat indicators are paused. Refresh to retry.") } }
        finally { mutable.update { it.copy(busy = false) } }
    }
    private suspend fun clientContacts(): List<GhostContact> {
        val before = contactRevision
        val remote = client.contacts(); currentCoroutineContext().ensureActive()
        val merged = (remote.filter { (contactVersions[it.id] ?: 0) <= before } +
            contactCache.values.filter { (contactVersions[it.id] ?: 0) > before }).distinctBy { it.id }.sortedBy { it.name.lowercase() }
        contactCache.clear(); merged.forEach { contactCache[it.id] = it }
        return merged
    }
    private fun reconcileDisplay() {
        val state = mutable.value
        val rules = currentRules
        val journal = state.saved.journal
        val conflict = (journal.applied != null && rules != null && rules != journal.applied) ||
            (journal.pending != null && rules != null && rules != journal.pending.before && rules != journal.pending.after)
        mutable.value = state.copy(serverLoaded = rules != null, conflict = conflict,
            confirmed = !conflict && journal.pending == null && journal.target == state.saved.desiredStatus() &&
                rules != null && excludesUsers(rules, state.saved.desiredStatus()))
    }
    private suspend fun persist(saved: GhostSaved) {
        val id = account ?: return
        store.save(id, saved); currentCoroutineContext().ensureActive()
        mutable.update { it.copy(saved = saved) }; reconcileDisplay()
    }
    private fun operation(requireInitialized: Boolean = true, block: suspend () -> Unit) {
        if (!mutable.value.authenticated || mutable.value.busy || (requireInitialized && !mutable.value.initialized) || !requests.tryLock()) return
        val currentScope = sessionScope
        if (currentScope == null) { requests.unlock(); return }
        mutable.update { it.copy(busy = true, error = null) }
        currentScope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.update { it.copy(error = "Privacy request failed. No success is assumed; refresh before retrying.", confirmed = false) } }
            finally { mutable.update { it.copy(busy = false) }; requests.unlock() }
        }
    }
    override fun enabled(value: Boolean) = operation { persist(mutable.value.saved.copy(enabled = value)) }
    override fun select(userId: Long) = operation {
        if (mutable.value.contacts.none { it.id == userId } || mutable.value.saved.contacts.size >= 1000) return@operation
        persist(mutable.value.saved.copy(contacts = mutable.value.saved.contacts + (userId to (mutable.value.saved.contacts[userId] ?: ContactPrivacy()))))
    }
    override fun remove(userId: Long) = operation { persist(mutable.value.saved.copy(contacts = mutable.value.saved.contacts - userId)) }
    override fun policy(userId: Long, value: ContactPrivacy) = operation {
        if (userId !in mutable.value.saved.contacts) return@operation
        persist(mutable.value.saved.copy(contacts = mutable.value.saved.contacts + (userId to value)))
    }
    override fun refresh() = operation(requireInitialized = false) {
        if (!mutable.value.initialized) initialize()
        else {
            val contacts = clientContacts(); mutable.update { it.copy(contacts = contacts) }
            currentRules = client.rules(); reconcilePending(account!!); reconcileDisplay()
        }
    }
    private suspend fun reconcilePending(id: Long) {
        val saved = mutable.value.saved
        val pending = saved.journal.pending ?: return
        val journal = when (currentRules) {
            pending.after -> saved.journal.copy(applied = currentRules, target = pending.target, pending = null)
            pending.before -> saved.journal.copy(pending = null)
            else -> { mutable.update { it.copy(conflict = true, confirmed = false) }; return }
        }
        store.save(id, saved.copy(journal = journal)); currentCoroutineContext().ensureActive()
        mutable.update { it.copy(saved = saved.copy(journal = journal)) }
    }
    override fun applyPrivacy() = operation {
        val id = account ?: return@operation
        val observed = client.rules(); currentRules = observed
        reconcilePending(id)
        val saved = mutable.value.saved
        val journal = saved.journal
        if (journal.pending != null || (journal.applied != null && journal.applied != observed)) {
            mutable.update { it.copy(conflict = true, confirmed = false, error = "Telegram rules changed. No rules were overwritten. Review the current rules before continuing.") }; return@operation
        }
        val baseline = journal.baseline ?: observed
        val desired = saved.desiredStatus()
        val proposed = try { statusExceptions(baseline, desired) } catch (_: IllegalArgumentException) {
            mutable.update { it.copy(error = "Telegram's 1,000 user-exception limit would be exceeded.") }; return@operation
        }
        if (proposed != observed) {
            val attempt = PrivacyAttempt(observed, proposed, desired)
            persist(saved.copy(journal = journal.copy(baseline = baseline, pending = attempt)))
            // Recheck the most recently received update before replacing this account-wide setting.
            if (currentRules != observed) { mutable.update { it.copy(conflict = true, confirmed = false) }; return@operation }
            client.setRules(proposed)
            val confirmed = client.rules(); currentCoroutineContext().ensureActive(); currentRules = confirmed
            if (!excludesUsers(confirmed, desired) || (desired.isEmpty() && confirmed != proposed)) {
                mutable.update { it.copy(conflict = true, confirmed = false, error = "Telegram privacy changes could not be confirmed. Refresh to review.") }; return@operation
            }
        }
        val applied = currentRules ?: observed
        val next = if (desired.isEmpty()) PrivacyJournal() else PrivacyJournal(baseline, applied, desired)
        persist(mutable.value.saved.copy(journal = next)); reconcileDisplay()
    }
    override fun useCurrentRules() = operation {
        // Explicit conflict recovery keeps all current server rules. It never guesses ownership of exceptions.
        currentRules = client.rules()
        persist(mutable.value.saved.copy(journal = PrivacyJournal()))
        mutable.update { it.copy(error = "Current Telegram rules kept. Existing exclusions remain; Apply only adds your selected exclusions.") }
    }
    override fun sendAction(chatId: Long, action: String) {
        if (!mutable.value.authenticated || !mutable.value.initialized) return
        sessionScope?.launch { try { client.chatAction(chatId, action) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) {} }
    }
    override fun update(contactId: Long, preferences: GhostPreferences) { if (!mutable.value.authenticated) demo.update(contactId, preferences) }
}
