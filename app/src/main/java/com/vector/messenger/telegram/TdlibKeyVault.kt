package com.vector.messenger.telegram

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Android Keystore wraps a random TDLib database key. No plaintext key file or silent key replacement. */
class TdlibKeyVault(private val context: Context, private val alias: String = "DEFAULT.TDLib.DatabaseWrapping.v1") {
    private val wrapped = AtomicFile(File(context.noBackupFilesDir, "telegram/database-key.v1"))

    @Synchronized fun loadOrCreate(database: File): ByteArray {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = keyStore.getKey(alias, null) as? SecretKey
        if (wrapped.baseFile.exists() || File(wrapped.baseFile.path + ".bak").exists()) {
            check(existing != null) { "Storage key unavailable" }
            val blob = wrapped.readFully()
            check(blob.size == 61 && blob[0] == 1.toByte()) { "Invalid key envelope" }
            return Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, existing, GCMParameterSpec(128, blob.copyOfRange(1, 13)))
                doFinal(blob.copyOfRange(13, blob.size)).also { check(it.size == 32) }
            }
        }
        val existingFiles = database.listFiles()
        check(!database.exists() || (database.isDirectory && existingFiles != null && existingFiles.isEmpty())) {
            "Existing database requires its original key"
        }
        val wrappingKey = existing ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).setRandomizedEncryptionRequired(true).build())
        }.generateKey()
        val plaintext = ByteArray(32).also { SecureRandom().nextBytes(it) }
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, wrappingKey) }
            val envelope = byteArrayOf(1) + cipher.iv + cipher.doFinal(plaintext)
            wrapped.baseFile.parentFile!!.mkdirs()
            val stream = wrapped.startWrite()
            try { stream.write(envelope); wrapped.finishWrite(stream) }
            catch (failure: Exception) { wrapped.failWrite(stream); throw failure }
            return plaintext
        } catch (failure: Exception) { plaintext.fill(0); throw failure }
    }

    /** Call only after TDLib confirms a completed logout and closes its database. */
    @Synchronized fun clearAfterLogout() {
        wrapped.delete()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
    }
}
