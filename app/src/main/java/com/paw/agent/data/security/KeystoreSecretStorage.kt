package com.paw.agent.data.security

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Android Keystore 硬件安全凭据加密与存储管理器。
 *
 * 采用 AES-256-GCM 认证加密模式保护敏感 API Key 与 Token，
 * 硬件安全模块 (TEE/StrongBox) 隔离密钥，防止明文泄露或被 Root/adb 提取。
 * 同时自适应单元测试 JVM 运行环境降级，保证自动化测试健壮性。
 */
object KeystoreSecretStorage {

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "agentpaw_master_credentials_key"
    private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_IV_LENGTH = 12
    private const val GCM_TAG_LENGTH_BITS = 128
    private const val ENCRYPTED_PREFIX = "enc:gcm:"

    // JVM 单元测试环境下的保底对称密钥 (32 bytes = 256 bits)
    private val JVM_FALLBACK_KEY = SecretKeySpec(
        byteArrayOf(
            0x2A, 0x4D, 0x72, 0x1E, 0x5C, 0x3B, 0x6F, 0x0A,
            0x41, 0x52, 0x63, 0x74, 0x30, 0x39, 0x22, 0x11,
            0x7A, 0x3B, 0x5C, 0x7D, 0x1E, 0x2F, 0x4A, 0x5B,
            0x6C, 0x7D, 0x0E, 0x1F, 0x2A, 0x3B, 0x4C, 0x5D,
        ),
        "AES",
    )

    private fun getOrCreateKey(): SecretKey {
        return runCatching {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (!keyStore.containsAlias(KEY_ALIAS)) {
                val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
                val spec = KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
                keyGen.init(spec)
                keyGen.generateKey()
            }
            keyStore.getKey(KEY_ALIAS, null) as SecretKey
        }.getOrElse { e ->
            // 仅在桌面 JVM 单元测试环境下允许使用保底测试密钥，在真实 Android 运行环境下强校验硬件 Keystore
            if (isJvmTestEnvironment()) {
                JVM_FALLBACK_KEY
            } else {
                throw SecurityException("AndroidKeyStore unavailable or master key cannot be initialized", e)
            }
        }
    }

    private fun isJvmTestEnvironment(): Boolean {
        val vendor = System.getProperty("java.vendor").orEmpty()
        return !vendor.contains("Android", ignoreCase = true)
    }

    /**
     * 将敏感明文字符串加密为安全密文存储格式。
     * 严禁在 Keystore 异常时静默降级为明文，杜绝 API Key 明文落盘泄露风险。
     */
    fun encrypt(plainText: String): String {
        if (plainText.isBlank()) return plainText
        return runCatching {
            val key = getOrCreateKey()
            val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            val cipherBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))

            val combined = ByteArray(iv.size + cipherBytes.size)
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(cipherBytes, 0, combined, iv.size, cipherBytes.size)

            val base64 = Base64.encodeToString(combined, Base64.NO_WRAP)
            "$ENCRYPTED_PREFIX$base64"
        }.getOrElse { e ->
            throw SecurityException("Keystore encryption failed. Refusing to store unencrypted credentials.", e)
        }
    }

    /**
     * 将密文字符串解密为明文。
     * 具备自动迁移感知：若输入非加密格式（旧版明文存储），则直接返回原值，
     * 待下次存入时自动升级加密。
     */
    fun decrypt(storedText: String): String {
        if (storedText.isBlank()) return storedText
        if (!storedText.startsWith(ENCRYPTED_PREFIX)) {
            // 兼容旧版本明文迁移
            return storedText
        }

        return runCatching {
            val rawBase64 = storedText.removePrefix(ENCRYPTED_PREFIX)
            val combined = Base64.decode(rawBase64, Base64.NO_WRAP)
            if (combined.size <= GCM_IV_LENGTH) return storedText

            val iv = combined.copyOfRange(0, GCM_IV_LENGTH)
            val cipherBytes = combined.copyOfRange(GCM_IV_LENGTH, combined.size)

            val key = getOrCreateKey()
            val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
            val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
            cipher.init(Cipher.DECRYPT_MODE, key, spec)

            val plainBytes = cipher.doFinal(cipherBytes)
            String(plainBytes, Charsets.UTF_8)
        }.getOrDefault(storedText)
    }
}
