package com.vector.messenger.feature

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.vector.core.auth.*
import com.vector.core.profile.ProfileState
import com.vector.messenger.design.DefaultAction
import com.vector.messenger.design.DefaultIconControl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun AuthenticationDialog(viewModel: AuthenticationViewModel, profile: ProfileViewModel, onClose: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val profileState by profile.state.collectAsStateWithLifecycle()
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false, securePolicy = SecureFlagPolicy.SecureOn)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            AuthenticationContent(state, viewModel::submit, viewModel::start, onClose, profileState, profile::refresh)
        }
    }
}

@Composable
internal fun AuthenticationContent(state: AuthState, submit: (AuthAction) -> Unit, start: () -> Unit, onClose: () -> Unit,
    profileState: ProfileState = ProfileState(authenticated = true, loading = true), refreshProfile: () -> Unit = {}) {
    val step = state.step
    // Authentication input is deliberately not rememberSaveable and never enters a SavedStateHandle.
    var input by remember(step) { mutableStateOf("") }
    var logoutConfirmation by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Telegram account", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            DefaultIconControl(Icons.Default.Close, "Close account", { input = ""; onClose() }, quiet = true)
        }
        when (step) {
            AuthStep.Starting -> { CircularProgressIndicator(Modifier.size(28.dp)); Text("Restoring your Telegram session…") }
            AuthStep.Phone, is AuthStep.Code, is AuthStep.Password, AuthStep.Email, is AuthStep.EmailCode -> {
                val title = when (step) {
                    AuthStep.Phone -> "Your phone number"
                    is AuthStep.Code -> "Verification code"
                    is AuthStep.Password -> "Two-step verification"
                    AuthStep.Email -> "Email address"
                    else -> "Email verification"
                }
                Text(title, style = MaterialTheme.typography.headlineSmall)
                Text(when (step) {
                    AuthStep.Phone -> "Enter your phone number with its country code, for example +441234567890."
                    is AuthStep.Code -> step.delivery
                    is AuthStep.Password -> if (step.hint.isEmpty()) "Enter your Telegram two-step verification password." else "Password hint: ${step.hint}"
                    AuthStep.Email -> "Telegram requires an email address for this sign-in."
                    is AuthStep.EmailCode -> "Enter the code sent to ${step.pattern}."
                }, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val secret = step is AuthStep.Password || step is AuthStep.Code || step is AuthStep.EmailCode
                OutlinedTextField(
                    value = input, onValueChange = { input = it }, label = { Text(title) }, singleLine = true, enabled = !state.busy,
                    visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = when (step) { AuthStep.Phone -> KeyboardType.Phone; AuthStep.Email -> KeyboardType.Email; else -> KeyboardType.Password }),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(enabled = (if (step is AuthStep.Password) input.isNotEmpty() else input.isNotBlank()) && !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), onClick = {
                    val value = input
                    input = ""
                    submit(when (step) {
                        AuthStep.Phone -> AuthAction.Phone(value.replace(" ", "").replace("-", ""))
                        is AuthStep.Code -> AuthAction.Code(value.trim())
                        is AuthStep.Password -> AuthAction.Password(value)
                        AuthStep.Email -> AuthAction.Email(value.trim())
                        else -> AuthAction.EmailCode(value.trim())
                    })
                }) { Text(if (state.busy) "Checking…" else "Continue") }
                if (step == AuthStep.Phone) TextButton(enabled = !state.busy, onClick = { input = ""; submit(AuthAction.Qr) }) { Text("Sign in with a QR code") }
                if ((step is AuthStep.Code && step.canResend) || step is AuthStep.EmailCode) {
                    TextButton(enabled = !state.busy, onClick = { submit(AuthAction.Resend) }) { Text("Request another code") }
                    Text("Telegram enforces the resend waiting period.", style = MaterialTheme.typography.bodySmall)
                }
                Text("Enter verification details only here. They are not saved or written to logs.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            is AuthStep.Qr -> {
                Text("Scan to sign in", style = MaterialTheme.typography.headlineSmall)
                Text("On another signed-in device, open Telegram → Settings → Devices → Link Desktop Device and scan this code.")
                LoginQr(step.link)
                Text("The code refreshes when Telegram provides a new link. Keep this screen private.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            AuthStep.Ready -> {
                Text("Signed in to Telegram", style = MaterialTheme.typography.titleMedium)
                AccountProfileContent(profileState, refreshProfile)
                Text("Telegram profile · chats remain local demo data.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                DefaultAction("Log out", { logoutConfirmation = true }, enabled = !state.busy)
            }
            AuthStep.LoggingOut -> { CircularProgressIndicator(Modifier.size(28.dp)); Text("Closing your Telegram session… Keep a network connection available to complete logout.") }
            AuthStep.Closed -> { Text("Session closed"); Button(onClick = start) { Text("Sign in again") } }
            is AuthStep.SetupRequired -> { Text("Developer setup required", style = MaterialTheme.typography.headlineSmall); Text(step.reason) }
            is AuthStep.Unsupported -> { Text("Additional Telegram step", style = MaterialTheme.typography.headlineSmall); Text(step.explanation) }
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
    if (logoutConfirmation) AlertDialog(onDismissRequest = { logoutConfirmation = false }, title = { Text("Log out of Telegram?") }, text = { Text("This closes the Telegram session and removes its local TDLib data after Telegram confirms logout. Demo chats are separate.") },
        confirmButton = { TextButton(onClick = { logoutConfirmation = false; submit(AuthAction.Logout) }) { Text("Log out") } }, dismissButton = { TextButton(onClick = { logoutConfirmation = false }) { Text("Cancel") } })
}

@Composable
private fun LoginQr(link: String) {
    val bitmap by produceState<ImageBitmap?>(null, link) {
        value = withContext(Dispatchers.Default) {
            val matrix = QRCodeWriter().encode(link, BarcodeFormat.QR_CODE, 640, 640)
            val pixels = IntArray(640 * 640) { index -> if (matrix[index % 640, index / 640]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
            Bitmap.createBitmap(pixels, 640, 640, Bitmap.Config.ARGB_8888).asImageBitmap()
        }
    }
    bitmap?.let { Image(it, "Telegram login QR code", Modifier.fillMaxWidth().aspectRatio(1f).padding(8.dp)) }
}
