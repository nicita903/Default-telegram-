package com.vector.messenger.feature

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.vector.core.ghost.*
import com.vector.messenger.telegram.decodePrivacyRules
import com.vector.messenger.telegram.encodePrivacyRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Separate platform-keystore protection for local preferences. Never accesses TDLib keys/files. */
class SecureGhostStore(private val context: Context, private val alias: String = "DEFAULT.Ghost.Local.v1", private val folder: String = "ghost") : GhostStore {
    private fun file(account: Long): AtomicFile {
        require(account > 0)
        val hash = MessageDigest.getInstance("SHA-256").digest(account.toString().toByteArray()).joinToString("") { "%02x".format(it) }
        return AtomicFile(File(File(context.noBackupFilesDir, folder), "$hash.v1"))
    }
    private fun key(existing: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = store.getKey(alias, null) as? SecretKey
        if (key != null) return key
        check(!existing) { "Private preference key unavailable" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }
    override suspend fun load(accountId: Long): GhostSaved = withContext(Dispatchers.IO) {
        val file = file(accountId)
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return@withContext GhostSaved()
        val bytes = file.readFully(); check(bytes.size in 30..2_000_000 && bytes[0] == 1.toByte())
        val plain = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key(true), GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
            updateAAD(accountId.toString().toByteArray()); doFinal(bytes.copyOfRange(13, bytes.size))
        }
        try { decodeGhostSaved(JSONObject(String(plain, Charsets.UTF_8))) } finally { plain.fill(0) }
    }
    override suspend fun save(accountId: Long, value: GhostSaved): Unit = withContext(Dispatchers.IO) {
        val file = file(accountId)
        val existing = file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()
        val plain = encodeGhostSaved(value).toString().toByteArray(Charsets.UTF_8)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, key(existing)); updateAAD(accountId.toString().toByteArray())
            }
            val encrypted = byteArrayOf(1) + cipher.iv + cipher.doFinal(plain)
            check(file.baseFile.parentFile!!.exists() || file.baseFile.parentFile!!.mkdirs())
            val stream = file.startWrite()
            try { stream.write(encrypted); file.finishWrite(stream) } catch (failure: Exception) { file.failWrite(stream); throw failure }
        } finally { plain.fill(0) }
    }
}

internal fun encodeGhostSaved(saved: GhostSaved): JSONObject = JSONObject().put("enabled", saved.enabled)
    .put("contacts", JSONArray().apply { saved.contacts.toSortedMap().forEach { (id, value) -> put(JSONObject().put("id", id)
        .put("typing", value.typing).put("recording", value.recording).put("status", value.status)) } })
    .put("baseline", saved.journal.baseline?.let(::encodePrivacyRules) ?: JSONObject.NULL)
    .put("applied", saved.journal.applied?.let(::encodePrivacyRules) ?: JSONObject.NULL)
    .put("target", JSONArray(saved.journal.target.sorted()))
    .put("pending", saved.journal.pending?.let { JSONObject().put("before", encodePrivacyRules(it.before)).put("after", encodePrivacyRules(it.after)).put("target", JSONArray(it.target.sorted())) } ?: JSONObject.NULL)

internal fun decodeGhostSaved(json: JSONObject): GhostSaved {
    fun ids(array: JSONArray?) = (0 until (array?.length() ?: 0)).map { array!!.getLong(it) }.filter { it > 0 }.toSet()
    val array = json.optJSONArray("contacts") ?: JSONArray()
    check(array.length() <= 1000)
    val contacts = (0 until array.length()).associate { index -> array.getJSONObject(index).let {
        val id = it.getLong("id"); require(id > 0)
        id to ContactPrivacy(it.optBoolean("typing", true), it.optBoolean("recording", true), it.optBoolean("status"))
    } }
    val pending = json.optJSONObject("pending")?.let { PrivacyAttempt(decodePrivacyRules(it.getJSONArray("before")), decodePrivacyRules(it.getJSONArray("after")), ids(it.optJSONArray("target"))) }
    return GhostSaved(json.optBoolean("enabled"), contacts, PrivacyJournal(json.optJSONArray("baseline")?.let(::decodePrivacyRules),
        json.optJSONArray("applied")?.let(::decodePrivacyRules), ids(json.optJSONArray("target")), pending))
}
