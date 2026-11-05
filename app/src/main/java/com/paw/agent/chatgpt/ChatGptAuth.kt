package com.paw.agent.chatgpt

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.math.BigInteger
import java.net.InetAddress
import java.net.ServerSocket
import java.security.KeyFactory
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Credentials never leave app-private noBackup storage except for TLS requests to OpenAI. */
class ChatGptAuth private constructor(context: Context) {
    private val file = File(context.applicationContext.noBackupFilesDir, "chatgpt-plan.enc")
    private val mutex = Mutex()
    private val http = OkHttpClient.Builder().callTimeout(60, TimeUnit.SECONDS).build()
    @Volatile var status: String = ""
        private set
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    @Synchronized private fun read(): JSONObject {
        if (!file.exists()) return JSONObject()
        val bytes = android.util.AtomicFile(file).openRead().use { it.readBytes() }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return JSONObject(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
    }
    @Synchronized private fun write(data: JSONObject) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val atomic = android.util.AtomicFile(file)
        val out = atomic.startWrite()
        try { out.write(cipher.iv + cipher.doFinal(data.toString().toByteArray(Charsets.UTF_8))); atomic.finishWrite(out) }
        catch (e: Exception) { atomic.failWrite(out); throw e }
    }
    fun signedIn() = runCatching { read().has("access_token") }.getOrDefault(false)
    fun accountLabel() = runCatching { read().optString("email", "已授权") }.getOrDefault("")
    fun reportFailure(error: Exception) { status = error.message ?: "登录未完成，请检查手机网络后重试。" }
    suspend fun signOut() = withContext(Dispatchers.IO) { mutex.withLock {
        val host = read().optString("host_id", "urn:uuid:${UUID.randomUUID()}")
        write(JSONObject().put("host_id", host)); status = "已退出登录（如需撤销授权，请在 ChatGPT 中管理此应用）。"
    } }
    private fun token(form: FormBody): JSONObject {
        http.newCall(Request.Builder().url("https://auth.openai.com/api/accounts/oauth/token").post(form).build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            check(response.isSuccessful) { "登录凭证请求失败 HTTP ${response.code}，请重新登录。" }
            check(response.header("Content-Type").orEmpty().contains("json")) { "登录服务返回网页，请检查手机网络后重新登录。" }
            return JSONObject(text)
        }
    }
    suspend fun accessToken(): String = withContext(Dispatchers.IO) { mutex.withLock {
        val saved = read()
        check(saved.has("access_token")) { "请先到模型服务中登录 ChatGPT。" }
        if (saved.optLong("expires_at") > System.currentTimeMillis() + 90_000) return@withLock saved.getString("access_token")
        val next = token(FormBody.Builder().add("grant_type", "refresh_token").add("client_id", saved.getString("client_id"))
            .add("refresh_token", saved.getString("refresh_token")).add("resource", RESOURCE).build())
        if (next.has("scope")) check(next.getString("scope").split(" ").contains("chatgpt.tokens.use.direct")) { "订阅权限已撤销，请重新登录。" }
        for (k in next.keys()) saved.put(k, next.get(k))
        saved.put("expires_at", System.currentTimeMillis() + next.getLong("expires_in") * 1000)
        write(saved); saved.getString("access_token")
    } }
    /** Listener is bound before the browser opens; no exported callback activity or custom URI scheme. */
    suspend fun prepareLogin(): LoginAttempt = withContext(Dispatchers.IO) {
        val saved = read()
        if (!saved.has("host_id")) { saved.put("host_id", "urn:uuid:${UUID.randomUUID()}"); write(saved) }
        val socket = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 600_000 }
        val redirect = "http://127.0.0.1:${socket.localPort}/auth/callback"
        val verifier = random(); val state = random(); val nonce = random()
        val clientId = saved.optString("client_id", "dynamic_agent_client")
        val url = "https://auth.openai.com/api/accounts/authorize".toHttpUrl().newBuilder()
            .addQueryParameter("client_id", clientId).addQueryParameter("ext_agent_host_id", saved.getString("host_id"))
            .addQueryParameter("response_type", "code").addQueryParameter("redirect_uri", redirect)
            .addQueryParameter("scope", "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct")
            .addQueryParameter("resource", RESOURCE).addQueryParameter("state", state).addQueryParameter("nonce", nonce)
            .addQueryParameter("code_challenge_method", "S256")
            .addQueryParameter("code_challenge", b64(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray())))
        if (clientId == "dynamic_agent_client") url.addQueryParameter("agent_name_hint", "AgentPaw Direct Test")
        else if (saved.has("id_token")) url.addQueryParameter("id_token_hint", saved.getString("id_token"))
        status = "等待浏览器授权，完成后返回此页。"
        LoginAttempt(url.build().toString(), socket, redirect, verifier, state, nonce, saved)
    }
    inner class LoginAttempt(val url: String, private val server: ServerSocket, private val redirect: String,
        private val verifier: String, private val state: String, private val nonce: String, private val saved: JSONObject) {
        fun cancel() { runCatching { server.close() } }
        suspend fun awaitCallback() = withContext(Dispatchers.IO) {
            try { server.use {
                while (true) {
                    val client = server.accept()
                    client.use {
                        client.soTimeout = 10_000
                        val line = client.getInputStream().bufferedReader().readLine().orEmpty()
                        val uri = ("http://127.0.0.1" + line.split(" ").getOrElse(1) { "/" }).toHttpUrl()
                        if (uri.encodedPath != "/auth/callback" || uri.queryParameter("state") != state) {
                            client.getOutputStream().write("HTTP/1.1 400 Bad Request\r\nConnection: close\r\nContent-Length: 0\r\n\r\n".toByteArray()); return@use
                        }
                        try {
                            check(uri.queryParameter("error") == null) { "授权未完成，请重新登录。" }
                            val id = uri.queryParameter("client_id") ?: saved.optString("client_id")
                            check(id.isNotBlank() && id != "dynamic_agent_client") { "未收到有效的客户端注册信息。" }
                            check(!saved.has("client_id") || saved.getString("client_id") == id) { "授权账号注册不匹配。" }
                            val result = token(FormBody.Builder().add("grant_type", "authorization_code").add("client_id", id)
                                .add("code", requireNotNull(uri.queryParameter("code"))).add("code_verifier", verifier)
                                .add("redirect_uri", redirect).add("resource", RESOURCE).build())
                            val identity = verifyIdToken(result.getString("id_token"), id, nonce)
                            check(!saved.has("subject") || saved.getString("subject") == identity.getString("sub")) { "返回的账号与原账号不一致。" }
                            check(result.getString("scope").split(" ").contains("chatgpt.tokens.use.direct")) { "未授予 ChatGPT 套餐使用权限。" }
                            result.put("client_id", id).put("host_id", saved.getString("host_id")).put("subject", identity.getString("sub"))
                                .put("email", identity.optString("email", "已授权")).put("expires_at", System.currentTimeMillis() + result.getLong("expires_in") * 1000)
                            mutex.withLock { write(result) }
                            status = "授权成功。返回 AgentPaw，选择模型并保存设置。"
                            browserResult(client, true); return@withContext
                        } catch (e: Exception) { browserResult(client, false); throw e }
                    }
                }
            } } catch (e: Exception) { status = e.message ?: "授权失败，请重试。"; throw e }
        }
        private fun browserResult(client: java.net.Socket, success: Boolean) {
            val body = "<!doctype html><meta charset=utf-8><h2>${if (success) "授权成功，请返回 AgentPaw。" else "授权未完成，请返回 AgentPaw 查看提示。"}</h2>".toByteArray()
            client.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nCache-Control: no-store\r\nConnection: close\r\nContent-Length: ${body.size}\r\n\r\n").toByteArray() + body)
        }
    }
    private fun verifyIdToken(jwt: String, audience: String, nonce: String): JSONObject {
        val parts = jwt.split('.'); check(parts.size == 3) { "身份令牌格式错误。" }
        val header = JSONObject(String(decode(parts[0]), Charsets.UTF_8))
        check(header.getString("alg") == "RS256") { "不支持的身份签名算法。" }
        val jwks = http.newCall(Request.Builder().url("https://auth.openai.com/.well-known/jwks.json").build()).execute().use {
            check(it.isSuccessful); JSONObject(it.body!!.string()).getJSONArray("keys")
        }
        val jwk = (0 until jwks.length()).map { jwks.getJSONObject(it) }.firstOrNull { it.optString("kid") == header.getString("kid") && it.optString("kty") == "RSA" }
            ?: error("无法验证 OpenAI 身份签名。")
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(BigInteger(1, decode(jwk.getString("n"))), BigInteger(1, decode(jwk.getString("e")))))
        val valid = Signature.getInstance("SHA256withRSA").run { initVerify(publicKey); update("${parts[0]}.${parts[1]}".toByteArray(Charsets.US_ASCII)); verify(decode(parts[2])) }
        check(valid) { "身份令牌签名无效。" }
        val payload = JSONObject(String(decode(parts[1]), Charsets.UTF_8)); val now = System.currentTimeMillis() / 1000
        check(payload.getString("iss") == "https://auth.openai.com" && payload.getString("nonce") == nonce && payload.getLong("exp") > now) { "身份令牌已过期或不匹配。" }
        val aud = payload.get("aud"); check(aud == audience || (aud is org.json.JSONArray && (0 until aud.length()).any { aud.getString(it) == audience })) { "身份令牌受众不匹配。" }
        if (payload.has("nbf")) check(payload.getLong("nbf") <= now + 30)
        check(payload.getString("sub").isNotBlank()); return payload
    }
    companion object {
        private const val ALIAS = "agentpaw_chatgpt_plan_v1"
        const val RESOURCE = "https://api.openai.com/v1"
        @Volatile private var instance: ChatGptAuth? = null
        fun get(context: Context): ChatGptAuth = instance ?: synchronized(this) { instance ?: ChatGptAuth(context.applicationContext).also { instance = it } }
        private fun random(): String = b64(ByteArray(48).also { SecureRandom().nextBytes(it) })
        private fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        private fun decode(s: String): ByteArray = Base64.decode(s, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }
}
