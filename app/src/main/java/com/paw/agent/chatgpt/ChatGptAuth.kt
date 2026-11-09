package com.paw.agent.chatgpt

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.math.BigInteger
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.util.UUID
import java.util.concurrent.TimeUnit

enum class ChatGptAuthStatus { IDLE, WAITING, SIGNED_IN, CANCELLED, FAILED, SIGNED_OUT, REMOTE_UNCONFIRMED }

data class ChatGptAuthState(
    val signedIn: Boolean = false,
    val accountLabel: String = "",
    val busy: Boolean = false,
    val status: ChatGptAuthStatus = ChatGptAuthStatus.IDLE,
)

internal data class ChatGptEndpoints(
    val authorize: String = "https://auth.openai.com/api/accounts/authorize",
    val token: String = "https://auth.openai.com/api/accounts/oauth/token",
    val jwks: String = "https://auth.openai.com/.well-known/jwks.json",
    val discovery: String = "https://auth.openai.com/.well-known/openid-configuration",
    val issuer: String = "https://auth.openai.com",
)

/** One owner for login, token rotation and account changes. Network IO never holds [gate]. */
class ChatGptAuth internal constructor(
    private val storage: ChatGptCredentialStorage,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
        .callTimeout(30, TimeUnit.SECONDS).build(),
    private val endpoints: ChatGptEndpoints = ChatGptEndpoints(),
) {
    private val gate = Any()
    private val refreshMutex = Mutex()
    private var generation = 0L
    private var attempt: LoginAttempt? = null
    private var signingOut = false
    private var credentialsBlocked = false
    private val calls = mutableMapOf<Call, Job>()
    private val mutableState = MutableStateFlow(ChatGptAuthState())
    val state = mutableState.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) { synchronized(gate) { publish(storage.read()) } }

    private fun publish(saved: JSONObject, status: ChatGptAuthStatus = state.value.status) {
        mutableState.value = ChatGptAuthState(
            signedIn = !signingOut && !credentialsBlocked && saved.optString("access_token").isNotBlank(),
            accountLabel = saved.optString("email"), busy = attempt != null || signingOut, status = status,
        )
    }

    /** Caller holds gate. Epoch validation also rejects callbacks already past socket.accept(). */
    private fun invalidate() {
        generation++
        attempt?.closeResources()
        attempt = null
        val obsolete = calls.toMap()
        calls.clear()
        obsolete.forEach { (call, owner) ->
            call.cancel()
            owner.cancel(CancellationException("Account operation superseded"))
        }
    }

    fun cancelLogin() = synchronized(gate) {
        if (attempt != null) {
            invalidate()
            mutableState.value = state.value.copy(busy = false, status = ChatGptAuthStatus.CANCELLED)
        }
    }

    fun reportFailure(owner: LoginAttempt? = null) = synchronized(gate) {
        // Exception messages may contain OAuth URLs, codes or credentials.
        if (owner == null || attempt === owner) {
            mutableState.value = state.value.copy(status = ChatGptAuthStatus.FAILED)
        }
    }

    private suspend fun <T> consume(epoch: Long, call: Call, block: suspend (Response) -> T): T {
        val owner = currentCoroutineContext().job
        synchronized(gate) {
            if (generation != epoch || signingOut) {
                call.cancel()
                throw CancellationException("Account operation superseded")
            }
            calls[call] = owner
        }
        return try { call.consumeCancellable(block) } finally { synchronized(gate) { calls.remove(call) } }
    }

    private suspend fun token(epoch: Long, form: FormBody, timeoutMillis: Int = 30_000): JSONObject = consume(epoch,
        http.newBuilder().callTimeout(timeoutMillis.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMillis.toLong(), TimeUnit.MILLISECONDS)
            .writeTimeout(timeoutMillis.toLong(), TimeUnit.MILLISECONDS).build()
            .newCall(Request.Builder().url(endpoints.token).post(form).build()),
    ) { response ->
        check(response.isSuccessful) { "Token request failed: HTTP ${response.code}" }
        check(response.header("Content-Type").orEmpty().contains("json", ignoreCase = true))
        JSONObject(response.body?.string().orEmpty())
    }

    private data class Credential(val token: String, val generation: Long)

    private suspend fun credentials(timeoutMillis: Int): Credential = withContext(Dispatchers.IO) { refreshMutex.withLock {
        val (saved, epoch) = synchronized(gate) {
            check(!signingOut && !credentialsBlocked) { "ChatGPT sign-in required" }
            storage.read() to generation
        }
        check(saved.optString("access_token").isNotBlank()) { "ChatGPT sign-in required" }
        if (saved.optLong("expires_at") > System.currentTimeMillis() + 90_000) {
            return@withLock Credential(saved.getString("access_token"), epoch)
        }
        val next = token(epoch, FormBody.Builder().add("grant_type", "refresh_token")
            .add("client_id", saved.getString("client_id")).add("refresh_token", saved.getString("refresh_token"))
            .add("resource", RESOURCE).build(), timeoutMillis)
        validateToken(next, requireScope = false)
        listOf("access_token", "refresh_token", "scope", "token_type").forEach { key ->
            if (next.has(key)) saved.put(key, next.get(key))
        }
        saved.put("expires_at", expiry(next))
        val context = currentCoroutineContext()
        synchronized(gate) {
            context.ensureActive()
            if (epoch != generation || signingOut) throw CancellationException("Refresh superseded")
            storage.write(saved)
            publish(saved)
        }
        Credential(saved.getString("access_token"), epoch)
    } }

    internal suspend fun <T> authorizedResponse(
        client: OkHttpClient, request: (String) -> Request, block: suspend (Response) -> T,
    ): T {
        val credential = credentials(client.callTimeoutMillis.takeIf { it > 0 } ?: 30_000)
        return consume(credential.generation, client.newCall(request(credential.token)), block)
    }

    suspend fun signOut() = withContext(Dispatchers.IO) {
        val (saved, epoch) = synchronized(gate) {
            check(!signingOut) { "Sign-out already in progress" }
            val data = storage.read()
            invalidate()
            signingOut = true
            credentialsBlocked = true
            publish(data)
            data to generation
        }
        var revoked = saved.optString("access_token").isBlank() && saved.optString("refresh_token").isBlank()
        try {
            if (saved.optString("refresh_token").isNotBlank()) revoked = revoke(saved)
        } finally {
            // Cancellation or a failed server must never leave usable local tokens behind.
            withContext(NonCancellable + Dispatchers.IO) {
                synchronized(gate) {
                    if (epoch == generation) {
                        val registration = JSONObject()
                        listOf("host_id", "client_id", "subject", "email").forEach { key ->
                            if (saved.has(key)) registration.put(key, saved.get(key))
                        }
                        try {
                            storage.write(registration)
                            mutableState.value = ChatGptAuthState(accountLabel = registration.optString("email"),
                                status = if (revoked) ChatGptAuthStatus.SIGNED_OUT else ChatGptAuthStatus.REMOTE_UNCONFIRMED)
                        } catch (e: Exception) {
                            mutableState.value = ChatGptAuthState(status = ChatGptAuthStatus.FAILED)
                            throw e
                        } finally { signingOut = false }
                    }
                }
            }
        }
    }

    private suspend fun revoke(saved: JSONObject): Boolean {
        val client = http.newBuilder().callTimeout(10, TimeUnit.SECONDS).build()
        repeat(2) { retry ->
            try {
                val url = client.newCall(Request.Builder().url(endpoints.discovery).build()).consumeCancellable {
                    check(it.isSuccessful)
                    JSONObject(it.body?.string().orEmpty()).getString("revocation_endpoint").toHttpUrl()
                }
                val issuer = endpoints.issuer.toHttpUrl()
                // Discovery cannot redirect a refresh token to another origin.
                check(url.scheme == issuer.scheme && url.host == issuer.host && url.port == issuer.port)
                val code = client.newCall(Request.Builder().url(url).post(FormBody.Builder()
                    .add("token", saved.getString("refresh_token")).add("token_type_hint", "refresh_token")
                    .add("client_id", saved.getString("client_id")).build()).build()).consumeCancellable { it.code }
                if (code == 200) return true
                if (code < 500) return false
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Never log credentials or server messages. */ }
            if (retry == 0) delay(300)
        }
        return false
    }

    /** Installs the listener and attempt identity before the system browser opens. */
    suspend fun prepareLogin(): LoginAttempt {
        val owner = currentCoroutineContext().job
        return withContext(Dispatchers.IO) {
            currentCoroutineContext().ensureActive()
            synchronized(gate) {
                check(!signingOut) { "Signing out" }
                val saved = storage.read()
                if (!saved.has("host_id")) {
                    saved.put("host_id", "urn:uuid:${UUID.randomUUID()}")
                    storage.write(saved)
                }
                invalidate()
                val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 600_000 }
                try {
                    LoginAttempt(server, saved, generation).also { next ->
                        attempt = next
                        next.ownerHandle = owner.invokeOnCompletion { cause -> if (cause != null) next.cancel() }
                        publish(saved, ChatGptAuthStatus.WAITING)
                    }
                } catch (e: Exception) { server.close(); throw e }
            }
        }
    }

    inner class LoginAttempt internal constructor(
        private val server: ServerSocket, private val saved: JSONObject, private val epoch: Long,
    ) {
        private val verifier = random()
        private val state = random()
        private val nonce = random()
        private val redirect = "http://127.0.0.1:${server.localPort}/auth/callback"
        @Volatile private var accepted: Socket? = null
        internal var ownerHandle: DisposableHandle? = null
        internal val isCurrent: Boolean get() = synchronized(gate) { attempt === this && epoch == generation }
        val url: String = endpoints.authorize.toHttpUrl().newBuilder().apply {
            val clientId = saved.optString("client_id").ifBlank { "dynamic_agent_client" }
            addQueryParameter("client_id", clientId)
            addQueryParameter("ext_agent_host_id", saved.getString("host_id"))
            addQueryParameter("response_type", "code")
            addQueryParameter("redirect_uri", redirect)
            addQueryParameter("scope", "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct")
            addQueryParameter("resource", RESOURCE)
            addQueryParameter("state", state)
            addQueryParameter("nonce", nonce)
            addQueryParameter("code_challenge_method", "S256")
            addQueryParameter("code_challenge", b64(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray())))
            if (clientId == "dynamic_agent_client") addQueryParameter("agent_name_hint", "AgentPaw")
            else if (saved.has("id_token")) addQueryParameter("id_token_hint", saved.getString("id_token"))
        }.build().toString()

        internal fun closeResources() {
            runCatching { server.close() }
            runCatching { accepted?.close() }
            ownerHandle?.dispose()
        }

        fun cancel() = synchronized(gate) {
            if (attempt === this) cancelLogin() else closeResources()
        }

        suspend fun awaitCallback() {
            try { awaitCallbackOnIo() } finally { cancel() }
        }

        private suspend fun awaitCallbackOnIo() = withContext(Dispatchers.IO) { coroutineScope {
            val context = currentCoroutineContext()
            val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { closeResources() }
            }
            try {
                while (true) {
                    context.ensureActive()
                    val client = server.accept()
                    synchronized(gate) {
                        if (epoch != generation || attempt !== this@LoginAttempt) {
                            client.close()
                            throw CancellationException("Login superseded")
                        }
                        accepted = client
                    }
                    client.use {
                        context.ensureActive()
                        client.soTimeout = 10_000
                        val line = client.getInputStream().bufferedReader().readLine().orEmpty()
                        val uri = ("http://127.0.0.1" + line.split(" ").getOrElse(1) { "/" }).toHttpUrl()
                        if (uri.encodedPath != "/auth/callback" || uri.queryParameter("state") != state) {
                            client.getOutputStream().write("HTTP/1.1 400 Bad Request\r\nConnection: close\r\nContent-Length: 0\r\n\r\n".toByteArray())
                            return@use
                        }
                        try {
                            check(uri.queryParameter("error") == null) { "Authorization declined" }
                            val id = uri.queryParameter("client_id") ?: saved.optString("client_id")
                            check(id.isNotBlank() && id != "dynamic_agent_client")
                            check(!saved.has("client_id") || saved.getString("client_id") == id)
                            val result = token(epoch, FormBody.Builder().add("grant_type", "authorization_code")
                                .add("client_id", id).add("code", requireNotNull(uri.queryParameter("code")))
                                .add("code_verifier", verifier).add("redirect_uri", redirect).add("resource", RESOURCE).build())
                            val identity = verifyIdToken(epoch, result.getString("id_token"), id, nonce)
                            check(!saved.has("subject") || saved.getString("subject") == identity.getString("sub"))
                            validateToken(result, requireScope = true)
                            result.put("client_id", id).put("host_id", saved.getString("host_id"))
                                .put("subject", identity.getString("sub")).put("email", identity.optString("email"))
                                .put("expires_at", expiry(result))
                            synchronized(gate) {
                                context.ensureActive()
                                if (epoch != generation || attempt !== this@LoginAttempt || signingOut) {
                                    throw CancellationException("Login superseded")
                                }
                                storage.write(result)
                                credentialsBlocked = false
                                attempt = null
                                publish(result, ChatGptAuthStatus.SIGNED_IN)
                            }
                            // A disconnected browser cannot invalidate a successfully committed login.
                            runCatching { browserResult(client, true) }
                            return@coroutineScope
                        } catch (e: Exception) {
                            runCatching { browserResult(client, false) }
                            throw e
                        }
                    }
                }
            } catch (e: Exception) {
                synchronized(gate) {
                    if (attempt === this@LoginAttempt) {
                        attempt = null
                        mutableState.value = this@ChatGptAuth.state.value.copy(busy = false, status =
                            if (e is CancellationException) ChatGptAuthStatus.CANCELLED else ChatGptAuthStatus.FAILED)
                    }
                }
                context.ensureActive()
                throw e
            } finally {
                cancellation.cancel()
                closeResources()
            }
        } }

        private fun browserResult(client: Socket, success: Boolean) {
            val body = "<!doctype html><meta charset=utf-8><h2>${if (success) "Signed in. Return to AgentPaw." else "Sign-in failed. Return to AgentPaw."}</h2>".toByteArray()
            client.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nCache-Control: no-store\r\nConnection: close\r\nContent-Length: ${body.size}\r\n\r\n").toByteArray() + body)
        }
    }

    private suspend fun verifyIdToken(epoch: Long, jwt: String, audience: String, nonce: String): JSONObject {
        val parts = jwt.split('.')
        check(parts.size == 3) { "Invalid ID token" }
        val header = JSONObject(String(decode(parts[0]), Charsets.UTF_8))
        check(header.getString("alg") == "RS256")
        val jwks = consume(epoch, http.newCall(Request.Builder().url(endpoints.jwks).build())) {
            check(it.isSuccessful)
            JSONObject(it.body!!.string()).getJSONArray("keys")
        }
        val jwk = (0 until jwks.length()).map { jwks.getJSONObject(it) }.firstOrNull {
            it.optString("kid") == header.getString("kid") && it.optString("kty") == "RSA"
        } ?: error("Unknown signing key")
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(
            BigInteger(1, decode(jwk.getString("n"))), BigInteger(1, decode(jwk.getString("e"))),
        ))
        check(Signature.getInstance("SHA256withRSA").run {
            initVerify(publicKey)
            update("${parts[0]}.${parts[1]}".toByteArray(Charsets.US_ASCII))
            verify(decode(parts[2]))
        }) { "Invalid ID signature" }
        val payload = JSONObject(String(decode(parts[1]), Charsets.UTF_8))
        val now = System.currentTimeMillis() / 1000
        check(payload.getString("iss") == endpoints.issuer && payload.getString("nonce") == nonce && payload.getLong("exp") > now)
        val aud = payload.get("aud")
        check(aud == audience || (aud is org.json.JSONArray && (0 until aud.length()).any { aud.getString(it) == audience }))
        if (payload.has("nbf")) check(payload.getLong("nbf") <= now + 30)
        check(payload.getString("sub").isNotBlank())
        return payload
    }

    private fun validateToken(token: JSONObject, requireScope: Boolean) {
        check(token.opt("access_token") is String && token.getString("access_token").isNotBlank())
        check(token.optString("token_type", "Bearer").equals("Bearer", ignoreCase = true))
        if (requireScope || token.has("refresh_token")) {
            check(token.opt("refresh_token") is String && token.getString("refresh_token").isNotBlank())
        }
        if (requireScope || token.has("scope")) check(token.getString("scope").split(" ").contains("chatgpt.tokens.use.direct"))
    }

    private fun expiry(token: JSONObject): Long {
        val seconds = token.getLong("expires_in")
        check(seconds in 1..31_536_000L)
        return System.currentTimeMillis() + seconds * 1000
    }

    companion object {
        const val RESOURCE = "https://api.openai.com/v1"
        @Volatile private var instance: ChatGptAuth? = null
        fun get(context: Context): ChatGptAuth = instance ?: synchronized(this) {
            instance ?: ChatGptAuth(EncryptedChatGptCredentialStorage(context.applicationContext)).also { instance = it }
        }
        private fun random(): String = b64(ByteArray(48).also { SecureRandom().nextBytes(it) })
        private fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        private fun decode(s: String): ByteArray = Base64.decode(s, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }
}
