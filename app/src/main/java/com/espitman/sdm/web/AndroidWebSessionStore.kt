package com.espitman.sdm.web

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONArray
import org.json.JSONObject

/** Phone authorization survives restarts; encryption keys never leave Android Keystore. */
internal class AndroidWebSessionStore(context: Context) : WebSessionStore {
    private val file = AtomicFile(File(context.noBackupFilesDir, "web-browsers.enc"))
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        }.generateKey()
    }
    override fun load(): List<StoredWebBrowser> = runCatching {
        if (!file.baseFile.exists()) return emptyList()
        val bytes = file.readFully()
        require(bytes.size in 29..65536 && bytes[0] == 1.toByte())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
        val json = JSONArray(String(cipher.doFinal(bytes.copyOfRange(13, bytes.size)), Charsets.UTF_8))
        require(json.length() <= 32)
        (0 until json.length()).map { i ->
            val row = json.getJSONObject(i)
            val id = row.getString("id"); val secret = row.getString("secret")
            require(id.matches(Regex("[A-Za-z0-9_-]{43}")) && secret.matches(Regex("[A-Za-z0-9_-]{43}")))
            val name = row.getString("browser"); val address = row.getString("address")
            require(name.length in 1..80 && address.length <= 80)
            StoredWebBrowser(WebAccess.Browser(id, name, address, row.getLong("approvedAt")), secret)
        }
    }.getOrDefault(emptyList())
    override fun save(sessions: List<StoredWebBrowser>) {
        require(sessions.size <= 32)
        val bytes = JSONArray(sessions.map { session -> JSONObject().put("id", session.browser.id)
            .put("browser", session.browser.browser).put("address", session.browser.address)
            .put("approvedAt", session.browser.approvedAt).put("secret", session.secret) }).toString().toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = byteArrayOf(1) + cipher.iv + cipher.doFinal(bytes)
        val output = file.startWrite()
        try { output.write(encrypted); file.finishWrite(output) }
        catch (error: Throwable) { file.failWrite(output); throw error }
    }
    companion object { private const val ALIAS = "sdm-web-browsers-v1" }
}
