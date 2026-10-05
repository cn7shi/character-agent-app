package com.character.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.character.app.data.ConnectionMode
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

internal interface ConnectionStore {
    var mode: ConnectionMode
    var dark: Boolean
    fun save(url: String, credential: String)
    fun read(): Pair<String, String>?
    fun clear()
}

/** D19: only encrypted connection data goes to preferences; the AES key stays in Android Keystore. */
internal class ConnectionVault(context: Context) : ConnectionStore {
    private val preferences = context.getSharedPreferences("connection", Context.MODE_PRIVATE)
    private val alias = "character-development-connection"
    override var mode: ConnectionMode
        get() = ConnectionMode.entries.firstOrNull { it.name == preferences.getString("mode", null) } ?: ConnectionMode.REALTIME
        set(value) { check(preferences.edit().putString("mode", value.name).commit()) }
    override var dark: Boolean
        get() = preferences.getBoolean("dark", true)
        set(value) { preferences.edit().putBoolean("dark", value).apply() }
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    override fun save(url: String, credential: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = cipher.doFinal(JSONObject().put("url", url).put("credential", credential).toString().toByteArray(Charsets.UTF_8))
        check(preferences.edit().putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("payload", Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit())
    }
    override fun read(): Pair<String, String>? {
        val payload = preferences.getString("payload", null) ?: return null
        val iv = Base64.decode(checkNotNull(preferences.getString("iv", null)), Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)) }
        val value = JSONObject(String(cipher.doFinal(Base64.decode(payload, Base64.NO_WRAP)), Charsets.UTF_8))
        return value.getString("url") to value.getString("credential")
    }
    override fun clear() { check(preferences.edit().remove("iv").remove("payload").commit()) }
}
