package com.vector.messenger

import androidx.test.platform.app.InstrumentationRegistry
import com.vector.messenger.telegram.*
import com.vector.core.profile.ProfileConnection
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File

/** All data is an isolated synthetic fixture; no live account or TDLib instance is used. */
class TelegramProfileMappingTest {
    private lateinit var root: File
    @Before fun before() {
        root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "profile_mapping_test").apply { mkdirs() }
    }
    @After fun after() { root.deleteRecursively() }
    private fun user() = JSONObject("""{"@type":"user","id":1234567890123,"first_name":"Test","last_name":"Account","phone_number":"12025550123","usernames":{"active_usernames":["primary_handle","another_handle"],"editable_username":"disabled_handle"}}""")
    private fun file(path: String, completed: Boolean) = JSONObject().put("id", 7).put("local",
        JSONObject().put("path", path).put("is_downloading_completed", completed))
    @Test fun mapsNamesPhoneAndLongIdAndUsesFirstActiveUsername() {
        val mapped = mapProfile(user(), root)
        assertEquals(1234567890123L, mapped.id)
        assertEquals("Test", mapped.firstName); assertEquals("Account", mapped.lastName)
        assertEquals("primary_handle", mapped.username); assertEquals("12025550123", mapped.phoneNumber)
        assertNull(mapped.photo)
    }
    @Test fun missingUsernamePhotoAndOptionalTextNeverUseDemoOrDisabledHandles() {
        val json = user().put("usernames", JSONObject().put("editable_username", "disabled_handle"))
            .put("profile_photo", JSONObject.NULL).put("phone_number", "").put("last_name", "")
        val mapped = mapProfile(json, root)
        assertNull(mapped.username); assertNull(mapped.photo); assertNull(mapped.phoneNumber)
        assertEquals("Test", mapped.displayName)
        json.remove("usernames"); assertNull(mapProfile(json, root).username)
        json.put("last_name", JSONObject.NULL).put("phone_number", JSONObject.NULL)
        assertEquals("", mapProfile(json, root).lastName); assertNull(mapProfile(json, root).phoneNumber)
    }
    @Test fun completedSmallPhotoMustBeInsidePrivateTdlibFiles() {
        val photo = File(root, "small.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val json = user().put("profile_photo", JSONObject().put("small", file(photo.path, true)))
        assertEquals(photo.canonicalPath, mapProfile(json, root).photo?.localPath)
        assertEquals(7, mapProfile(json, root).photo?.fileId)
        assertNull(mapProfileFile(file(photo.path, false), root)?.localPath)
        assertNull(mapProfileFile(file(File(root, "missing.jpg").path, true), root)?.localPath)
        // Existing files outside the permitted directory are not exposed to the decoder.
        assertNull(mapProfileFile(file(File(root.parentFile, "../shared_prefs/appearance_v2.xml").path, true), root)?.localPath)
        assertNull(mapProfileFile(JSONObject().put("id", 0), root))
    }
    @Test fun connectionUpdatesMapToDisplayStatusOnly() {
        assertEquals(ProfileConnection.WaitingForNetwork, mapProfileConnection(JSONObject().put("@type", "connectionStateWaitingForNetwork")))
        assertEquals(ProfileConnection.Ready, mapProfileConnection(JSONObject().put("@type", "connectionStateReady")))
        assertEquals(ProfileConnection.Updating, mapProfileConnection(JSONObject().put("@type", "connectionStateUpdating")))
        assertEquals(ProfileConnection.Connecting, mapProfileConnection(JSONObject().put("@type", "connectionStateConnectingToProxy")))
    }
}
