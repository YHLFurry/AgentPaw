package com.paw.agent.chatgpt

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal interface ChatGptCredentialStorage {
    fun read(): JSONObject
    fun write(data: JSONObject)
}

/** Retains the original encrypted format; credentials never fall back to plaintext. */
internal class EncryptedChatGptCredentialStorage(context: Context) : ChatGptCredentialStorage {
    private val file = AtomicFile(File(context.applicationContext.noBackupFilesDir, "chatgpt-plan.enc"))

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        }.generateKey()
    }

    override fun read(): JSONObject {
        val bytes = try {
            file.openRead().use { it.readBytes() }
        } catch (e: java.io.FileNotFoundException) {
            if (!file.baseFile.exists()) return JSONObject()
            throw e
        }
        check(bytes.size > 28) { "Invalid encrypted credentials" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return JSONObject(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
    }

    override fun write(data: JSONObject) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val output = file.startWrite()
        try {
            output.write(cipher.iv + cipher.doFinal(data.toString().toByteArray(Charsets.UTF_8)))
            file.finishWrite(output)
        } catch (e: Exception) {
            file.failWrite(output)
            throw e
        }
    }

    private companion object { const val ALIAS = "agentpaw_chatgpt_plan_v1" }
}
