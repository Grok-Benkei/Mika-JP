package com.wangzan101.mika.core.oauth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CompletableFuture

class OpenRouterAuthTest {
    private lateinit var server: MockWebServer
    private val http = OkHttpClient()

    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() { server.shutdown() }

    private fun auth() = OpenRouterAuth(http, authUrl = server.url("/auth").toString(), keysUrl = server.url("/api/v1/auth/keys").toString())

    private fun json(code: Int = 200, body: String) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    /** RFC 7636 付録 B の例。 */
    @Test fun pkceChallengeIsS256OfTheVerifier() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", Pkce.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
        val v = Pkce.verifier()
        assertTrue(v.length in 43..128)
        assertTrue(v, v.all { it.isLetterOrDigit() || it in "-._~" })
        assertNotEquals(v, Pkce.verifier())
        assertNotEquals(Pkce.state(), Pkce.state())
    }

    @Test fun authorizeUrlCarriesCallbackChallengeLabelAndState() {
        val a = OpenRouterAuth(http)
        val attempt = a.newAttempt("http://localhost:4321/auth/callback")
        val url = a.authorizeUrl(attempt).toHttpUrl()
        assertEquals("openrouter.ai", url.host)
        assertEquals("/auth", url.encodedPath)
        assertEquals("http://localhost:4321/auth/callback", url.queryParameter("callback_url"))
        assertEquals(attempt.challenge, url.queryParameter("code_challenge"))
        assertEquals("S256", url.queryParameter("code_challenge_method"))
        assertEquals("Mika", url.queryParameter("key_label"))
        assertEquals(attempt.state, url.queryParameter("state"))
        // 検証文字列自体が認証ページのアドレスに表示されません
        assertFalse(url.toString().contains(attempt.verifier))
    }

    @Test fun callbackNeedsTheSameStateAndACode() {
        val a = auth()
        val attempt = a.newAttempt("http://localhost:1/auth/callback")
        assertTrue(a.accepts(mapOf("code" to "c", "state" to attempt.state), attempt))
        assertFalse(a.accepts(mapOf("code" to "c", "state" to "other"), attempt))
        // にはコードがありますが、状態がありません: 今回は開始されていませんでした
        assertFalse(a.accepts(mapOf("code" to "c"), attempt))
        // は、拒否するときに状態もコードも表示しませんが、エラーを表示します。同意されていないかのように受け入れます。何もないのは今回ではない
        assertTrue(a.accepts(mapOf("error" to "access_denied"), attempt))
        assertFalse(a.accepts(emptyMap(), attempt))
        assertEquals("c", a.readCallback(mapOf("code" to "c", "state" to attempt.state), attempt))
        val denied = runCatching { a.readCallback(mapOf("error" to "access_denied"), attempt) }.exceptionOrNull() as AccountSignInException
        assertEquals(AccountSignInFailure.Denied, denied.reason)
        val forged = runCatching { a.readCallback(mapOf("code" to "c", "state" to "x"), attempt) }.exceptionOrNull() as AccountSignInException
        assertEquals(AccountSignInFailure.Rejected, forged.reason)
    }

    @Test fun exchangePostsCodeAndVerifierAndReturnsTheKey() = runBlocking {
        val a = auth()
        val attempt = a.newAttempt("http://localhost:1/auth/callback")
        server.enqueue(json(body = """{"key":"sk-or-v1-SECRET","user_id":"u1"}"""))
        assertEquals("sk-or-v1-SECRET", a.exchange("the-code", attempt))
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/v1/auth/keys", req.path)
        assertTrue(req.getHeader("Content-Type").orEmpty().startsWith("application/json"))
        val body = Json.parseToJsonElement(req.body.readUtf8()).jsonObject
        assertEquals("the-code", body["code"]!!.jsonPrimitive.content)
        assertEquals(attempt.verifier, body["code_verifier"]!!.jsonPrimitive.content)
        assertEquals("S256", body["code_challenge_method"]!!.jsonPrimitive.content)
    }

    @Test fun exchangeErrorsKeepOnlyTheStatus() = runBlocking {
        val a = auth()
        val attempt = a.newAttempt("http://localhost:1/auth/callback")
        server.enqueue(json(403, """{"error":{"message":"Invalid code SHOULD-NOT-LEAK"}}"""))
        val expired = runCatching { a.exchange("c", attempt) }.exceptionOrNull() as AccountSignInException
        assertEquals(AccountSignInFailure.Expired, expired.reason)
        assertFalse(expired.message.orEmpty().contains("SHOULD-NOT-LEAK"))
        server.enqueue(json(400, """{"error":{"message":"bad method"}}"""))
        assertEquals(AccountSignInFailure.Rejected, (runCatching { a.exchange("c", attempt) }.exceptionOrNull() as AccountSignInException).reason)
        server.enqueue(json(200, """{"nokey":true}"""))
        assertEquals("no_key", (runCatching { a.exchange("c", attempt) }.exceptionOrNull() as AccountSignInException).code)
        server.enqueue(json(502, "{}"))
        assertEquals(AccountSignInFailure.Network, (runCatching { a.exchange("c", attempt) }.exceptionOrNull() as AccountSignInException).reason)
    }

    /** プロセス全体: 認証ページ アドレスのコールバック アドレスは localhost で、ブラウザーはコードと状態を返し、Key に変更され、ブラウザーは結果ページを受け取ります。 */
    @Test fun signInRunsThroughTheLoopbackCallback() = runBlocking {
        server.enqueue(json(body = """{"key":"sk-or-v1-abc"}"""))
        val opened = CompletableFuture<String>()
        val pages = mutableListOf<AccountSignInException?>()
        val job = async(Dispatchers.IO) {
            signInWithOpenRouter(auth(), openBrowser = { opened.complete(it) }, page = { pages += it; "<p>done</p>" }, timeoutMs = 10_000)
        }
        val url = withContext(Dispatchers.IO) { opened.get() }.toHttpUrl()
        val callback = url.queryParameter("callback_url")!!.toHttpUrl()
        assertEquals("localhost", callback.host)
        assertEquals("/auth/callback", callback.encodedPath)
        // 最初は一致しない状態になります。400 を返し、その後待機します。
        val bad = callback.newBuilder().addQueryParameter("code", "c").addQueryParameter("state", "forged").build()
        val badCode = withContext(Dispatchers.IO) { http.newCall(Request.Builder().url(bad.newBuilder().host("127.0.0.1").build()).build()).execute().use { it.code } }
        assertEquals(400, badCode)
        val good = callback.newBuilder().host("127.0.0.1")
            .addQueryParameter("code", "the-code").addQueryParameter("state", url.queryParameter("state")).build()
        val page = withContext(Dispatchers.IO) { http.newCall(Request.Builder().url(good).build()).execute().use { it.body!!.string() } }
        assertEquals("<p>done</p>", page)
        assertEquals("sk-or-v1-abc", job.await())
        assertEquals(listOf<AccountSignInException?>(null), pages)
    }
}
