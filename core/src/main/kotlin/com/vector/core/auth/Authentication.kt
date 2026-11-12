package com.vector.core.auth

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

sealed interface AuthStep {
    data object Starting : AuthStep
    data object Phone : AuthStep
    data class Code(val delivery: String, val canResend: Boolean) : AuthStep
    data class Password(val hint: String) : AuthStep
    data class Qr(val link: String) : AuthStep { override fun toString() = "Qr([redacted])" }
    data object Email : AuthStep
    data class EmailCode(val pattern: String) : AuthStep
    data object Ready : AuthStep
    data object LoggingOut : AuthStep
    data object Closed : AuthStep
    data class SetupRequired(val reason: String) : AuthStep
    data class Unsupported(val explanation: String) : AuthStep
}

data class AuthState(val step: AuthStep = AuthStep.Starting, val busy: Boolean = false, val error: String? = null)

/** Intentionally redacted, including subclasses, so diagnostic interpolation cannot reveal input. */
sealed class AuthAction {
    final override fun toString() = "AuthAction([redacted])"
    class Phone(val value: String) : AuthAction()
    class Code(val value: String) : AuthAction()
    class Password(val value: String) : AuthAction()
    class Email(val value: String) : AuthAction()
    class EmailCode(val value: String) : AuthAction()
    data object Qr : AuthAction()
    data object Resend : AuthAction()
    data object Logout : AuthAction()
}

enum class AuthFailure(val description: String) {
    InvalidInput("Check the details and try again."),
    InvalidPhone("Check your phone number, including the country code."),
    InvalidCode("The code is invalid or expired. Check it or request another code when available."),
    InvalidPassword("The password was not accepted. Try again."),
    RateLimited("Telegram has limited further attempts. Please try again later."),
    Network("Unable to complete the request. Check your connection and try again."),
    Storage("Unable to unlock Telegram storage. Existing data has been preserved."),
    Unavailable("Telegram could not complete this request. Please try again."),
}

interface TelegramAuthClient {
    val steps: Flow<AuthStep>
    suspend fun start()
    suspend fun execute(action: AuthAction): AuthFailure?
}

interface AuthenticationRepository {
    val state: StateFlow<AuthState>
    fun start()
    suspend fun submit(action: AuthAction)
}

fun AuthStep.accepts(action: AuthAction): Boolean = when (action) {
    is AuthAction.Phone -> this == AuthStep.Phone
    is AuthAction.Code -> this is AuthStep.Code
    is AuthAction.Password -> this is AuthStep.Password
    is AuthAction.Email -> this == AuthStep.Email
    is AuthAction.EmailCode -> this is AuthStep.EmailCode
    AuthAction.Qr -> this == AuthStep.Phone
    AuthAction.Resend -> (this is AuthStep.Code && canResend) || this is AuthStep.EmailCode
    AuthAction.Logout -> this == AuthStep.Ready
}
