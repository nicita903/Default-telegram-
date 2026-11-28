package com.vector.messenger.feature

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vector.messenger.telegram.canonicalJson
import java.security.KeyStore
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic rules and isolated Keystore aliases only; never changes a Telegram account. */
@RunWith(AndroidJUnit4::class)
class TelegramSettingsSecurityTest {
    private val rules = """[{"@type":"userPrivacySettingRuleRestrictUsers","user_ids":[7,9]},{"@type":"userPrivacySettingRuleAllowChatMembers","chat_ids":[-44]},{"@type":"userPrivacySettingRuleAllowPremiumUsers"},{"@type":"userPrivacySettingRuleAllowAll"}]"""
    @Test fun audienceChangeRetainsOrderedUserGroupAndOpaqueExceptions() {
        val changed = PrivacyRules.audience(rules, "My Contacts")
        val before = JSONArray(rules).jsonObjects().take(3)
        assertEquals(canonicalJson(JSONArray(before)), canonicalJson(JSONArray(changed.jsonObjects().take(3))))
        assertEquals("My Contacts", PrivacyRules.audience(changed.toString()))
    }
    @Test fun tdlibImplicitDenyIsRecognized() {
        assertTrue(PrivacyRules.equivalent("[]", """[{"@type":"userPrivacySettingRuleRestrictAll"}]"""))
        assertTrue(PrivacyRules.equivalent("""[{"@type":"userPrivacySettingRuleAllowContacts"}]""", PrivacyRules.audience(raw = "[]", label = "My Contacts").toString()))
    }
    @Test fun oppositeExceptionMustBeRemovedExplicitly() {
        try { PrivacyRules.exception(rules, true, false, 7, true); fail("Conflicting allow unexpectedly succeeded") }
        catch (_: SettingsFailure) { }
    }
    @Test fun removingOneExceptionRetainsOtherUsersAndRules() {
        val changed = PrivacyRules.exception(rules, false, false, 7, false)
        assertEquals(9L, changed.getJSONObject(0).getJSONArray("user_ids").getLong(0))
        assertEquals(1, changed.getJSONObject(0).getJSONArray("user_ids").length())
        assertEquals(canonicalJson(JSONArray(JSONArray(rules).jsonObjects().drop(1))), canonicalJson(JSONArray(changed.jsonObjects().drop(1))))
    }
    @Test fun addingGroupExceptionPlacesItBeforeGeneralAudience() {
        val changed = PrivacyRules.exception("""[{"@type":"userPrivacySettingRuleAllowAll"}]""", false, true, -67, true)
        assertEquals("userPrivacySettingRuleRestrictChatMembers", changed.getJSONObject(0).getString("@type"))
        assertEquals(-67L, changed.getJSONObject(0).getJSONArray("chat_ids").getLong(0))
    }
    @Test fun localLockRejectsWrongCodeSurvivesRecreationAndDoesNotStorePlaintext() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val namespace = "settings_security_test_${UUID.randomUUID()}"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        fun lock() = AppPasscode(context, scope, namespace, "$namespace.verifier", "$namespace.biometric")
        suspend fun settled(lock: AppPasscode) = withTimeout(15_000) { lock.state.first { !it.busy } }
        try {
            val first = lock()
            withContext(Dispatchers.Main) { first.configure("", "test-only-passcode", false, 0, false) {} }
            assertTrue(settled(first).enabled)
            assertFalse(first.state.value.locked)
            val preferences = context.getSharedPreferences(namespace, Context.MODE_PRIVATE)
            assertFalse(preferences.all.values.any { it.toString().contains("test-only-passcode") })
            val recreated = lock()
            assertTrue(recreated.state.value.locked)
            withContext(Dispatchers.Main) { recreated.unlock("wrong") {} }
            assertTrue(settled(recreated).locked)
            withContext(Dispatchers.Main) { recreated.unlock("test-only-passcode") {} }
            assertFalse(settled(recreated).locked)
            withContext(Dispatchers.Main) { recreated.background(); recreated.foreground() }
            assertTrue(recreated.state.value.locked)
            withContext(Dispatchers.Main) { recreated.unlock("test-only-passcode") {} }
            settled(recreated)
            withContext(Dispatchers.Main) { recreated.configure("wrong", null, true, 0, false) {} }
            assertTrue(settled(recreated).enabled)
            assertNotNull(preferences.getString("verifier", null))
        } finally {
            scope.cancel()
            context.deleteSharedPreferences(namespace)
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            store.deleteEntry("$namespace.verifier"); store.deleteEntry("$namespace.biometric")
        }
    }
}
