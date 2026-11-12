package com.vector.core.auth

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class DefaultAuthenticationRepository(
    private val client: TelegramAuthClient,
    private val scope: CoroutineScope,
) : AuthenticationRepository {
    private val mutableState = MutableStateFlow(AuthState())
    override val state = mutableState.asStateFlow()
    private val observing = AtomicBoolean(false)
    private val starting = AtomicBoolean(false)
    private val requests = Mutex()
    private val stateGuard = Mutex()
    private var revision = 0L

    override fun start() {
        if (observing.compareAndSet(false, true)) {
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                client.steps.collect { step ->
                    stateGuard.withLock { revision++; mutableState.value = AuthState(step) }
                }
            }
        }
        if (!starting.compareAndSet(false, true)) return
        scope.launch {
            stateGuard.withLock { mutableState.update { if (it.step == AuthStep.Closed) AuthState() else it } }
            try { client.start() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { stateGuard.withLock { mutableState.value = AuthState(AuthStep.SetupRequired("Telegram initialization failed. Check the local configuration and native build.")) } }
            finally { starting.set(false) }
        }
    }

    override suspend fun submit(action: AuthAction) {
        if (!requests.tryLock()) return // Drop duplicate submissions rather than queueing secrets.
        try {
            val requestRevision = stateGuard.withLock {
                val current = mutableState.value
                if (current.busy || !current.step.accepts(action)) return@withLock null
                if (!valid(action)) {
                    mutableState.value = current.copy(error = AuthFailure.InvalidInput.description)
                    return@withLock null
                }
                mutableState.value = current.copy(busy = true, error = null)
                revision
            } ?: return
            val failure = try { client.execute(action) }
            catch (cancelled: CancellationException) {
                withContext(NonCancellable) { stateGuard.withLock { if (revision == requestRevision) mutableState.update { it.copy(busy = false) } } }
                throw cancelled
            }
            catch (_: Exception) { AuthFailure.Unavailable }
            // A late error from an earlier auth step must never overwrite a newer state (especially Ready).
            stateGuard.withLock { if (revision == requestRevision) mutableState.update { it.copy(busy = false, error = failure?.description) } }
        } finally { requests.unlock() }
    }

    private fun valid(action: AuthAction): Boolean = when (action) {
        is AuthAction.Phone -> action.value.matches(Regex("\\+[0-9]{5,15}"))
        is AuthAction.Code -> action.value.isNotBlank() && action.value.length <= 128
        is AuthAction.Password -> action.value.isNotEmpty() && action.value.length <= 1024
        is AuthAction.Email -> action.value.contains('@') && action.value.length <= 254
        is AuthAction.EmailCode -> action.value.isNotBlank() && action.value.length <= 128
        else -> true
    }
}
