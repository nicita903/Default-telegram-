package com.vector.messenger.telegram

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import com.vector.core.auth.*
import org.drinkless.tdlib.JsonClient
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class TdlibIntegrationTest {
    @Test fun officialNativeLibraryCreatesAndClosesClientWithoutNetworkCredentials() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        org.junit.Assume.assumeTrue("Native smoke must not contend with a configured live session", runCatching { TelegramConfiguration.load(context) }.isFailure)
        assertEquals("ok", JSONObject(JsonClient.execute(request("setLogStream", "log_stream" to request("logStreamEmpty")).toString())!!).getString("@type"))
        val client = JsonClient.createClientId()
        JsonClient.send(client, request("getAuthorizationState").toString())
        var parametersRequested = false
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline && !parametersRequested) {
            val raw = JsonClient.receive(.1) ?: continue
            val event = JSONObject(raw)
            if (event.optInt("@client_id") == client && event.optJSONObject("authorization_state")?.optString("@type") == "authorizationStateWaitTdlibParameters") parametersRequested = true
        }
        JsonClient.send(client, request("close").toString())
        var closed = false
        val closeDeadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < closeDeadline && !closed) {
            val raw = JsonClient.receive(.1) ?: continue
            val event = JSONObject(raw)
            if (event.optInt("@client_id") == client && event.optJSONObject("authorization_state")?.optString("@type") == "authorizationStateClosed") closed = true
        }
        assertTrue("Official native client must request initialization parameters", parametersRequested)
        assertTrue("Official native client must close cleanly", closed)
    }

    @Test fun keystoreEnvelopeRestoresOriginalKeyAndNeverReplacesALostKey() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.noBackupFilesDir, "vault-test-${UUID.randomUUID()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(context) { override fun getNoBackupFilesDir() = directory }
        val alias = "DEFAULT.TDLib.Test.${UUID.randomUUID()}"
        val vault = TdlibKeyVault(isolated, alias)
        val database = File(directory, "database")
        var first: ByteArray? = null
        var restored: ByteArray? = null
        try {
            first = vault.loadOrCreate(database)
            restored = TdlibKeyVault(isolated, alias).loadOrCreate(database)
            assertTrue("A new vault instance must restore the same database key", first.contentEquals(restored))
            assertEquals(32, first.size)
            database.mkdirs()
            File(database, "sample-db").writeBytes(byteArrayOf(1))
            File(directory, "telegram/database-key.v1").delete()
            assertThrows(IllegalStateException::class.java) { vault.loadOrCreate(database) }
            val unreadable = object : File(database.path) { override fun listFiles(): Array<File>? = null }
            assertThrows(IllegalStateException::class.java) { vault.loadOrCreate(unreadable) }
            assertFalse(File(directory, "telegram/database-key.v1").exists())
            assertTrue(File(database, "sample-db").exists())
        } finally {
            first?.fill(0); restored?.fill(0)
            vault.clearAfterLogout() // Test-only alias and isolated storage, never a real session.
            directory.deleteRecursively()
        }
    }

    @Test fun mapsStatesRequestsAndSanitizedErrorsUsingThePinnedSchema() {
        assertEquals(AuthStep.Ready, mapAuthorization(request("authorizationStateReady")))
        assertTrue(mapAuthorization(request("authorizationStateWaitOtherDeviceConfirmation", "link" to "https://example.invalid")) is AuthStep.Unsupported)
        assertTrue(mapAuthorization(request("authorizationStateWaitOtherDeviceConfirmation", "link" to "tg://login?token=fake")) is AuthStep.Qr)
        assertEquals("checkAuthenticationPassword", AuthAction.Password("fake").toRequest().getString("@type"))
        assertEquals("logOut", AuthAction.Logout.toRequest().getString("@type"))
        assertEquals(AuthFailure.Unavailable, mapTdlibFailure(request("error", "code" to 406, "message" to "PASSWORD_HASH_INVALID")))
        assertEquals(AuthFailure.InvalidPassword, mapTdlibFailure(request("error", "code" to 400, "message" to "PASSWORD_HASH_INVALID")))
        assertEquals(AuthFailure.RateLimited, mapTdlibFailure(request("error", "code" to 429, "message" to "arbitrary private details")))
    }
}
