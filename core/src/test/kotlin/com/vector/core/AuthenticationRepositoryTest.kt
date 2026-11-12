package com.vector.core

import com.vector.core.auth.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AuthenticationRepositoryTest {
    private class FakeClient : TelegramAuthClient {
        val updates = Channel<AuthStep>(Channel.UNLIMITED)
        override val steps = updates.receiveAsFlow()
        val actions = mutableListOf<AuthAction>()
        var restored: AuthStep = AuthStep.Phone
        var failure: AuthFailure? = null
        var gate: CompletableDeferred<Unit>? = null
        var starts = 0
        override suspend fun start() { starts++; updates.send(restored) }
        override suspend fun execute(action: AuthAction): AuthFailure? { actions += action; gate?.await(); return failure }
    }
    @Test fun phoneCodePasswordReadyAndLogoutTransitionsAreUpdateDriven() = runTest {
        val client = FakeClient()
        val repository = DefaultAuthenticationRepository(client, backgroundScope)
        repository.start(); runCurrent()
        assertEquals(AuthStep.Phone, repository.state.value.step)
        repository.submit(AuthAction.Phone("+12025550123"))
        assertEquals(AuthStep.Phone, repository.state.value.step) // An Ok response is not authentication.
        client.updates.send(AuthStep.Code("Check Telegram", false)); runCurrent()
        repository.submit(AuthAction.Code("00000"))
        client.updates.send(AuthStep.Password("sample hint")); runCurrent()
        repository.submit(AuthAction.Password("fake test password"))
        client.updates.send(AuthStep.Ready); runCurrent()
        assertEquals(AuthStep.Ready, repository.state.value.step)
        repository.submit(AuthAction.Logout)
        client.updates.send(AuthStep.LoggingOut); client.updates.send(AuthStep.Closed); runCurrent()
        assertEquals(AuthStep.Closed, repository.state.value.step)
        repository.start(); runCurrent()
        assertEquals(AuthStep.Phone, repository.state.value.step)
        assertEquals(2, client.starts)
    }
    @Test fun existingSessionCanRestoreDirectlyToReadyWithoutInput() = runTest {
        val client = FakeClient().apply { restored = AuthStep.Ready }
        val repository = DefaultAuthenticationRepository(client, backgroundScope)
        repository.start(); runCurrent()
        assertEquals(AuthStep.Ready, repository.state.value.step)
        assertTrue(client.actions.isEmpty())
    }
    @Test fun qrAndEmailStatesRemainReactive() = runTest {
        val client = FakeClient()
        val repository = DefaultAuthenticationRepository(client, backgroundScope)
        repository.start(); runCurrent()
        repository.submit(AuthAction.Qr)
        client.updates.send(AuthStep.Qr("tg://login?token=fake-one")); runCurrent()
        client.updates.send(AuthStep.Qr("tg://login?token=fake-two")); runCurrent()
        assertTrue(repository.state.value.step is AuthStep.Qr)
        client.updates.send(AuthStep.Email); runCurrent()
        repository.submit(AuthAction.Email("test@example.invalid"))
        client.updates.send(AuthStep.EmailCode("t***@example.invalid")); runCurrent()
        repository.submit(AuthAction.EmailCode("00000"))
        assertEquals(3, client.actions.size)
    }
    @Test fun invalidInputAndWrongStepNeverReachTheTransport() = runTest {
        val client = FakeClient()
        val repository = DefaultAuthenticationRepository(client, backgroundScope)
        repository.start(); runCurrent()
        repository.submit(AuthAction.Phone("123"))
        repository.submit(AuthAction.Password("fake"))
        repository.submit(AuthAction.Logout)
        assertTrue(client.actions.isEmpty())
        assertEquals(AuthFailure.InvalidInput.description, repository.state.value.error)
    }
    @Test fun errorsKeepTheCurrentStepAndNeverImplySuccess() = runTest {
        val client = FakeClient().apply { restored = AuthStep.Code("Check Telegram", true); failure = AuthFailure.InvalidCode }
        val repository = DefaultAuthenticationRepository(client, backgroundScope)
        repository.start(); runCurrent()
        repository.submit(AuthAction.Code("00000"))
        assertTrue(repository.state.value.step is AuthStep.Code)
        assertFalse(repository.state.value.busy)
        assertEquals(AuthFailure.InvalidCode.description, repository.state.value.error)
    }
    @Test fun lateErrorCannotOverwriteAReadyUpdate() = runTest {
        val client = FakeClient().apply { gate = CompletableDeferred(); failure = AuthFailure.Network }
        val repository = DefaultAuthenticationRepository(client, backgroundScope)
        repository.start(); runCurrent()
        val request = launch { repository.submit(AuthAction.Phone("+12025550123")) }
        runCurrent(); assertTrue(repository.state.value.busy)
        client.updates.send(AuthStep.Ready); runCurrent()
        client.gate!!.complete(Unit); request.join()
        assertEquals(AuthStep.Ready, repository.state.value.step)
        assertNull(repository.state.value.error)
    }
    @Test fun secretsAndQrTokensAreRedactedFromDiagnosticStrings() {
        assertFalse(AuthAction.Password("fake-secret").toString().contains("fake-secret"))
        assertFalse(AuthAction.Code("fake-code").toString().contains("fake-code"))
        assertFalse(AuthStep.Qr("tg://login?token=fake-token").toString().contains("fake-token"))
    }
}
