package com.vector.messenger.telegram

import com.vector.core.auth.*
import org.json.JSONArray
import org.json.JSONObject

internal fun request(type: String, vararg fields: Pair<String, Any?>) = JSONObject().put("@type", type).apply {
    fields.forEach { (key, value) -> put(key, value ?: JSONObject.NULL) }
}

internal fun AuthAction.toRequest(): JSONObject = when (this) {
    is AuthAction.Phone -> request("setAuthenticationPhoneNumber", "phone_number" to value, "settings" to null)
    is AuthAction.Code -> request("checkAuthenticationCode", "code" to value)
    is AuthAction.Password -> request("checkAuthenticationPassword", "password" to value)
    is AuthAction.Email -> request("setAuthenticationEmailAddress", "email_address" to value)
    is AuthAction.EmailCode -> request("checkAuthenticationEmailCode", "code" to request("emailAddressAuthenticationCode", "code" to value))
    AuthAction.Qr -> request("requestQrCodeAuthentication", "other_user_ids" to JSONArray())
    AuthAction.Resend -> request("resendAuthenticationCode", "reason" to null)
    AuthAction.Logout -> request("logOut")
}

internal fun mapAuthorization(json: JSONObject): AuthStep = when (json.optString("@type")) {
    "authorizationStateWaitTdlibParameters" -> AuthStep.Starting
    "authorizationStateWaitPhoneNumber" -> AuthStep.Phone
    "authorizationStateWaitCode" -> {
        val info = json.optJSONObject("code_info")
        val delivery = when (info?.optJSONObject("type")?.optString("@type")) {
            "authenticationCodeTypeTelegramMessage" -> "Check Telegram on a device where you are already signed in."
            "authenticationCodeTypeSms", "authenticationCodeTypeSmsWord", "authenticationCodeTypeSmsPhrase" -> "Enter the code, word, or phrase Telegram sent by SMS."
            "authenticationCodeTypeCall", "authenticationCodeTypeMissedCall", "authenticationCodeTypeFlashCall" -> "Enter the verification information from Telegram's phone call."
            "authenticationCodeTypeFragment" -> "Retrieve your code from Fragment using your authorized wallet."
            else -> "Use the verification method Telegram provided. SMS delivery may be unavailable to third-party clients."
        }
        AuthStep.Code(delivery, info?.optJSONObject("next_type") != null)
    }
    "authorizationStateWaitPassword" -> AuthStep.Password(json.optString("password_hint").take(256))
    "authorizationStateWaitOtherDeviceConfirmation" -> {
        val link = json.optString("link")
        if (link.startsWith("tg://login?token=") && link.length <= 4096) AuthStep.Qr(link)
        else AuthStep.Unsupported("Telegram returned an unsupported QR login link.")
    }
    "authorizationStateWaitEmailAddress" -> AuthStep.Email
    "authorizationStateWaitEmailCode" -> AuthStep.EmailCode(json.optJSONObject("code_info")?.optString("email_address_pattern").orEmpty().take(256))
    "authorizationStateReady" -> AuthStep.Ready
    "authorizationStateLoggingOut", "authorizationStateClosing" -> AuthStep.LoggingOut
    "authorizationStateClosed" -> AuthStep.Closed
    "authorizationStateWaitRegistration" -> AuthStep.Unsupported("Create your account using an official Telegram application, then sign in here. Registration is not implemented in this phase.")
    "authorizationStateWaitPremiumPurchase" -> AuthStep.Unsupported("Telegram requires a purchase to complete this login. Use an official Telegram application for the required purchase; this client cannot bypass it.")
    else -> AuthStep.Unsupported("This authentication step is not supported by this build. Use an official Telegram client or update the integration.")
}

/** Never show arbitrary TDLib text. In particular, code 406 must not have its message processed. */
internal fun mapTdlibFailure(json: JSONObject): AuthFailure {
    val code = json.optInt("code")
    if (code == 406) return AuthFailure.Unavailable
    if (code == 429) return AuthFailure.RateLimited
    if (code >= 500 || code == 0) return AuthFailure.Network
    return when (json.optString("message").substringBefore(':').substringBefore(' ')) {
        "PHONE_NUMBER_INVALID", "PHONE_NUMBER_BANNED" -> AuthFailure.InvalidPhone
        "PHONE_CODE_INVALID", "PHONE_CODE_EMPTY", "PHONE_CODE_EXPIRED", "EMAIL_CODE_INVALID" -> AuthFailure.InvalidCode
        "PASSWORD_HASH_INVALID", "PASSWORD_TOO_FRESH" -> AuthFailure.InvalidPassword
        "FLOOD_WAIT", "FLOOD_PREMIUM_WAIT" -> AuthFailure.RateLimited
        else -> AuthFailure.Unavailable
    }
}
