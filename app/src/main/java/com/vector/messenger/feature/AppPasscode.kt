package com.vector.messenger.feature

import android.app.Activity
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.os.SystemClock
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.*
import com.vector.messenger.design.*
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class PasscodeState(val enabled: Boolean = false, val locked: Boolean = false, val busy: Boolean = false,
    val biometric: Boolean = false, val autoLock: Int = 0, val error: String? = null)

/** Local UI lock. The verifier is salted, stretched and encrypted by a non-exportable Keystore key. */
@Singleton
class AppPasscode internal constructor(private val context: Context, private val scope: CoroutineScope,
    private val storageName: String, private val keyAlias: String, private val biometricAlias: String) {
    @Inject constructor(@ApplicationContext context: Context, scope: CoroutineScope) : this(context, scope,
        "default_local_lock_v1", "Default.LocalLock.Verifier.v1", "Default.LocalLock.Biometric.v1")
    private val preferences = context.getSharedPreferences(storageName, Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(PasscodeState(enabled = preferences.getBoolean("enabled", false),
        locked = preferences.getBoolean("enabled", false), biometric = preferences.getBoolean("biometric", false), autoLock = preferences.getInt("interval", 0)))
    val state = mutable.asStateFlow()
    private var backgroundAt = 0L
    private var biometricRequest: CancellationSignal? = null
    private var backgroundLock: Job? = null
    private val random = SecureRandom()
    private var epoch = 0L
    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun key(alias: String, create: Boolean, biometric: Boolean = false): SecretKey {
        (keyStore().getKey(alias, null) as? SecretKey)?.let { return it }
        check(create) { "Unavailable lock key" }
        val specification = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        if (biometric) {
            specification.setUserAuthenticationRequired(true).setInvalidatedByBiometricEnrollment(true)
            if (Build.VERSION.SDK_INT >= 30) specification.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
            else specification.setUserAuthenticationValidityDurationSeconds(-1)
        }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply { init(specification.build()) }.generateKey()
    }
    private fun stretch(passcode: String, salt: ByteArray): ByteArray {
        val chars = passcode.toCharArray()
        val specification = PBEKeySpec(chars, salt, 210_000, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(specification).encoded }
        finally { chars.fill('\u0000'); specification.clearPassword() }
    }
    private fun verify(passcode: String): Boolean {
        val blob = Base64.decode(preferences.getString("verifier", null) ?: return false, Base64.NO_WRAP)
        require(blob.size > 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(keyAlias, false), GCMParameterSpec(128, blob.copyOfRange(0, 12)))
        val clear = cipher.doFinal(blob.copyOfRange(12, blob.size))
        return try {
            require(clear.size == 48)
            val computed = stretch(passcode, clear.copyOfRange(0, 16))
            try { MessageDigest.isEqual(clear.copyOfRange(16, 48), computed) } finally { computed.fill(0) }
        } finally { clear.fill(0); blob.fill(0) }
    }
    private fun encryptedVerifier(passcode: String): String {
        val salt = ByteArray(16).also(random::nextBytes)
        val digest = stretch(passcode, salt)
        val clear = salt + digest
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key(keyAlias, true)) }
            return Base64.encodeToString(cipher.iv + cipher.doFinal(clear), Base64.NO_WRAP)
        } finally { salt.fill(0); digest.fill(0); clear.fill(0) }
    }
    fun foreground() {
        backgroundLock?.cancel(); backgroundLock = null
        if (state.value.enabled && backgroundAt > 0 && SystemClock.elapsedRealtime() - backgroundAt >= state.value.autoLock * 1000L) lock()
        backgroundAt = 0L
    }
    fun background() {
        cancelBiometric()
        backgroundAt = SystemClock.elapsedRealtime()
        if (state.value.enabled) {
            if (state.value.autoLock == 0) lock()
            else backgroundLock = scope.launch { delay(state.value.autoLock * 1000L); lock() }
        }
    }
    fun lock() { if (state.value.enabled) { epoch++; mutable.update { it.copy(locked = true) } } }
    fun cancelBiometric() { biometricRequest?.cancel(); biometricRequest = null }
    private fun authenticated(current: String): Boolean {
        if (System.currentTimeMillis() < preferences.getLong("retry", 0)) throw SettingsFailure("Too many attempts. Wait before trying again.")
        if (!verify(current)) {
            val failures = preferences.getInt("failures", 0) + 1
            val delay = if (failures < 5) 0L else (30_000L shl (failures - 5).coerceAtMost(7)).coerceAtMost(3_600_000L)
            preferences.edit().putInt("failures", failures).putLong("retry", System.currentTimeMillis() + delay).commit()
            throw SettingsFailure("Incorrect passcode.${if (delay > 0) " Try again later." else ""}")
        }
        preferences.edit().putInt("failures", 0).putLong("retry", 0).commit()
        return true
    }
    private fun operate(action: suspend () -> Unit) {
        if (state.value.busy) return
        mutable.update { it.copy(busy = true, error = null) }
        scope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { mutable.update { it.copy(error = (error as? SettingsFailure)?.message ?: "Local lock could not be verified. Use your passcode; lock data has not been reset.") } }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }
    fun unlock(passcode: String, success: () -> Unit) = operate {
        val token = epoch
        withContext(Dispatchers.Default) { authenticated(passcode) }
        if (token == epoch) { mutable.update { it.copy(locked = false) }; success() }
    }
    fun configure(current: String, new: String?, disable: Boolean, interval: Int, biometric: Boolean, success: () -> Unit) = operate {
        val token = epoch
        val verifier = withContext(Dispatchers.Default) {
            if (state.value.enabled) authenticated(current)
            if (token != epoch || state.value.locked) throw SettingsFailure("Unlock Default before changing its passcode.")
            if (disable) {
                keyStore().deleteEntry(biometricAlias)
                null
            } else {
                val encrypted = if (new != null) { if (new.length < 6) throw SettingsFailure("Use at least 6 characters."); encryptedVerifier(new) } else null
                require(interval in listOf(0, 60, 300, 3600))
                if (biometric) { check(biometricSupported()); key(biometricAlias, true, true) }
                else keyStore().deleteEntry(biometricAlias)
                encrypted
            }
        }
        if (token != epoch || state.value.locked) throw SettingsFailure("Unlock Default before changing its passcode.")
        val editor = preferences.edit().putBoolean("enabled", !disable).putBoolean("biometric", biometric && !disable).putInt("interval", interval)
        if (disable) editor.remove("verifier") else if (verifier != null) editor.putString("verifier", verifier)
        check(editor.commit())
        mutable.update { it.copy(enabled = !disable, locked = false, autoLock = interval, biometric = biometric && !disable) }
        success()
    }
    fun biometricSupported(): Boolean {
        val manager = context.getSystemService(BiometricManager::class.java)
        return if (Build.VERSION.SDK_INT >= 30) manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS
        else manager.canAuthenticate() == BiometricManager.BIOMETRIC_SUCCESS
    }
    fun biometric(activity: Activity) {
        if (!state.value.enabled || !state.value.locked || !state.value.biometric || state.value.busy) return
        cancelBiometric()
        val token = epoch
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key(biometricAlias, false)) }
            val prompt = BiometricPrompt.Builder(activity).setTitle("Unlock Default").setNegativeButton("Use passcode", activity.mainExecutor) { _, _ -> cancelBiometric() }
            if (Build.VERSION.SDK_INT >= 30) prompt.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            val cancellation = CancellationSignal().also { biometricRequest = it }
            prompt.build().authenticate(BiometricPrompt.CryptoObject(cipher), cancellation, activity.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    try {
                        if (cancellation.isCanceled || token != epoch) return
                        val authorized = result.cryptoObject?.cipher ?: return
                        authorized.doFinal(ByteArray(32).also(random::nextBytes))
                        mutable.update { it.copy(locked = false, error = null) }
                    } catch (_: Exception) { mutable.update { it.copy(error = "Biometric key unavailable. Use your passcode.") } }
                }
            })
        } catch (_: Exception) { mutable.update { it.copy(error = "Biometric unlock unavailable. Use your passcode.") } }
    }
}

@Composable internal fun AppUnlockScreen(lock: AppPasscode) {
    val state by lock.state.collectAsStateWithLifecycle()
    val activity = LocalContext.current as? Activity
    var passcode by remember { mutableStateOf("") }
    SecureSettingsWindow()
    BackHandler { activity?.moveTaskToBack(true) }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { passcode = ""; lock.cancelBiometric() }
    Column(Modifier.fillMaxSize().background(DefaultDesign.colors.base).safeDrawingPadding().imePadding().padding(28.dp), verticalArrangement = Arrangement.Center) {
        Text("Default is locked", color = DefaultDesign.colors.text, style = MaterialTheme.typography.titleLarge)
        SecretInput(passcode, { passcode = it }, "Passcode")
        state.error?.let { SettingsNote(it) }
        DefaultAction("Unlock", { val input = passcode; passcode = ""; lock.unlock(input) {} }, enabled = !state.busy && passcode.isNotEmpty())
        if (state.biometric && activity != null) DefaultAction("Use biometrics", { passcode = ""; lock.biometric(activity) }, enabled = !state.busy)
    }
}

@Composable internal fun PasscodeSettings(lock: AppPasscode) {
    val state by lock.state.collectAsStateWithLifecycle()
    var mode by remember { mutableStateOf("") }
    var current by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    var biometric by remember(state.biometric) { mutableStateOf(state.biometric) }
    var interval by remember(state.autoLock) { mutableIntStateOf(state.autoLock) }
    var confirm by remember { mutableStateOf(false) }
    fun clear() { current = ""; new = ""; repeat = ""; mode = "" }
    SecureSettingsWindow()
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { clear() }
    fun apply() {
        lock.configure(current, new.takeIf { mode in listOf("enable", "change") }, mode == "disable", interval, biometric, ::clear)
        current = ""; new = ""; repeat = ""
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding()) {
        SettingsHeading("Local security · this device")
        SettingsNote("Protect Default with a local passcode. This does not change your Telegram password or Android screen lock. Remember this code: there is no in-app recovery bypass.")
        state.error?.let { SettingsNote(it) }
        if (mode.isEmpty()) {
            DefaultAction(if (state.enabled) "Change passcode" else "Enable passcode", { mode = if (state.enabled) "change" else "enable" }, enabled = !state.busy)
            if (state.enabled) {
                DefaultAction("Auto-lock & biometrics", { mode = "options" }, enabled = !state.busy)
                DefaultAction("Disable passcode", { mode = "disable" }, enabled = !state.busy, destructive = true)
                DefaultAction("Lock now", lock::lock, enabled = !state.busy)
            }
        } else {
            if (state.enabled) SecretInput(current, { current = it }, "Current passcode")
            if (mode in listOf("enable", "change")) {
                SecretInput(new, { new = it }, "New passcode · at least 6 characters")
                SecretInput(repeat, { repeat = it }, "Repeat passcode")
            }
            if (mode in listOf("enable", "options")) {
                SettingsHeading("Auto-lock after leaving Default")
                listOf(0 to "Immediately", 60 to "1 minute", 300 to "5 minutes", 3600 to "1 hour").forEach { (seconds, title) ->
                    CompactSettingsRow(title, icon = VectorIcons.Clock, selected = interval == seconds, chevron = false) { interval = seconds }
                }
                if (lock.biometricSupported()) CompactSettingsRow("Biometric unlock", icon = SettingsIcons.Lock, checked = biometric) { biometric = !biometric }
                else SettingsNote("Strong biometrics are unavailable. Your passcode remains available.")
            }
            DefaultAction("Save", { if (mode == "disable") confirm = true else apply() }, enabled = !state.busy && (!state.enabled || current.isNotEmpty()) && (mode !in listOf("enable", "change") || (new.length >= 6 && new == repeat)))
            DefaultAction("Cancel", ::clear, enabled = !state.busy)
        }
    }
    if (confirm) ConfirmSettingsAction("Disable local lock?", "Your conversations will open without Default’s passcode.", { confirm = false }) { confirm = false; apply() }
}
