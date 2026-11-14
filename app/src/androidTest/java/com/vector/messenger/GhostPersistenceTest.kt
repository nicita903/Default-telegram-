package com.vector.messenger

import androidx.test.platform.app.InstrumentationRegistry
import com.vector.core.ghost.*
import com.vector.messenger.feature.SecureGhostStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.KeyStore

class GhostPersistenceTest {
    @Test fun encryptedPreferencesAndRecoveryJournalSurviveReopeningAndAreAccountBound() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = "ghost-persistence-test"
        val alias = "DEFAULT.Ghost.Test.Persistence"
        val root = File(context.noBackupFilesDir, folder)
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        root.deleteRecursively(); keys.deleteEntry(alias)
        try {
            val store = SecureGhostStore(context, alias, folder)
            val original = listOf(PrivacyRule.Other("{\"@type\":\"userPrivacySettingRuleAllowAll\"}"))
            val proposed = statusExceptions(original, setOf(1234567890123))
            val saved = GhostSaved(true, mapOf(1234567890123L to ContactPrivacy(status = true)),
                PrivacyJournal(original, null, emptySet(), PrivacyAttempt(original, proposed, setOf(1234567890123))))
            store.save(111, saved)
            assertEquals(saved, SecureGhostStore(context, alias, folder).load(111))
            assertEquals(GhostSaved(), store.load(222))
            val encrypted = root.listFiles()!!.single().readBytes()
            assertFalse(String(encrypted, Charsets.ISO_8859_1).contains("1234567890123"))
            assertFalse(String(encrypted, Charsets.ISO_8859_1).contains("userPrivacySettingRule"))
            store.save(222, GhostSaved())
            val files = root.listFiles()!!.toList()
            val first = files.first { it.readBytes().contentEquals(encrypted) }
            val second = files.first { it != first }
            second.writeBytes(encrypted)
            try { store.load(222); fail("Another account's ciphertext must not decrypt") } catch (_: javax.crypto.AEADBadTagException) {}
        } finally { root.deleteRecursively(); keys.deleteEntry(alias) }
    }
    @Test fun lostKeystoreKeyDoesNotSilentlyResetExistingPrivatePreferences() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = "ghost-key-loss-test"; val alias = "DEFAULT.Ghost.Test.KeyLoss"
        val root = File(context.noBackupFilesDir, folder)
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        root.deleteRecursively(); keys.deleteEntry(alias)
        try {
            val store = SecureGhostStore(context, alias, folder)
            store.save(111, GhostSaved(true, mapOf(10L to ContactPrivacy())))
            val before = root.listFiles()!!.single().readBytes()
            keys.deleteEntry(alias)
            try { store.load(111); fail("A missing key must be reported") } catch (_: IllegalStateException) {}
            try { store.save(111, GhostSaved()); fail("Existing data must not be overwritten with a new key") } catch (_: IllegalStateException) {}
            assertArrayEquals(before, root.listFiles()!!.single().readBytes())
        } finally { root.deleteRecursively(); keys.deleteEntry(alias) }
    }
}
