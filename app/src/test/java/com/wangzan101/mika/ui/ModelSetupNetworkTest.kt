package com.wangzan101.mika.ui

import com.wangzan101.mika.core.model.ListFailure
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.i18n.ChineseTexts
import com.wangzan101.mika.settings.AesGcmKeyCipher
import com.wangzan101.mika.settings.ProviderConfig
import com.wangzan101.mika.settings.ProviderPresets
import com.wangzan101.mika.settings.ProviderStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.crypto.KeyGenerator

/**
 * 「テストして保存」実際の列モデルのインターフェイスとアダプター: パスによって応答するローカル サービスに対応するカスタム OpenAI 互換インターフェイス。
 * リスト インターフェイスが 404 を受信したとき、「この会社は実際にはリストを提供していません」(手動入力モデルは正常に調整できます) および「アドレスとインターフェイスのタイプが間違って入力されました」(呼び出しも 404 を受け取りました) を受け取りました。
 */
class ModelSetupNetworkTest {
    @get:Rule val texts = ChineseTexts()

    @get:Rule val tmp = TemporaryFolder()

    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val server = MockWebServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** パス → 何かを返します。書き込まれていないパスは 404 Web ページに戻ります。 [chat] は、リクエスト内のモデル名に基づいてコールバック インターフェイスを提供します。 */
    private val routes = mutableMapOf<String, () -> MockResponse>()
    private var chat: ((model: String) -> MockResponse)? = null
    private val chatModels = mutableListOf<String>()

    @Before fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path?.substringBefore('?')
                chat?.takeIf { path == "/chat/completions" }?.let { answer ->
                    val model = Regex("\"model\"\\s*:\\s*\"([^\"]+)\"").find(request.body.readUtf8())?.groupValues?.get(1).orEmpty()
                    synchronized(chatModels) { chatModels += model }
                    return answer(model)
                }
                return routes[path]?.invoke()
                    ?: MockResponse().setResponseCode(404).setHeader("Content-Type", "text/html").setBody("<html><body>Not Found</body></html>")
            }
        }
        server.start()
    }

    @After fun stop() {
        scope.cancel()
        server.shutdown()
    }

    private val base get() = server.url("/").toString().trimEnd('/')

    private fun toolCall() = MockResponse().setHeader("Content-Type", "application/json").setBody(
        """{"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[{"id":"c1","type":"function",""" +
            """"function":{"name":"get_time","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}""",
    )

    private fun setUp(): Pair<ProviderStore, ModelSetupViewModel> {
        val store = ProviderStore(tmp.root, AesGcmKeyCipher { key }, userAgent = "Mika/1.2.3")
        val vm = ModelSetupViewModel(store, scope, io = Dispatchers.IO)
        vm.selectPreset(ProviderPresets.custom.label)
        vm.setKind(ProviderConfig.Kind.OpenAiCompat)
        vm.setBaseUrl(base)
        vm.setApiKey("sk")
        return store to vm
    }

    private fun ModelSetupViewModel.testAndWait() = runBlocking {
        testAndSave()
        withTimeout(15_000) { state.first { it.form.test != ListState.Running } }
    }

    @Test fun listAndChatBoth404MeansNothingIsSaved() {
        val (store, vm) = setUp()
        vm.testAndWait()
        assertTrue("先にモデル名の入力を求める", vm.state.value.form.noList)
        assertTrue(store.providers.value.isEmpty())
        vm.setModel("gemini")
        vm.testAndWait()
        val failed = vm.state.value.form.test as ListState.Failed
        assertTrue(failed.message, failed.message.startsWith("モデルを一覧にできず、「gemini」でのテスト呼び出しでも見つかりませんでした。アドレスかインターフェースの種類が間違っている"))
        assertTrue(store.providers.value.isEmpty())
        assertTrue(store.list().isEmpty())
    }

    @Test fun list404ButChatWorksSavesANoListProvider() {
        routes["/chat/completions"] = ::toolCall
        val (store, vm) = setUp()
        vm.testAndWait()
        vm.setModel("my-model")
        vm.testAndWait()
        val p = store.providers.value.single()
        assertEquals(emptyList<Any>(), p.models)
        assertEquals(setOf("my-model"), p.verifiedModels)
        assertEquals("my-model", store.configFor(ModelRole.Main)?.model)
        assertFalse(vm.state.value.showForm)
        // が書き留めた「検証済み」は、読み返してもまだ残っています。
        assertEquals(setOf("my-model"), ProviderStore(tmp.root, AesGcmKeyCipher { key }).provider(p.id)?.verifiedModels)
        assertEquals(listOf("/models", "/models", "/chat/completions"), List(server.requestCount) { server.takeRequest().path })
    }

    /** 最初に他のモデルがリストにあります。アクティブ化されていないモデルは 400 にコールバックし、次のモデルを試行します。成功したら保存し、メインモデルとして使用します。最初に試したのが失敗したものを教えてください。 */
    @Test fun notActivatedFirstModelFallsBackToTheNext() {
        routes["/models"] = ::thirdPartyFirst
        chat = { model -> if (model == "vendor/second-chat") toolCall() else notActivated() }
        val (store, vm) = setUp()
        vm.testAndWait()
        assertEquals(listOf("vendor/first-chat", "vendor/second-chat"), chatModels)
        val p = store.providers.value.single()
        assertEquals("vendor/second-chat", store.configFor(ModelRole.Main)?.model)
        assertFalse(vm.state.value.showForm)
        val note = vm.state.value.probeNote.orEmpty()
        assertTrue(note, note.startsWith("最初に試した vendor/first-chat はテストに通りませんでした: このアカウントではこのモデルが有効になっていないか、アクセス権がありません(プロバイダーからの応答: The product is not activated"))
        assertTrue(note, note.endsWith("メインモデルは、テストに通った「vendor/second-chat」です。"))
        assertEquals(null, vm.state.value.pickToProbe)
        assertTrue("vendor/second-chat" in p.verifiedModels)
    }

    /** いくつか試しましたがどれも試しませんでした: キーは正しいです。サービス プロバイダーと無期限のマスター モデルを保存してください。リストから選択してください。 */
    @Test fun noCandidateActivatedSavesProviderWithoutMain() {
        routes["/models"] = ::thirdPartyFirst
        chat = { notActivated() }
        val (store, vm) = setUp()
        vm.testAndWait()
        assertEquals(listOf("vendor/first-chat", "vendor/second-chat", "vendor/third-chat"), chatModels)
        val p = store.providers.value.single()
        assertEquals(4, p.models?.size)
        assertEquals(null, store.roles.value.main)
        val s = vm.state.value
        assertEquals(p.id, s.pickToProbe)
        assertTrue(s.probeNote.orEmpty(), s.probeNote.orEmpty().startsWith("プロバイダーには接続できましたが、「vendor/first-chat」はテストに通りませんでした: "))
        assertTrue(s.probeNote.orEmpty(), s.probeNote.orEmpty().endsWith("vendor/first-chat、vendor/second-chat、vendor/third-chat を試しましたが、どれも有効になっていません。"))
    }

    /** 最初に試行されているモデルのアカウント制限はゼロ (制限: 429 の 0): 現在の制限に引っかかっているとはみなされません。その後、次のモデルを試して保存すると、それがメイン モデルになります。 */
    @Test fun quotaZeroFirstModelFallsBackToTheNext() {
        routes["/models"] = {
            MockResponse().setHeader("Content-Type", "application/json").setBody("""{"object":"list","data":[{"id":"paid-only"},{"id":"free-ok"}]}""")
        }
        chat = { model -> if (model == "free-ok") toolCall() else quotaZero() }
        val (store, vm) = setUp()
        vm.testAndWait()
        assertEquals(listOf("paid-only", "free-ok"), chatModels)
        val p = store.providers.value.single()
        assertEquals("free-ok", store.configFor(ModelRole.Main)?.model)
        assertTrue("free-ok" in p.verifiedModels)
        assertFalse(vm.state.value.showForm)
        val note = vm.state.value.probeNote.orEmpty()
        assertTrue(note, note.startsWith("最初に試した paid-only はテストに通りませんでした: このアカウントではこのモデルが有効になっていないか、アクセス権がありません(プロバイダーからの応答: "))
        assertTrue(note, note.endsWith("メインモデルは、テストに通った「free-ok」です。"))
    }

    /** は実際には現在制限されています (429 はクォータがゼロであるとは言っていません)。最初のものがモデル以外の理由で失敗し、次のものが保存または試行されませんでした。 */
    @Test fun plainRateLimitOnTheFirstModelSavesNothing() {
        routes["/models"] = {
            MockResponse().setHeader("Content-Type", "application/json").setBody("""{"object":"list","data":[{"id":"a"},{"id":"b"}]}""")
        }
        chat = {
            MockResponse().setResponseCode(429).setHeader("Content-Type", "application/json")
                .setBody("""{"error":{"message":"Rate limit reached for requests, limit: 60 per minute"}}""")
        }
        val (store, vm) = setUp()
        vm.testAndWait()
        assertEquals(listOf("a"), chatModels.distinct())
        assertTrue(vm.state.value.form.test is ListState.Failed)
        assertTrue(store.providers.value.isEmpty())
    }

    private fun quotaZero() = MockResponse().setResponseCode(429).setHeader("Content-Type", "application/json").setBody(
        """[{"error":{"code":429,"message":"You exceeded your current quota. Quota exceeded for metric: generativelanguage.googleapis.com/generate_content_free_tier_requests, limit: 0, model: paid-only","status":"RESOURCE_EXHAUSTED"}}]""",
    )

    private fun thirdPartyFirst() = MockResponse().setHeader("Content-Type", "application/json").setBody(
        """{"object":"list","data":[{"id":"vendor/first-chat"},{"id":"text-embedding-v4"},{"id":"vendor/second-chat"},{"id":"vendor/third-chat"}]}""",
    )

    private fun notActivated() = MockResponse().setResponseCode(400).setHeader("Content-Type", "application/json").setBody(
        """{"code":"InvalidParameter","message":"The product is not activated, please confirm that you have activated products and try again after activation.","request_id":"r1"}""",
    )

    @Test fun badKeyIsAKeyErrorAndNothingIsSaved() {
        routes["/models"] = { MockResponse().setResponseCode(401).setBody("""{"error":{"message":"invalid api key"}}""") }
        val (store, vm) = setUp()
        vm.testAndWait()
        assertEquals(ListState.Failed(ListFailure.Auth.text), vm.state.value.form.test)
        assertFalse("Key が正しくない場合はモデル名を求めない", vm.state.value.form.noList)
        assertTrue(store.providers.value.isEmpty())
    }

    /** カスタム選択 OpenAI の応答: 列モデルは依然として `GET /models` です。 (`/chat/completions` には触れずに) `/responses` を呼び出してみてください。保存されたサービス プロバイダーとメイン モデルはすべて、このインターフェイスを備えています。 */
    @Test fun customResponsesListsThenProbesTheResponsesEndpoint() {
        routes["/models"] = {
            MockResponse().setHeader("Content-Type", "application/json").setBody("""{"object":"list","data":[{"id":"gpt-x"}]}""")
        }
        routes["/responses"] = {
            MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                "data: {\"type\":\"response.output_item.done\",\"item\":{\"type\":\"function_call\",\"call_id\":\"c1\",\"name\":\"get_time\",\"arguments\":\"{}\"}}\n\n" +
                    "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\"}}\n\n",
            )
        }
        val (store, vm) = setUp()
        vm.setKind(ProviderConfig.Kind.OpenAiResponses)
        vm.testAndWait()
        val p = store.providers.value.single()
        assertEquals(ProviderConfig.Kind.OpenAiResponses, p.kind)
        assertEquals(setOf("gpt-x"), p.verifiedModels)
        val main = store.configFor(ModelRole.Main)
        assertEquals("gpt-x", main?.model)
        assertEquals(ProviderConfig.Kind.OpenAiResponses, main?.kind)
        assertFalse(vm.state.value.showForm)
        val requests = List(server.requestCount) { server.takeRequest() }
        assertEquals(listOf("/models", "/responses"), requests.map { it.path })
        assertTrue(requests.all { it.getHeader("Authorization") == "Bearer sk" })
    }
}
