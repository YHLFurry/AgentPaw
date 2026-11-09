package com.paw.agent.chatgpt

import android.app.Application
import com.paw.agent.core.llm.*
import com.paw.agent.core.llm.dto.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.*
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.Closeable
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URLDecoder
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ChatGptIntegrationTest {
    @Test fun normalAgentRequestUsesSupportedProtocolAndReturnsToolCall() = runBlocking {
        Fixture(signedIn = true).use { f ->
            var request: JsonObject? = null
            f.route("/responses") { exchange ->
                assertEquals("Bearer access", exchange.requestHeaders.getFirst("Authorization"))
                request = Json.parseToJsonElement(exchange.requestBody.bufferedReader().readText()).jsonObject
                f.respond(exchange, """data: {"type":"response.output_text.delta","delta":"Checking"}

data: {"type":"response.completed","response":{"output":[{"type":"function_call","namespace":"agentpaw","call_id":"call_screen","name":"get_screen_state","arguments":"{}"}]}}

""", contentType = "text/event-stream")
            }
            val schema = ToolSpec(function = FunctionSpec("get_screen_state", "Read screen",
                buildJsonObject { put("type", "object"); putJsonObject("properties") {} }))
            val chunks = f.plan.complete(config, ChatCompletionRequest("model",
                listOf(ChatMessage("system", "Ask before payment"), ChatMessage("user", "Read screen")),
                tools = listOf(schema))).toList()
            val body = requireNotNull(request)
            assertEquals("developer", body["input"]!!.jsonArray[0].jsonObject["role"]!!.jsonPrimitive.content)
            assertEquals("namespace", body["tools"]!!.jsonArray.single().jsonObject["type"]!!.jsonPrimitive.content)
            assertEquals("call_screen", chunks.filterIsInstance<LlmChunk.ToolCalls>().single().calls.single().id)
            assertEquals("get_screen_state", chunks.filterIsInstance<LlmChunk.ToolCalls>().single().calls.single().name)
            assertEquals("tool_calls", chunks.filterIsInstance<LlmChunk.Done>().single().finishReason)
        }
    }

    @Test fun followsConfiguredTimeoutAndDoesNotRetryOrRedirectCredentials() {
        Fixture(signedIn = true).use { f ->
            val client = f.plan.clientFor(config.copy(timeoutSeconds = 12))
            assertEquals(12_000, client.callTimeoutMillis)
            assertEquals(12_000, client.readTimeoutMillis)
            assertFalse(client.retryOnConnectionFailure)
            assertFalse(client.followRedirects)
            assertFalse(client.followSslRedirects)
        }
    }

    @Test fun cancellingModelListInterruptsResponseBodyRead() = runBlocking {
        Fixture(signedIn = true).use { f ->
            val started = CompletableDeferred<Unit>()
            f.route("/models") { exchange ->
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.write("{".toByteArray())
                exchange.responseBody.flush()
                started.complete(Unit)
                f.release.await(8, TimeUnit.SECONDS)
                exchange.close()
            }
            val task = launch(Dispatchers.IO) { f.plan.listModels(config) }
            withTimeout(5_000) { started.await() }
            withTimeout(2_000) { task.cancelAndJoin() }
            assertTrue(task.isCancelled)
            assertTrue(f.cancelled.get())
        }
    }

    @Test fun cancellingRefreshInterruptsPendingHttpAndDoesNotPersistTokens() = runBlocking {
        Fixture(signedIn = true, expired = true).use { f ->
            val started = CompletableDeferred<Unit>()
            f.route("/token") { exchange ->
                started.complete(Unit)
                f.release.await(8, TimeUnit.SECONDS)
                exchange.close()
            }
            val task = launch(Dispatchers.IO) { f.plan.listModels(config) }
            withTimeout(5_000) { started.await() }
            withTimeout(2_000) { task.cancelAndJoin() }
            assertEquals("access", f.storage.read().getString("access_token"))
            assertTrue(task.isCancelled)
            assertTrue(f.cancelled.get())
        }
    }

    @Test fun concurrentRequestsShareOneRefreshAndPreserveRotatedRefreshToken() = runBlocking {
        Fixture(signedIn = true, expired = true).use { f ->
            val count = AtomicInteger()
            f.route("/token") { exchange ->
                count.incrementAndGet()
                f.respond(exchange, """{"access_token":"new_access","refresh_token":"new_refresh","expires_in":3600,"client_id":"foreign","subject":"foreign","host_id":"foreign"}""")
            }
            f.route("/models") { exchange ->
                assertEquals("Bearer new_access", exchange.requestHeaders.getFirst("Authorization"))
                f.respond(exchange, """{"models":[{"slug":"visible","visibility":"list"},{"slug":"hidden","visibility":"hide"}]}""")
            }
            val results = List(4) { async(Dispatchers.IO) { f.plan.listModels(config).getOrThrow() } }.awaitAll()
            assertTrue(results.all { it == listOf("visible") })
            assertEquals(1, count.get())
            assertEquals("new_refresh", f.storage.read().getString("refresh_token"))
            assertEquals("client", f.storage.read().getString("client_id"))
            assertEquals("subject_saved", f.storage.read().getString("subject"))
            assertEquals("urn:uuid:stable-host", f.storage.read().getString("host_id"))
        }
    }

    @Test fun cancelledLoopbackWaitClosesListenerPromptly() = runBlocking {
        Fixture().use { f ->
            val attempt = f.auth.prepareLogin()
            val redirect = attempt.url.toHttpUrl().queryParameter("redirect_uri")!!.toHttpUrl()
            val started = CompletableDeferred<Unit>()
            val waiting = launch(Dispatchers.IO) { started.complete(Unit); attempt.awaitCallback() }
            withTimeout(2_000) { started.await() }
            withTimeout(2_000) { waiting.cancelAndJoin() }
            assertTrue(runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1", redirect.port), 500) } }.isFailure)
            assertFalse(f.auth.state.value.busy)
            assertFalse(f.storage.read().has("access_token"))
        }
    }

    @Test fun lateOldCallbackCannotOverwriteNewAccount() = runBlocking {
        Fixture().use { f ->
            val started = CompletableDeferred<Unit>()
            f.route("/token") { exchange ->
                val form = f.form(exchange)
                if (form["code"] == "old") {
                    started.complete(Unit)
                    f.release.await(8, TimeUnit.SECONDS)
                    runCatching { f.respond(exchange, f.tokens("old")) }
                } else f.respond(exchange, f.tokens(form.getValue("code")))
            }
            val old = f.auth.prepareLogin()
            f.register("old", old)
            val oldWait = async(Dispatchers.IO) { runCatching { old.awaitCallback() } }
            val oldBrowser = async(Dispatchers.IO) { runCatching { f.callback("old", old) } }
            withTimeout(5_000) { started.await() }
            val next = f.auth.prepareLogin()
            f.register("new", next)
            val nextWait = async(Dispatchers.IO) { next.awaitCallback() }
            withContext(Dispatchers.IO) { f.callback("new", next) }
            withTimeout(5_000) { nextWait.await() }
            f.release.countDown()
            withTimeout(2_000) { oldWait.await(); oldBrowser.await() }
            assertEquals("new_access", f.storage.read().getString("access_token"))
            assertEquals("subject_new", f.storage.read().getString("subject"))
            assertEquals(ChatGptAuthStatus.SIGNED_IN, f.auth.state.value.status)
        }
    }

    @Test fun signOutWhileLoginIsInFlightRejectsLateCallback() = runBlocking {
        Fixture().use { f ->
            val started = CompletableDeferred<Unit>()
            f.route("/token") { exchange ->
                f.form(exchange)
                started.complete(Unit)
                f.release.await(8, TimeUnit.SECONDS)
                runCatching { f.respond(exchange, f.tokens("late")) }
            }
            val attempt = f.auth.prepareLogin()
            f.register("late", attempt)
            val waiting = async(Dispatchers.IO) { runCatching { attempt.awaitCallback() } }
            val browser = async(Dispatchers.IO) { runCatching { f.callback("late", attempt) } }
            withTimeout(5_000) { started.await() }
            f.auth.signOut()
            f.release.countDown()
            withTimeout(2_000) { waiting.await(); browser.await() }
            assertFalse(f.storage.read().has("access_token"))
            assertFalse(f.auth.state.value.signedIn)
            assertFalse(f.auth.state.value.busy)
        }
    }

    @Test fun logoutRevokesSessionAndReusesRegistrationWithoutTokens() = runBlocking {
        Fixture(signedIn = true).use { f ->
            var revoked = false
            f.route("/revoke") { exchange ->
                val form = f.form(exchange)
                assertEquals("refresh", form["token"])
                assertEquals("refresh_token", form["token_type_hint"])
                assertEquals("client", form["client_id"])
                revoked = true
                f.respond(exchange, "")
            }
            f.auth.signOut()
            assertTrue(revoked)
            assertEquals(ChatGptAuthStatus.SIGNED_OUT, f.auth.state.value.status)
            val saved = f.storage.read()
            assertEquals(setOf("host_id", "client_id", "subject", "email"), saved.keys().asSequence().toSet())
            val attempt = f.auth.prepareLogin()
            val url = attempt.url.toHttpUrl()
            assertEquals("client", url.queryParameter("client_id"))
            assertEquals("urn:uuid:stable-host", url.queryParameter("ext_agent_host_id"))
            assertNull(url.queryParameter("agent_name_hint"))
            assertNull(url.queryParameter("id_token_hint"))
            attempt.cancel()
        }
    }

    @Test fun logoutFailureRetriesAndClearlyReportsUnconfirmedRemoteRevocation() = runBlocking {
        Fixture(signedIn = true).use { f ->
            val count = AtomicInteger()
            f.route("/revoke") { exchange -> count.incrementAndGet(); f.respond(exchange, "", code = 503) }
            f.auth.signOut()
            assertEquals(2, count.get())
            assertFalse(f.storage.read().has("refresh_token"))
            assertEquals(ChatGptAuthStatus.REMOTE_UNCONFIRMED, f.auth.state.value.status)
        }
    }

    @Test fun cancellingLogoutStillClearsLocalTokens() = runBlocking {
        Fixture(signedIn = true).use { f ->
            val started = CompletableDeferred<Unit>()
            f.route("/revoke") { exchange ->
                started.complete(Unit)
                f.release.await(8, TimeUnit.SECONDS)
                exchange.close()
            }
            val task = launch(Dispatchers.IO) { f.auth.signOut() }
            withTimeout(5_000) { started.await() }
            withTimeout(2_000) { task.cancelAndJoin() }
            assertFalse(f.storage.read().has("access_token"))
            assertFalse(f.storage.read().has("refresh_token"))
            assertEquals(ChatGptAuthStatus.REMOTE_UNCONFIRMED, f.auth.state.value.status)
        }
    }

    @Test fun invalidNonceNeverReplacesCredentials() = runBlocking {
        Fixture().use { f ->
            val attempt = f.auth.prepareLogin()
            f.register("bad", attempt, nonce = "wrong_nonce")
            val task = async(Dispatchers.IO) { runCatching { attempt.awaitCallback() } }
            withContext(Dispatchers.IO) { f.callback("bad", attempt) }
            assertTrue(withTimeout(5_000) { task.await() }.isFailure)
            assertFalse(f.storage.read().has("access_token"))
            assertEquals(ChatGptAuthStatus.FAILED, f.auth.state.value.status)
        }
    }

    @Test fun prematureStreamEndDoesNotReportSuccess() = runBlocking {
        Fixture(signedIn = true).use { f ->
            f.route("/responses") { exchange ->
                f.respond(exchange, """data: {"type":"response.output_text.delta","delta":"partial"}

""", contentType = "text/event-stream")
            }
            val result = f.plan.testConnection(config)
            assertTrue(result.isFailure)
        }
    }

    private class MemoryStorage(initial: JSONObject) : ChatGptCredentialStorage {
        private var data = initial.toString()
        @Synchronized override fun read() = JSONObject(data)
        @Synchronized override fun write(data: JSONObject) { this.data = data.toString() }
    }

    private class Fixture(signedIn: Boolean = false, expired: Boolean = false) : Closeable {
        private val executor = Executors.newCachedThreadPool { runnable -> Thread(runnable).apply { isDaemon = true } }
        private val server = LocalHttpServer()
        val base = "http://127.0.0.1:${server.address.port}"
        val release = CountDownLatch(1)
        val cancelled = AtomicBoolean()
        val routes = ConcurrentHashMap<String, (HttpExchange) -> Unit>()
        private val attempts = ConcurrentHashMap<String, String>()
        private val key = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val storage = MemoryStorage(if (!signedIn) JSONObject() else JSONObject()
            .put("host_id", "urn:uuid:stable-host").put("client_id", "client").put("subject", "subject_saved")
            .put("email", "saved@example.test").put("access_token", "access").put("refresh_token", "refresh")
            .put("id_token", "id").put("expires_at", if (expired) 1 else System.currentTimeMillis() + 3_600_000))
        val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .callTimeout(5, TimeUnit.SECONDS).eventListener(object : EventListener() {
                override fun canceled(call: Call) { cancelled.set(true) }
            }).build()
        val auth = ChatGptAuth(storage, http, ChatGptEndpoints(
            authorize = "$base/authorize", token = "$base/token", jwks = "$base/jwks",
            discovery = "$base/discovery", issuer = base,
        ))
        val plan = ChatGptPlanClient(auth, object : LlmClient {
            override fun complete(config: LlmConfig, request: ChatCompletionRequest): Flow<LlmChunk> = error("Unexpected fallback")
            override suspend fun testConnection(config: LlmConfig): Result<Unit> = error("Unexpected fallback")
        }, http, base)

        init {
            route("/token") { exchange -> respond(exchange, tokens(form(exchange).getValue("code"))) }
            route("/discovery") { exchange -> respond(exchange, """{"revocation_endpoint":"$base/revoke"}""") }
            route("/revoke") { exchange -> respond(exchange, "") }
            route("/jwks") { exchange ->
                val public = key.public as RSAPublicKey
                respond(exchange, JSONObject().put("keys", org.json.JSONArray().put(JSONObject()
                    .put("kid", "test").put("kty", "RSA").put("n", b64(public.modulus.toByteArray()))
                    .put("e", b64(public.publicExponent.toByteArray())))).toString())
            }
            server.executor = executor
            server.createContext("/") { exchange ->
                try { routes[exchange.requestURI.path]?.invoke(exchange) ?: respond(exchange, "", 404) }
                catch (_: Exception) { exchange.close() }
            }
            server.start()
        }

        fun route(path: String, handler: (HttpExchange) -> Unit) { routes[path] = handler }
        fun register(code: String, attempt: ChatGptAuth.LoginAttempt, nonce: String? = null) {
            attempts[code] = nonce ?: attempt.url.toHttpUrl().queryParameter("nonce")!!
        }
        fun tokens(code: String): String = JSONObject().put("access_token", "${code}_access")
            .put("refresh_token", "${code}_refresh").put("expires_in", 3600)
            .put("scope", "chatgpt.tokens.use.direct").put("id_token", idToken(code)).toString()

        private fun idToken(code: String): String {
            val header = b64("""{"alg":"RS256","kid":"test"}""".toByteArray())
            val payload = b64(JSONObject().put("iss", base).put("aud", "client").put("nonce", attempts.getValue(code))
                .put("exp", System.currentTimeMillis() / 1000 + 3600).put("sub", "subject_$code")
                .put("email", "$code@example.test").toString().toByteArray())
            val signature = Signature.getInstance("SHA256withRSA").run {
                initSign(key.private); update("$header.$payload".toByteArray()); b64(sign())
            }
            return "$header.$payload.$signature"
        }

        fun callback(code: String, attempt: ChatGptAuth.LoginAttempt): String {
            val authorize = attempt.url.toHttpUrl()
            val url = authorize.queryParameter("redirect_uri")!!.toHttpUrl().newBuilder()
                .addQueryParameter("state", authorize.queryParameter("state")).addQueryParameter("client_id", "client")
                .addQueryParameter("code", code).build()
            return http.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { it.body!!.string() }
        }
        fun form(exchange: HttpExchange): Map<String, String> = exchange.requestBody.bufferedReader().readText()
            .split("&").associate { part -> part.split("=", limit = 2).let {
                URLDecoder.decode(it[0], "UTF-8") to URLDecoder.decode(it.getOrElse(1) { "" }, "UTF-8")
            } }
        fun respond(exchange: HttpExchange, body: String, code: Int = 200, contentType: String = "application/json") {
            val bytes = body.toByteArray()
            exchange.responseHeaders.add("Content-Type", contentType)
            exchange.sendResponseHeaders(code, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            exchange.responseBody.use { if (bytes.isNotEmpty()) it.write(bytes) }
            exchange.close()
        }
        override fun close() {
            release.countDown()
            auth.cancelLogin()
            server.stop(0)
            executor.shutdownNow()
            http.dispatcher.executorService.shutdownNow()
            http.connectionPool.evictAll()
        }
        private fun b64(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private companion object {
        val config = LlmConfig(provider = LlmProvider.CHATGPT_PLAN, model = "model", timeoutSeconds = 10)
    }
}
