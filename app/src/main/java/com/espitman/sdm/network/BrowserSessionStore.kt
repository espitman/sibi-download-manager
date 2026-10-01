package com.espitman.sdm.network

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Download grants are encrypted separately from exports and Android backups. */
internal class BrowserSessionStore(context: Context) {
    private val directory = File(context.noBackupFilesDir, "browser-download-sessions")
    private fun file(id: String) = AtomicFile(File(directory, MessageDigest.getInstance("SHA-256")
        .digest(id.toByteArray()).joinToString("") { "%02x".format(it) }))
    private fun key(): SecretKey = synchronized(BrowserSessionStore::class.java) {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance("AES", "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    @Synchronized fun save(id: String, context: ScopedRequestContext) {
        check(directory.isDirectory || directory.mkdirs()) { "Unable to retain browser session" }
        val retained = !context.isPrivate && context.retainSession
        val json = JSONObject().put("origin", context.originUrl).put("private", context.isPrivate)
            .put("requiresSignIn", context.requiresSignIn || !retained)
        if (retained) {
            json.put("cookie", context.cookie).put("userAgent", context.userAgent).put("referer", context.referer)
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))
        val atomic = file(id)
        val output = atomic.startWrite()
        try { output.write(cipher.iv.size); output.write(cipher.iv); output.write(encrypted); atomic.finishWrite(output) }
        catch (failure: Exception) { atomic.failWrite(output); throw failure }
    }
    @Synchronized fun read(id: String): ScopedRequestContext? {
        val atomic = file(id)
        if (!atomic.baseFile.exists()) return null
        return try {
            val bytes = atomic.readFully()
            val ivSize = bytes[0].toInt() and 255
            require(ivSize == 12 && bytes.size > ivSize + 17)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(1, ivSize + 1)))
            }
            val json = JSONObject(String(cipher.doFinal(bytes.copyOfRange(ivSize + 1, bytes.size)), Charsets.UTF_8))
            fun optional(name: String) = if (json.isNull(name)) null else json.optString(name).takeIf { it.isNotEmpty() }
            ScopedRequestContext(json.getString("origin"), optional("cookie"), optional("userAgent"), optional("referer"),
                isPrivate = json.optBoolean("private"), requiresSignIn = json.optBoolean("requiresSignIn"))
        } catch (_: Exception) {
            // Fail closed if the grant is corrupt or the Keystore key became unavailable.
            ScopedRequestContext("", requiresSignIn = true)
        }
    }
    @Synchronized fun remove(id: String) { file(id).delete() }
    private companion object { const val ALIAS = "sdm.browser.download.session.v1" }
}
